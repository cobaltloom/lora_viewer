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
    func fetchFlights(server: TrackServerConfig, source: DataSource, groundAltitudeM: Double) async throws -> [String: [TrackServerPoint]] {
        let data = try await get("tracks", server: server, source: source, groundAltitudeM: groundAltitudeM)
        let decoded = try JSONDecoder().decode(TracksResponse.self, from: data)
        return decoded.tracks.mapValues(Self.points)
    }

    /// Every flight since midnight JST, landed ones included, keyed like `fetchFlights`: each
    /// glider's flights oldest first, each flight's points oldest first.
    func fetchTodaysFlights(server: TrackServerConfig, source: DataSource, groundAltitudeM: Double) async throws -> [String: [[TrackServerPoint]]] {
        let data = try await get("flights", server: server, source: source, groundAltitudeM: groundAltitudeM)
        let decoded = try JSONDecoder().decode(FlightsResponse.self, from: data)
        return decoded.flights.mapValues { flights in flights.map(Self.points) }
    }

    private func get(_ path: String, server: TrackServerConfig, source: DataSource, groundAltitudeM: Double) async throws -> Data {
        var components = URLComponents(url: server.url.appendingPathComponent(path), resolvingAgainstBaseURL: false)
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
        return data
    }

    /// Each point is [epochSeconds, lat, lon, altM].
    private static func points(_ raw: [[Double]]) -> [TrackServerPoint] {
        raw.compactMap { p in
            guard p.count >= 4 else { return nil }
            return TrackServerPoint(
                time: Date(timeIntervalSince1970: p[0]),
                coordinate: CLLocationCoordinate2D(latitude: p[1], longitude: p[2]),
                altitudeM: p[3]
            )
        }
    }

    private struct TracksResponse: Decodable {
        let tracks: [String: [[Double]]]
    }

    private struct FlightsResponse: Decodable {
        let flights: [String: [[[Double]]]]
    }
}

struct TrackServerPoint {
    let time: Date
    let coordinate: CLLocationCoordinate2D
    let altitudeM: Double
}

/// One batch of flying gliders' tracks fetched from the track server, published so the map view
/// re-times laps with the backfilled points. `id` makes each fetch a distinct change.
struct BackfilledFlights {
    let id = UUID()
    let flights: [String: [TrackServerPoint]]
}

/// Today's flights from the track server (see `TrackServerAPI.fetchTodaysFlights`), with each
/// glider's base name, so the map view can fill in turnpoint passages and laps flown while the app
/// wasn't watching this source - including gliders that have since landed and left the map.
struct TodaysFlights {
    let id = UUID()
    let flights: [String: [[TrackServerPoint]]]
    let baseNames: [String: String]
}

struct TrackServerConfig: Equatable {
    let url: URL
    let key: String
}
