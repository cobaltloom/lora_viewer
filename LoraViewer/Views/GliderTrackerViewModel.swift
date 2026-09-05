import Foundation
import CoreLocation

@MainActor
final class GliderTrackerViewModel: ObservableObject {
    @Published var config: AppConfig?
    @Published var positions: [GliderPosition] = []
    @Published var lastUpdated: Date?
    @Published var errorMessage: String?
    @Published var isLoading = false
    /// Each glider's positions seen so far this app launch, keyed by imei —
    /// drawn on the map as a trail. Kept in memory only (not persisted), and
    /// capped per glider so a long session doesn't grow unbounded.
    @Published private(set) var trails: [String: [CLLocationCoordinate2D]] = [:]

    private let maxTrailPointsPerGlider = 500

    private let api: TrailRouteAPI
    private let settings: APISettings
    private var pollingTask: Task<Void, Never>?

    init(settings: APISettings) {
        self.settings = settings
        self.api = TrailRouteAPI(settings: settings)
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
