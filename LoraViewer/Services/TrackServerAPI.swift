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

    /// The server's lap results for `source` from `from` to `to` (dates "yyyy-MM-dd"): date → course → laps.
    func fetchResults(server: TrackServerConfig, source: DataSource, from: String, to: String) async throws -> [String: DayResults] {
        var components = URLComponents(url: server.url.appendingPathComponent("results"), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "source", value: source.rawValue),
            URLQueryItem(name: "from", value: from),
            URLQueryItem(name: "to", value: to),
        ]
        guard let url = components?.url else { throw TrailRouteAPIError.invalidBaseURL }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue(server.key, forHTTPHeaderField: "X-Api-Key")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
        return try JSONDecoder().decode(ResultsResponse.self, from: data).results
    }

    private struct ResultsResponse: Decodable {
        let results: [String: DayResults]
    }

    /// The competition a representative last saved (see `Competition`), or nil if none yet.
    func fetchCompetition(server: TrackServerConfig) async throws -> Competition? {
        var request = URLRequest(url: server.url.appendingPathComponent("standings"))
        request.timeoutInterval = 15
        request.setValue(server.key, forHTTPHeaderField: "X-Api-Key")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw TrailRouteAPIError.invalidResponse
        }
        return try JSONDecoder().decode(StandingsResponse.self, from: data).standings
    }

    /// Saves `competition` for every app to see (`editorKey` only if the server sets one).
    func saveCompetition(server: TrackServerConfig, editorKey: String, competition: Competition) async throws -> Competition {
        var request = URLRequest(url: server.url.appendingPathComponent("standings"))
        request.httpMethod = "PUT"
        request.timeoutInterval = 15
        request.setValue(server.key, forHTTPHeaderField: "X-Api-Key")
        request.setValue(editorKey, forHTTPHeaderField: "X-Editor-Key")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var payload = competition
        payload.updatedAt = nil
        request.httpBody = try JSONEncoder().encode(payload)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw TrailRouteAPIError.invalidResponse }
        if http.statusCode == 403 { throw TrailRouteAPIError.serverError("合言葉が違います") }
        guard (200..<300).contains(http.statusCode),
              let saved = try JSONDecoder().decode(StandingsResponse.self, from: data).standings else {
            throw TrailRouteAPIError.serverError("保存できませんでした(\(http.statusCode))")
        }
        return saved
    }

    private struct StandingsResponse: Decodable {
        let standings: Competition?
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

/// A competition as set up in the app (shared through the track server): its period, and per day the
/// course flown and any corrected team scores, plus shared handicaps. The teams are the gliders
/// themselves (each source's own ids), and their laps come from the track server, which times every
/// flight whether or not any app was open (see `LapResult`).
struct Competition: Codable, Equatable {
    struct Day: Codable, Equatable {
        /// The course flown that day (an app course name); nil = the one most laps were completed on.
        var course: String?
        /// Glider id → corrected scores (penalties, or where they differ from the official results).
        var scores: [String: DayScore] = [:]
    }

    /// A team's score for one day: 団体 (best three) and 全国選出点 (best one).
    struct DayScore: Codable, Equatable {
        var total: Double?
        var national: Double?
    }

    var name: String = ""
    /// First and last competition days, "yyyy-MM-dd".
    var startDate: String?
    var endDate: String?
    /// Date ("yyyy-MM-dd") → that day's settings.
    var days: [String: Day] = [:]
    /// Glider id → competition handicap, overriding each device's own.
    var handicaps: [String: Double] = [:]
    var updatedAt: Double?
    /// When the track server confirmed every day's scores into `days`, 3 days after the last day, so
    /// the standings outlive its results (kept 60 days).
    var finalizedAt: Double?

    static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    /// The competition days up to and including `until`'s day, as day starts in order.
    func dates(until: Date) -> [Date] {
        let calendar = Calendar.current
        guard let start = startDate.flatMap(Self.dateFormatter.date(from:)) else { return [] }
        let last = min(endDate.flatMap(Self.dateFormatter.date(from:)) ?? until, until)
        var dates: [Date] = []
        var day = calendar.startOfDay(for: start)
        let lastDay = calendar.startOfDay(for: last)
        while day <= lastDay, dates.count < 366 {
            dates.append(day)
            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        return dates
    }
}

/// One lap, or partly flown course, the track server timed (see its /results): `finish` and
/// `lapSeconds` for a lap, `passed` scoring turnpoints for a partly flown course.
struct LapResult: Decodable, Equatable {
    let id: String
    let start: Double
    var finish: Double?
    var lapSeconds: Double?
    let distanceKm: Double
    var goalAltitudeM: Double?
    var passed: [String]?
}

/// One day's server results: course name → laps and partly flown courses.
typealias DayResults = [String: [LapResult]]

struct TrackServerConfig: Equatable {
    let url: URL
    let key: String
}
