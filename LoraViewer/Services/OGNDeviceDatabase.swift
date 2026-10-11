import Foundation

/// One OGN device database (DDB) entry: the owner's registration and privacy choices.
struct OGNDevice {
    let registration: String?
    let competitionID: String?
    /// false = the owner asked not to be shown at all.
    let isTracked: Bool
    /// false = the owner asked not to be identified; we don't show these either.
    let isIdentified: Bool
}

/// The OGN device database (http://ddb.glidernet.org), cached on disk and refreshed daily. OGN's data
/// usage rules require anyone redistributing OGN data to follow these privacy choices, so positions
/// are not shown until it has been loaded at least once.
actor OGNDeviceDatabase {
    private static let url = URL(string: "https://ddb.glidernet.org/download/?j=1&t=1")!
    private static let maxAge: TimeInterval = 24 * 3600

    private let session: URLSession
    private var devices: [String: OGNDevice]?
    private var loadedAt: Date?

    init(session: URLSession = .shared) {
        self.session = session
    }

    private var cacheURL: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("ogn_ddb.json")
    }

    /// Returns the database, downloading it if the cache is missing or over a day old. Falls back to
    /// a stale cache if the download fails; throws only when there is nothing at all.
    func get() async throws -> [String: OGNDevice] {
        if let devices, let loadedAt, Date().timeIntervalSince(loadedAt) < Self.maxAge {
            return devices
        }
        let file = cacheURL
        let modified = (try? FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate]) as? Date
        if modified.map({ Date().timeIntervalSince($0) >= Self.maxAge }) ?? true {
            try? await download(to: file)
        }
        guard let data = try? Data(contentsOf: file) else {
            throw TrailRouteAPIError.serverError("OGNの機体データベースを取得できませんでした")
        }
        let parsed = try Self.parse(data)
        devices = parsed
        loadedAt = Date()
        return parsed
    }

    private func download(to file: URL) async throws {
        var request = URLRequest(url: Self.url)
        request.setValue(OGNAPI.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
        _ = try Self.parse(data) // don't replace a good cache with something unparseable
        try data.write(to: file, options: .atomic)
    }

    static func parse(_ data: Data) throws -> [String: OGNDevice] {
        let decoded = try JSONDecoder().decode(DDBResponse.self, from: data)
        var result: [String: OGNDevice] = [:]
        for entry in decoded.devices {
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
