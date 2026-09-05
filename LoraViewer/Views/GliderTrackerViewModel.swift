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
    @Published private(set) var trails: [String: [CLLocationCoordinate2D]] = [:]
    /// Kept in sync with `AlertSettings.minimumFlyingAltitudeM` by the view,
    /// since this view model has no environment access of its own.
    var groundAltitudeThresholdM: Double = 60

    private let maxTrailPointsPerGlider = 500
    private let trailsStorageKey = "gliderTrails"

    private let api: TrailRouteAPI
    private let settings: APISettings
    private var pollingTask: Task<Void, Never>?

    init(settings: APISettings) {
        self.settings = settings
        self.api = TrailRouteAPI(settings: settings)
        loadTrails()
    }

    func nameFor(index: String) -> String {
        config?.nameMasterDisplayed[index] ?? "#\(index)"
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
        isLoading = true
        defer { isLoading = false }
        do {
            positions = try await api.fetchCurrentPositions()
            recordTrailPoints(from: positions)
            lastUpdated = Date()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func recordTrailPoints(from positions: [GliderPosition]) {
        for glider in positions {
            guard let alt = glider.alt, alt > groundAltitudeThresholdM else {
                trails.removeValue(forKey: glider.imei)
                continue
            }
            var points = trails[glider.imei] ?? []
            if let last = points.last, last.latitude == glider.lat, last.longitude == glider.lon {
                continue
            }
            points.append(glider.coordinate)
            if points.count > maxTrailPointsPerGlider {
                points.removeFirst(points.count - maxTrailPointsPerGlider)
            }
            trails[glider.imei] = points
        }
        saveTrails()
    }

    private func loadTrails() {
        guard let data = UserDefaults.standard.data(forKey: trailsStorageKey),
              let decoded = try? JSONDecoder().decode([String: [TrailPoint]].self, from: data)
        else { return }
        trails = decoded.mapValues { $0.map(\.coordinate) }
    }

    private func saveTrails() {
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
                let interval = max(self.settings.refreshIntervalSeconds, 3)
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            }
        }
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }
}
