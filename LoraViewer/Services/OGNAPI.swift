import Foundation

/// Talks to OGN Japan (https://ogn.ezoe.net), using the same `/api/positions` endpoint the
/// site's own `app.js` polls. Only used once `APISettings.isOGNAvailable` is on.
final class OGNAPI {
    static let baseURL = URL(string: "https://ogn.ezoe.net/")!
    static let attribution = "データ提供: OGN Japan (ogn.ezoe.net)"
    /// How far back the server should look. The site itself asks for 90s; we only need each
    /// aircraft's latest fix, but a shorter window would drop aircraft between sparse fixes.
    private static let sinceSeconds = 90

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func fetchCurrentPositions() async throws -> [GliderPosition] {
        var components = URLComponents(url: Self.baseURL.appendingPathComponent("api/positions"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "since", value: String(Self.sinceSeconds))]
        guard let url = components?.url else { throw TrailRouteAPIError.invalidBaseURL }

        var request = URLRequest(url: url)
        // Identify ourselves so the operator can tell app traffic apart from browsers.
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
        let decoded = try JSONDecoder().decode(OGNPositionsResponse.self, from: data)
        return Self.latestPositions(from: decoded.aircraft)
    }

    /// The response holds every fix in the window; keep each aircraft's newest one. Aircraft whose
    /// owners opted out of tracking are dropped (the server already leaves random-id aircraft out
    /// of `aircraft` and lists them separately under `anon`, which we never read).
    static func latestPositions(from fixes: [OGNFix]) -> [GliderPosition] {
        var latest: [String: OGNFix] = [:]
        for fix in fixes where fix.lat != nil && fix.lon != nil && !fix.isTrackingRefused {
            if let current = latest[fix.deviceID], (current.date ?? .distantPast) >= (fix.date ?? .distantPast) {
                continue
            }
            latest[fix.deviceID] = fix
        }
        return latest.values.compactMap { $0.gliderPosition }
    }

    private static let userAgent: String = {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        return "LoraViewer/\(version) (iOS)"
    }()
}

struct OGNPositionsResponse: Decodable {
    let aircraft: [OGNFix]
}

/// One position report from `/api/positions`.
struct OGNFix: Decodable {
    let deviceID: String
    let lat: Double?
    let lon: Double?
    let altitudeM: Double?
    let timestamp: String?
    let registration: String?
    let competitionID: String?
    let hidden: Bool
    let noTracking: Bool

    enum CodingKeys: String, CodingKey {
        case deviceID = "device_id"
        case lat, lon
        case altitudeM = "altitude_m"
        case timestamp = "ts"
        case registration
        case competitionID = "cn"
        case hidden
        case noTracking = "no_tracking"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = try c.decodeLenientString(forKey: .deviceID)
        lat = try c.decodeLenientDouble(forKey: .lat)
        lon = try c.decodeLenientDouble(forKey: .lon)
        altitudeM = try c.decodeLenientDouble(forKey: .altitudeM)
        timestamp = try? c.decode(String.self, forKey: .timestamp)
        registration = (try? c.decode(String.self, forKey: .registration)).flatMap { $0.isEmpty ? nil : $0 }
        competitionID = (try? c.decode(String.self, forKey: .competitionID)).flatMap { $0.isEmpty ? nil : $0 }
        hidden = (try? c.decode(Bool.self, forKey: .hidden)) ?? false
        noTracking = (try? c.decode(Bool.self, forKey: .noTracking)) ?? false
    }

    var isTrackingRefused: Bool { hidden || noTracking || deviceID.isEmpty }

    var date: Date? { timestamp.flatMap(OGNFix.parseDate) }

    /// Marker label: the competition no. if set, else the registration without the "JA" prefix
    /// (e.g. "JA21MA" -> "21MA"), else the tail of the device id.
    var markerLabel: String {
        if let competitionID { return competitionID }
        if let registration {
            return registration.hasPrefix("JA") ? String(registration.dropFirst(2)) : registration
        }
        return String(deviceID.suffix(4))
    }

    var displayName: String {
        switch (competitionID, registration) {
        case let (cn?, reg?): return "\(cn) \(reg)"
        case let (cn?, nil): return cn
        case let (nil, reg?): return reg
        case (nil, nil): return deviceID
        }
    }

    var gliderPosition: GliderPosition? {
        guard let lat, let lon else { return nil }
        return GliderPosition(
            imei: deviceID,
            index: markerLabel,
            lat: lat,
            lon: lon,
            alt: altitudeM,
            source: .ogn,
            isDisconnected: false,
            positionDateTimeUTC: date,
            sourceName: displayName
        )
    }

    private static let isoWithFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let iso = ISO8601DateFormatter()

    static func parseDate(_ string: String) -> Date? {
        isoWithFraction.date(from: string) ?? iso.date(from: string)
    }
}
