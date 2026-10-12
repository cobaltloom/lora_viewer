import Foundation
import CoreLocation

/// The club's own track server (see `server/` in the repo): it collects OGN and LoRa positions
/// around the clock, so it can hand back each flying glider's trail from takeoff - including what
/// was flown before this app was opened. Configured from Firestore (`meta/serverConfig`
/// `trackServerUrl` / `trackServerKey`); without them the app just draws trails live as before.
final class TrackServerAPI {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    /// Each currently flying glider's points since takeoff, keyed by the same id the app uses
    /// (LoRa IMEI / OGN callsign), oldest first.
    func fetchFlights(server: TrackServerConfig, source: DataSource, groundAltitudeM: Double) async throws -> [String: [CLLocationCoordinate2D]] {
        var components = URLComponents(url: server.url.appendingPathComponent("tracks"), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "source", value: source.rawValue),
            URLQueryItem(name: "groundAltM", value: String(Int(groundAltitudeM))),
        ]
        guard let url = components?.url else { throw TrailRouteAPIError.invalidBaseURL }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue(server.key, forHTTPHeaderField: "X-Api-Key")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
        let decoded = try JSONDecoder().decode(TracksResponse.self, from: data)
        // Each point is [epochSeconds, lat, lon, altM].
        return decoded.tracks.mapValues { points in
            points.compactMap { p in
                p.count >= 3 ? CLLocationCoordinate2D(latitude: p[1], longitude: p[2]) : nil
            }
        }
    }

    private struct TracksResponse: Decodable {
        let tracks: [String: [[Double]]]
    }
}

struct TrackServerConfig: Equatable {
    let url: URL
    let key: String
}
