import Foundation
import CoreLocation

private struct TrailPoint: Codable {
    let lat: Double
    let lon: Double

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }

    init(_ coordinate: CLLocationCoordinate2D) {
        lat = coordinate.latitude
        lon = coordinate.longitude
    }
}

@MainActor
final class GliderTrackerViewModel: ObservableObject {
    @Published var config: AppConfig?
    @Published var positions: [GliderPosition] = []
    @Published var lastUpdated: Date?
    @Published var errorMessage: String?
    @Published var isLoading = false
    /// Each glider's positions for its current flight, keyed by imei — drawn
    /// on the map as a trail. Persisted to UserDefaults so a trail survives
    /// the app being closed mid-flight, but cleared once that glider's
    /// altitude drops to/below `groundAltitudeThresholdM` (the flight has
    /// landed), so the next takeoff starts a fresh trail.
    @Published private(set) var trails: [String: [CLLocationCoordinate2D]] = [:] {
        didSet { assignTrailColorSlots() }
    }
    /// Each trailing glider's palette slot, so simultaneous trails get
    /// distinct colors. A slot is kept for as long as the glider's trail
    /// lasts, so its color never changes mid-flight; new gliders take the
    /// lowest free slot, so colors only repeat once more gliders are flying
    /// than the palette has colors.
    @Published private(set) var trailColorSlots: [String: Int] = [:]
    /// Each flying glider's timed positions this flight (live, plus any backfilled from the track
    /// server), kept for lap timing. Dropped when the glider lands, like its trail.
    @Published private(set) var timedTracks: [String: [TimedPoint]] = [:]
    private static let maxTimedPoints = 7200
    /// Each glider's latest fix at/below the ground threshold: a new flight's timed track starts
    /// with it if recent, since a winch launch can cross the start line between it and the first
    /// airborne fix (LoRa fixes are ~15 s apart).
    private var lastGroundFixes: [String: TimedPoint] = [:]
    private static let takeoffGroundFixMaxAge: TimeInterval = 60
    /// The latest flights fetched from the track server (see `backfillTrailsIfNeeded`).
    @Published private(set) var backfilledFlights: BackfilledFlights?
    /// Today's flights from the track server (see `fetchTodaysFlightsIfNeeded`).
    @Published private(set) var todaysFlights: TodaysFlights?
    /// Set when today's flights should be fetched again: at start, after a source switch, and on
    /// coming back to the foreground (see `refreshTodaysFlights`).
    private var needsTodaysFlights = true
    private var lastTodaysFlightsFetch = Date.distantPast
    static let trailColorCount = 8
    /// Kept in sync with `AlertSettings.minimumFlyingAltitudeM` by the view,
    /// since this view model has no environment access of its own.
    var groundAltitudeThresholdM: Double = 60

    private let maxTrailPointsPerGlider = 2000
    private let trailsStorageKey = "gliderTrails"
    /// Live trail points are saved at most this often (plus on going to the background, see
    /// `saveTrailsNow`): JSON-encoding every trail on each 2 s refresh kept the CPU busy.
    private static let trailSaveInterval: TimeInterval = 30
    private var lastTrailSave = Date.distantPast

    private let api: TrailRouteAPI
    private let ognAPI = OGNAPI()
    private let trackServerAPI = TrackServerAPI()
    /// Set when the next refresh should backfill trails from the track server: at start, after a
    /// source switch, and when a glider is first seen already flying.
    private var needsTrailBackfill = true
    private var lastTrailBackfill = Date.distantPast
    private static let minBackfillInterval: TimeInterval = 60
    private let settings: APISettings
    /// The source the current `positions`/`trails` came from, so switching sources starts clean
    /// instead of mixing one source's stale gliders into the other's map.
    private var lastDataSource: DataSource?
    private var pollingTask: Task<Void, Never>?

    init(settings: APISettings) {
        self.settings = settings
        self.api = TrailRouteAPI(settings: settings)
        loadTrails()
    }

    var dataSource: DataSource { settings.effectiveDataSource }

    func nameFor(_ glider: GliderPosition) -> String {
        glider.sourceName ?? config?.nameMasterDisplayed[glider.index] ?? "#\(glider.index)"
    }

