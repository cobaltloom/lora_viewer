import Foundation

/// Persists turnpoint-passage events (see `AlertNotifier.notifyTurnpointPassage`)
/// to UserDefaults so they survive app relaunch and can be reviewed later in
/// the day even if a push notification was missed. This is a rolling log for
/// same-day review, not a full flight-log archive, so entries older than
/// `retentionDays` are dropped on load.
@MainActor
final class TurnpointPassageLog: ObservableObject {
    @Published private(set) var records: [TurnpointPassageRecord] = []

    private let storageKey = "turnpointPassageLog"
    private let retentionDays = 2

    init() {
        load()
    }

    var todaysRecords: [TurnpointPassageRecord] {
        records
            .filter { Calendar.current.isDateInToday($0.timestamp) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func record(gliderName: String, turnpointName: String, altitudeM: Double?) {
        records.append(TurnpointPassageRecord(gliderName: gliderName, turnpointName: turnpointName, altitudeM: altitudeM))
        prune()
        save()
    }

    func clearToday() {
        records.removeAll { Calendar.current.isDateInToday($0.timestamp) }
        save()
    }

    private func prune() {
        guard let cutoff = Calendar.current.date(byAdding: .day, value: -retentionDays, to: Date()) else { return }
        records.removeAll { $0.timestamp < cutoff }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([TurnpointPassageRecord].self, from: data)
        else { return }
        records = decoded
        prune()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        UserDefaults.standard.set(data, forKey: storageKey)
    }
}
