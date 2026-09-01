import Foundation

@MainActor
final class GliderTrackerViewModel: ObservableObject {
    @Published var config: AppConfig?
    @Published var positions: [GliderPosition] = []
    @Published var lastUpdated: Date?
    @Published var errorMessage: String?
    @Published var isLoading = false

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
        guard !settings.secretKey.isEmpty else {
            errorMessage = "設定画面でシークレットキーを入力してください。"
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            positions = try await api.fetchCurrentPositions()
            lastUpdated = Date()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
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
