import Foundation

enum TrailRouteAPIError: LocalizedError {
    case invalidBaseURL
    case invalidResponse
    case serverError(String)

    var errorDescription: String? {
        switch self {
        case .invalidBaseURL:
            return "サーバーURLが正しくありません。設定を確認してください。"
        case .invalidResponse:
            return "サーバーからの応答を解析できませんでした。"
        case .serverError(let msg):
            return "サーバーエラー: \(msg)"
        }
    }
}

/// Talks to the same PHP endpoints used by the site's own `js/plot53.js`.
final class TrailRouteAPI {
    private let settings: APISettings
    private let session: URLSession

    init(settings: APISettings, session: URLSession = .shared) {
        self.settings = settings
        self.session = session
    }

    func fetchConfig() async throws -> AppConfig {
        guard let base = settings.baseURL else { throw TrailRouteAPIError.invalidBaseURL }
        let url = base.appendingPathComponent("load_config.php")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"

        let (data, response) = try await session.data(for: request)
        try Self.validateHTTP(response)

        let decoded = try JSONDecoder().decode(AppConfigResponse.self, from: data)
        guard decoded.result == "0" else {
            throw TrailRouteAPIError.serverError(decoded.message ?? decoded.result)
        }
        return decoded.config
    }

    func fetchCurrentPositions() async throws -> [GliderPosition] {
        guard let base = settings.baseURL else { throw TrailRouteAPIError.invalidBaseURL }
        var components = URLComponents(url: base.appendingPathComponent("mapapi.php"), resolvingAgainstBaseURL: false)
        // The per-account URL path is the site's actual access control; `key` is only
        // sent when the user has supplied one, in case some deployments still check it.
        var queryItems: [URLQueryItem] = [
            URLQueryItem(name: "rdm", value: String(Double.random(in: 0...1)))
        ]
        if !settings.secretKey.isEmpty {
            queryItems.append(URLQueryItem(name: "key", value: settings.secretKey))
        }
        components?.queryItems = queryItems
        guard let url = components?.url else { throw TrailRouteAPIError.invalidBaseURL }

        let (data, response) = try await session.data(from: url)
        try Self.validateHTTP(response)

        let decoded = try JSONDecoder().decode(CurrentPositionsResponse.self, from: data)
        guard decoded.result == "0" else {
            throw TrailRouteAPIError.serverError(decoded.message ?? decoded.result)
        }
        return decoded.positions
    }

    func fetchTrackLog(start: Date?, end: Date?) async throws -> [String: TrackLogDevice] {
        guard let base = settings.baseURL else { throw TrailRouteAPIError.invalidBaseURL }
        let url = base.appendingPathComponent("query_position_log.php")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")

        let startStr = start.map { TrailRouteDateFormatter.utc.string(from: $0) } ?? ""
        let endStr = end.map { TrailRouteDateFormatter.utc.string(from: $0) } ?? ""
        let body = "datetimeStart=\(startStr.urlFormEncoded)&datetimeEnd=\(endStr.urlFormEncoded)"
        request.httpBody = body.data(using: .utf8)

        let (data, response) = try await session.data(for: request)
        try Self.validateHTTP(response)

        let decoded = try JSONDecoder().decode(TrackLogResponse.self, from: data)
        guard decoded.result == "0" else {
            throw TrailRouteAPIError.serverError(decoded.message ?? decoded.result)
        }
        return decoded.positionData
    }

    private static func validateHTTP(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
    }
}

private extension String {
    var urlFormEncoded: String {
        addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? self
    }
}
