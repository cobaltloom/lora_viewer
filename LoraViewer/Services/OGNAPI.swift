import Foundation
import Network

/// Live positions straight from the Open Glider Network: a read-only APRS-IS connection to
/// aprs.glidernet.org, filtered to a radius around Menuma, kept open while the OGN source is in use.
/// Beacons are collected as they stream in and `fetchCurrentPositions` returns the latest per
/// aircraft, so the rest of the app can keep polling exactly as it does for LoRa.
///
/// Follows OGN's data usage rules (https://www.glidernet.org/ogn-data-usage/): stealth/no-tracking/
/// random-address aircraft and DDB entries marked not tracked or not identified are never shown.
final class OGNAPI {
    static let attribution = "データ提供: Open Glider Network (OGN)"
    static let userAgent: String = "LoraViewer/\(appVersion) (iOS)"

    private static let host = NWEndpoint.Host("aprs.glidernet.org")
    private static let port = NWEndpoint.Port(rawValue: 14580)!
    /// 150 km around the Menuma OGN receiver - all of Kanto, without the whole country's traffic.
    private static let filter = "r/36.2113/139.4188/150"
    private static let maxFixAge: TimeInterval = 90
    /// Some trackers' clocks are hours off; such a fix would linger past `maxFixAge` or vanish at
    /// once, so it's dropped rather than shown at a misleading time.
    private static let maxClockSkew: TimeInterval = 300
    private static let reconnectDelay: TimeInterval = 5
    private static let keepaliveInterval: TimeInterval = 60
    /// The server sends a "#" comment every ~20s, so this much silence means a dead link.
    private static let silenceTimeout: TimeInterval = 90
    private static let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"

    private let deviceDatabase = OGNDeviceDatabase()
    /// Everything below is only touched on `queue`.
    private let queue = DispatchQueue(label: "OGNAPI")
    private var connection: NWConnection?
    private var keepaliveTimer: DispatchSourceTimer?
    private var receiveBuffer = Data()
    private var latestBeacons: [String: OGNBeacon] = [:]
    private var lastReceived = Date.distantPast
    private var lastConnectAttempt = Date.distantPast

    /// A read-only APRS login still needs a callsign; OGN asks that it be unique per client.
    private let callsign = "LV" + String((0..<7).map { _ in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".randomElement()! })

    deinit {
        connection?.cancel()
        keepaliveTimer?.cancel()
    }

    func fetchCurrentPositions() async throws -> [GliderPosition] {
        let devices = try await deviceDatabase.get()
        let beacons: [OGNBeacon] = queue.sync {
            ensureStreaming()
            let cutoff = Date().addingTimeInterval(-Self.maxFixAge)
            latestBeacons = latestBeacons.filter { $0.value.timestamp >= cutoff }
            return Array(latestBeacons.values)
        }
        return beacons.compactMap { beacon in
            let device = devices[beacon.address]
            if let device, !device.isTracked || !device.isIdentified { return nil }
            return Self.gliderPosition(from: beacon, device: device)
        }
    }

    /// Closes the stream; the next `fetchCurrentPositions` reconnects.
    func stop() {
        queue.sync {
            disconnect()
            latestBeacons = [:]
        }
    }

    static func gliderPosition(from beacon: OGNBeacon, device: OGNDevice?) -> GliderPosition {
        let registration = device?.registration
        let competitionID = device?.competitionID
        // Marker label: the competition no. if set, else the registration without the "JA" prefix
        // (e.g. "JA21MA" -> "21MA"), else the tail of the device address.
        let markerLabel = competitionID
            ?? registration.map { $0.hasPrefix("JA") ? String($0.dropFirst(2)) : $0 }
            ?? String(beacon.address.suffix(4))
        let name = [competitionID, registration].compactMap { $0 }.joined(separator: " ")
        return GliderPosition(
            imei: beacon.callsign,
            index: markerLabel,
            lat: beacon.lat,
            lon: beacon.lon,
            alt: beacon.altitudeM,
            source: .ogn,
            isDisconnected: false,
            positionDateTimeUTC: beacon.timestamp,
            sourceName: name.isEmpty ? beacon.callsign : name
        )
    }

    // MARK: - Streaming (on `queue`)

    private func ensureStreaming() {
        if connection != nil, Date().timeIntervalSince(lastReceived) > Self.silenceTimeout,
           Date().timeIntervalSince(lastConnectAttempt) > Self.silenceTimeout {
            disconnect()
        }
        guard connection == nil, Date().timeIntervalSince(lastConnectAttempt) >= Self.reconnectDelay else { return }
        lastConnectAttempt = Date()
        receiveBuffer = Data()

        let connection = NWConnection(host: Self.host, port: Self.port, using: .tcp)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection, connection === self.connection else { return }
            switch state {
            case .ready:
                self.lastReceived = Date()
                self.send("user \(self.callsign) pass -1 vers LoraViewer \(Self.appVersion) filter \(Self.filter)\r\n")
                self.startKeepalive()
                self.receive(on: connection)
            case .failed, .cancelled:
                self.disconnect()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self, connection === self.connection else { return }
            if let data, !data.isEmpty {
                self.lastReceived = Date()
                self.receiveBuffer.append(data)
                self.processLines()
            }
            if isComplete || error != nil {
                self.disconnect()
            } else {
                self.receive(on: connection)
            }
        }
    }

    private func processLines() {
        let newline = UInt8(ascii: "\n")
        while let end = receiveBuffer.firstIndex(of: newline) {
            let lineData = receiveBuffer[receiveBuffer.startIndex..<end]
            receiveBuffer.removeSubrange(receiveBuffer.startIndex...end)
            guard let line = String(data: lineData, encoding: .isoLatin1)?
                .trimmingCharacters(in: .whitespacesAndNewlines) else { continue }
            let now = Date()
            guard let beacon = OGNBeaconParser.parse(line, now: now),
                  OGNBeaconParser.isDisplayable(beacon),
                  abs(beacon.timestamp.timeIntervalSince(now)) <= Self.maxClockSkew else { continue }
            if let previous = latestBeacons[beacon.callsign], previous.timestamp > beacon.timestamp { continue }
            latestBeacons[beacon.callsign] = beacon
        }
    }

    private func startKeepalive() {
        keepaliveTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.keepaliveInterval, repeating: Self.keepaliveInterval)
        timer.setEventHandler { [weak self] in self?.send("#keepalive\r\n") }
        timer.resume()
        keepaliveTimer = timer
    }

    private func send(_ text: String) {
        connection?.send(content: text.data(using: .utf8), completion: .contentProcessed { _ in })
    }

    private func disconnect() {
        keepaliveTimer?.cancel()
        keepaliveTimer = nil
        let old = connection
        connection = nil
        old?.cancel()
    }
}
