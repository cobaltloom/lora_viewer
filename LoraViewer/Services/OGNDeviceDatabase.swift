import Foundation

/// One OGN device database (DDB) entry: the owner's registration and privacy choices.
struct OGNDevice {
    let registration: String?
    let competitionID: String?
    /// false = the owner asked not to be shown at all.
    let isTracked: Bool
    /// false = the owner asked not to be identified; shown by position only, without any name.
    let isIdentified: Bool
}

/// A device database in the OGN DDB JSON format, cached on disk and refreshed daily: the OGN DDB
/// itself (`ogn()`) or FlarmNet's export of the same format (`flarmNet()`). OGN's data usage rules
/// require anyone redistributing OGN data to follow the OGN DDB's privacy choices, so positions are
/// not shown until it has been loaded at least once.
actor OGNDeviceDatabase {
    private static let maxAge: TimeInterval = 24 * 3600
    private static let retryInterval: TimeInterval = 10 * 60

    private let session: URLSession
    private let url: URL
    private let cacheFileName: String
    /// Only entries whose registration starts with this are kept; nil keeps everything.
    private let registrationPrefix: String?
    private var devices: [String: OGNDevice]?
    private var loadedAt: Date?
    private var lastDownloadAttempt: Date?

    private init(url: URL, cacheFileName: String, registrationPrefix: String?, session: URLSession) {
        self.url = url
        self.cacheFileName = cacheFileName
        self.registrationPrefix = registrationPrefix
        self.session = session
    }

    /// The OGN DDB (http://ddb.glidernet.org): names and the owners' privacy choices.
    static func ogn(session: URLSession = .shared) -> OGNDeviceDatabase {
        OGNDeviceDatabase(url: URL(string: "https://ddb.glidernet.org/download/?j=1&t=1")!,
                          cacheFileName: "ogn_ddb.json", registrationPrefix: nil, session: session)
    }

    /// FlarmNet (https://www.flarmnet.org), Japanese registrations only: names for aircraft that are
    /// not in the OGN DDB.
    static func flarmNet(session: URLSession = .shared) -> OGNDeviceDatabase {
        OGNDeviceDatabase(url: URL(string: "https://www.flarmnet.org/files/ddb.json")!,
                          cacheFileName: "flarmnet_ddb.json", registrationPrefix: "JA", session: session)
    }

    private var cacheURL: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent(cacheFileName)
    }

    /// Returns the database, downloading it if the cache is missing or over a day old. Falls back to
    /// a stale cache if the download fails; throws only when there is nothing at all.
    func get() async throws -> [String: OGNDevice] {
        if let devices, let loadedAt, Date().timeIntervalSince(loadedAt) < Self.maxAge {
            return devices
        }
        let file = cacheURL
        let modified = (try? FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate]) as? Date
        // Without any cache a failed download would otherwise be retried on every refresh.
        let retryDue = lastDownloadAttempt.map { Date().timeIntervalSince($0) >= Self.retryInterval } ?? true
        if modified.map({ Date().timeIntervalSince($0) >= Self.maxAge }) ?? true, retryDue {
            lastDownloadAttempt = Date()
            try? await download(to: file)
        }
        guard let data = try? Data(contentsOf: file) else {
            throw TrailRouteAPIError.serverError("機体データベースを取得できませんでした")
        }
        let parsed = try Self.parse(data, registrationPrefix: registrationPrefix)
        devices = parsed
        loadedAt = Date()
        return parsed
    }

    private func download(to file: URL) async throws {
        var request = URLRequest(url: url)
        request.setValue(OGNAPI.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
        _ = try Self.parse(data, registrationPrefix: registrationPrefix) // don't replace a good cache with something unparseable
        try data.write(to: file, options: .atomic)
    }

    static func parse(_ data: Data, registrationPrefix: String? = nil) throws -> [String: OGNDevice] {
        let decoded = try JSONDecoder().decode(DDBResponse.self, from: data)
        var result: [String: OGNDevice] = [:]
        for entry in decoded.devices {
            if let registrationPrefix, !entry.registration.hasPrefix(registrationPrefix) { continue }
            result[entry.deviceID.uppercased()] = OGNDevice(
                registration: entry.registration.isEmpty ? nil : entry.registration,
                competitionID: entry.cn.isEmpty ? nil : entry.cn,
                isTracked: entry.tracked != "N",
                isIdentified: entry.identified != "N"
            )
        }
        return result
    }

    private struct DDBResponse: Decodable {
        let devices: [Entry]
    }

    private struct Entry: Decodable {
        let deviceID: String
        let registration: String
        let cn: String
        let tracked: String
        let identified: String

        enum CodingKeys: String, CodingKey {
            case deviceID = "device_id"
            case registration, cn, tracked, identified
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            deviceID = try c.decodeLenientString(forKey: .deviceID)
            registration = try c.decodeLenientString(forKey: .registration).trimmingCharacters(in: .whitespaces)
            cn = try c.decodeLenientString(forKey: .cn).trimmingCharacters(in: .whitespaces)
            tracked = try c.decodeLenientString(forKey: .tracked)
            identified = try c.decodeLenientString(forKey: .identified)
        }
    }
}