    func loadConfigIfNeeded() async {
        guard config == nil else { return }
        do {
            config = try await api.fetchConfig()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshOnce() async {
        // Only for the first fetch: toggling this on every refresh re-rendered the whole map twice.
        let showsLoading = lastUpdated == nil
        if showsLoading { isLoading = true }
        defer { if showsLoading { isLoading = false } }
        let source = settings.effectiveDataSource
        if let lastDataSource, lastDataSource != source {
            positions = []
            trails = [:]
            timedTracks = [:]
            saveTrails()
            needsTrailBackfill = true
            lastTrailBackfill = .distantPast
            todaysFlights = nil
            needsTodaysFlights = true
            lastTodaysFlightsFetch = .distantPast
        }
        lastDataSource = source
        do {
            let fetched: [GliderPosition]
            switch source {
            case .lora:
                ognAPI.stop() // no-op unless we just switched away from OGN
                fetched = try await api.fetchCurrentPositions()
            case .ogn: fetched = try await ognAPI.fetchCurrentPositions()
            }
            // The source may have been switched while this request was in flight.
            guard source == settings.effectiveDataSource else { return }
            recordTimedPoints(from: fetched) // before `positions`, which the view's lap timing reacts to
            positions = fetched
            let hadTrail = Set(trails.keys)
            recordTrailPoints(from: positions)
            if positions.contains(where: { trails[$0.imei] != nil && !hadTrail.contains($0.imei) }) {
                needsTrailBackfill = true // newly seen in flight: its earlier part may be on the server
            }
            // Not awaited: a slow track server must never hold up the live refresh loop.
            Task { await backfillTrailsIfNeeded(source: source) }
            Task { await fetchTodaysFlightsIfNeeded(source: source) }
            lastUpdated = Date()
            if errorMessage != nil { errorMessage = nil }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Replaces flying gliders' trails with their whole flight from the track server, so trails
    /// reach back to takeoff even if the app was opened mid-flight. Live points keep appending
    /// afterwards. Best effort: without a configured server, or on any error, trails stay live-only.
    private func backfillTrailsIfNeeded(source: DataSource) async {
        guard needsTrailBackfill, let server = settings.trackServer,
              Date().timeIntervalSince(lastTrailBackfill) >= Self.minBackfillInterval else { return }
        needsTrailBackfill = false
        lastTrailBackfill = Date()
        guard let flights = try? await trackServerAPI.fetchFlights(
            server: server, source: source, groundAltitudeM: groundAltitudeThresholdM
        ), source == settings.effectiveDataSource else { return }
        var tracks = timedTracks
        for (id, points) in flights {
            guard let live = tracks[id], let lastServerTime = points.last?.time else { continue }
            let server = points.map { TimedPoint(time: $0.time, coordinate: $0.coordinate, altitudeM: $0.altitudeM) }
            tracks[id] = Array((server + live.filter { $0.time > lastServerTime }).suffix(Self.maxTimedPoints))
        }
        timedTracks = tracks
        backfilledFlights = BackfilledFlights(flights: flights)
        var updated = trails
        var changed = false
        // Only gliders still flying here (they have a live trail), and only if the server knows more.
        for (id, serverPoints) in flights {
            let points = serverPoints.map(\.coordinate)
            guard let live = updated[id], points.count > live.count else { continue }
            var merged = points
            // Keep the newest live point if the server hasn't caught up with it yet.
            if let newest = live.last, let last = merged.last,
               newest.latitude != last.latitude || newest.longitude != last.longitude {
                merged.append(newest)
            }
            updated[id] = Array(merged.suffix(maxTrailPointsPerGlider))
            changed = true
        }
        guard changed else { return }
        trails = updated
        saveTrails()
    }

    /// Asks for today's flights again on the next refresh, e.g. after the app was in the background.
    func refreshTodaysFlights() {
        needsTodaysFlights = true
    }

    /// Fetches every flight flown today on this source, landed ones included, so passages and laps
    /// flown while the app wasn't watching this source can be filled in. Best effort, like backfill.
    private func fetchTodaysFlightsIfNeeded(source: DataSource) async {
        guard needsTodaysFlights, let server = settings.trackServer,
              Date().timeIntervalSince(lastTodaysFlightsFetch) >= Self.minBackfillInterval else { return }
        needsTodaysFlights = false
        lastTodaysFlightsFetch = Date()
        guard let flights = try? await trackServerAPI.fetchTodaysFlights(
            server: server, source: source, groundAltitudeM: groundAltitudeThresholdM
        ), source == settings.effectiveDataSource else { return }
        var baseNames: [String: String] = [:]
        for id in flights.keys {
            if let glider = positions.first(where: { $0.imei == id }) {
                guard !glider.isAnonymous else { continue }
                baseNames[id] = nameFor(glider)
            } else if source == .ogn, let name = await ognAPI.baseName(forCallsign: id) {
                baseNames[id] = name
            }
            // A LoRa tracker not in the current positions can't be named; it's left out.
        }
        guard source == settings.effectiveDataSource else { return }
        todaysFlights = TodaysFlights(flights: flights, baseNames: baseNames)
    }

    private func recordTimedPoints(from positions: [GliderPosition]) {
        var tracks = timedTracks.filter { id, _ in positions.contains { $0.imei == id } }
        let now = Date()
        for glider in positions {
            let time = glider.positionDateTimeUTC ?? now
            guard let alt = glider.alt, alt > groundAltitudeThresholdM else {
                tracks.removeValue(forKey: glider.imei)
                if let alt = glider.alt { lastGroundFixes[glider.imei] = TimedPoint(time: time, coordinate: glider.coordinate, altitudeM: alt) }
                continue
            }
            var track = tracks[glider.imei] ?? []
            if track.isEmpty, let ground = lastGroundFixes[glider.imei], ground.time < time,
               time.timeIntervalSince(ground.time) <= Self.takeoffGroundFixMaxAge {
                track.append(ground)
            }
            if let last = track.last, time <= last.time { continue }
            track.append(TimedPoint(time: time, coordinate: glider.coordinate, altitudeM: alt))
            tracks[glider.imei] = Array(track.suffix(Self.maxTimedPoints))
        }
        timedTracks = tracks
    }

    private func recordTrailPoints(from positions: [GliderPosition]) {
        // Built on a copy and assigned once, so `trails` publishes (and reassigns color slots) once
        // per refresh rather than once per glider.
        var updated = trails
        var changed = false
        var landed = false
        for glider in positions {
            guard let alt = glider.alt, alt > groundAltitudeThresholdM else {
                if updated.removeValue(forKey: glider.imei) != nil { changed = true; landed = true }
                continue
            }
            var points = updated[glider.imei] ?? []
            if let last = points.last, last.latitude == glider.lat, last.longitude == glider.lon {
                continue
            }
            points.append(glider.coordinate)
            if points.count > maxTrailPointsPerGlider {
                points.removeFirst(points.count - maxTrailPointsPerGlider)
            }
            updated[glider.imei] = points
            changed = true
        }
        guard changed else { return }
        trails = updated
        // A landing is saved at once, so a reopened app doesn't bring back a finished flight's trail.
        if landed || Date().timeIntervalSince(lastTrailSave) >= Self.trailSaveInterval {
            saveTrails()
        }
    }

    private func assignTrailColorSlots() {
        var slots = trailColorSlots.filter { trails[$0.key] != nil }
        for imei in trails.keys.sorted() where slots[imei] == nil {
            let used = Set(slots.values)
            slots[imei] = (0..<Self.trailColorCount).first { !used.contains($0) } ?? 0
        }
        if slots != trailColorSlots {
            trailColorSlots = slots
        }
    }

    private func loadTrails() {
        guard let data = UserDefaults.standard.data(forKey: trailsStorageKey),
              let decoded = try? JSONDecoder().decode([String: [TrailPoint]].self, from: data)
        else { return }
        trails = decoded.mapValues { $0.map(\.coordinate) }
    }

    /// Saves any trail points not yet saved; called when the app goes to the background.
    func saveTrailsNow() {
        saveTrails()
    }

    private func saveTrails() {
        lastTrailSave = Date()
        let encoded = trails.mapValues { $0.map(TrailPoint.init) }
        guard let data = try? JSONEncoder().encode(encoded) else { return }
        UserDefaults.standard.set(data, forKey: trailsStorageKey)
    }

    func startPolling() {
        stopPolling()
        pollingTask = Task { [weak self] in
            guard let self else { return }
            await self.loadConfigIfNeeded()
            while !Task.isCancelled {
                await self.refreshOnce()
                let interval = self.settings.currentRefreshIntervalSeconds
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            }
        }
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
        ognAPI.stop()
    }
}
