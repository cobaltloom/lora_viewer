import Foundation

/// Persists turnpoint-passage events (see `AlertNotifier.notifyTurnpointPassage`)
/// to UserDefaults so they survive app relaunch and can be reviewed later in
/// the day even if a push notification was missed. This is a rolling log for
/// same-day review, not a full flight-log archive, so entries older than
/// `retentionDays` are dropped on load. LoRa and OGN (FLARM) keep separate
/// logs, like favorites: the same glider has different names in each, so a
/// shared log would mix them and could list one passage twice.
@MainActor
final class TurnpointPassageLog: ObservableObject {
    @Published private(set) var records: [TurnpointPassageRecord] = []

    /// Set by the map view whenever the effective data source changes.
    var dataSource: DataSource = .lora {
        didSet {
            guard dataSource != oldValue else { return }
            records = []
            load()
        }
    }

    /// The LoRa key predates OGN support and is kept as-is so existing records survive.
    private var storageKey: String {
        switch dataSource {
        case .lora: return "turnpointPassageLog"
        case .ogn: return "turnpointPassageLogOGN"
        }
    }
    private let retentionDays = 2

    init() {
        load()
    }

    var todaysRecords: [TurnpointPassageRecord] {
        records
            .filter { Calendar.current.isDateInToday($0.timestamp) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    /// Records a passage found afterwards in the track server's history (flown while the app was
    /// closed), at the time it actually happened - unless the same glider already has a record for
    /// this turnpoint within `duplicateWindow` (e.g. the live check caught it when the app opened).
    /// Also used for live passages, timed by the fix. Returns whether it was recorded.
    @discardableResult
    func recordPastPassage(gliderName: String, turnpointName: String, altitudeM: Double?, timestamp: Date) -> Bool {
        let duplicateWindow: TimeInterval = 10 * 60
        // Goal rows carry the lap time, which can differ by a few seconds between the live track
        // and the server's, so any two goal rows count as the same event.
        let isFinish = turnpointName.hasPrefix(TurnpointPassageRecord.finishPrefix)
        guard !records.contains(where: {
            $0.gliderName == gliderName && ($0.turnpointName == turnpointName || (isFinish && $0.isFinish))
                && abs($0.timestamp.timeIntervalSince(timestamp)) < duplicateWindow
        }) else { return false }
        records.append(TurnpointPassageRecord(gliderName: gliderName, turnpointName: turnpointName, altitudeM: altitudeM, timestamp: timestamp))
        prune()
        save()
        return true
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
