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
    func recordPastPassage(gliderName: String, turnpointName: String, altitudeM: Double?, timestamp: Date,
                           gliderID: String? = nil, lapSeconds: Double? = nil, courseDistanceKm: Double? = nil,
                           courseName: String? = nil) -> Bool {
        let duplicateWindow: TimeInterval = 10 * 60
        // Goal rows carry the lap time, which can differ by a few seconds between the live track
        // and the server's, so any two goal rows count as the same event; likewise partial-lap rows.
        let isFinish = turnpointName.hasPrefix(TurnpointPassageRecord.finishPrefix)
        let isPartialLap = turnpointName.hasPrefix(TurnpointPassageRecord.partialLapPrefix)
        // Rows of another course are another history (see `TurnpointPassageRecord.courseName`); a row
        // from before courses were recorded is taken as this course's.
        if let index = records.firstIndex(where: {
            $0.gliderName == gliderName
                && ($0.courseName == courseName || $0.courseName == nil)
                && ($0.turnpointName == turnpointName || (isFinish && $0.isFinish) || (isPartialLap && $0.isPartialLap))
                && abs($0.timestamp.timeIntervalSince(timestamp)) < duplicateWindow
        }) {
            var changed = false
            if records[index].courseName == nil, courseName != nil {
                records[index].courseName = courseName
                changed = true
            }
            // A goal row saved before lap scoring gets its lap time now, so it can be scored.
            if records[index].lapSeconds == nil, let lapSeconds {
                records[index].gliderID = gliderID
                records[index].lapSeconds = lapSeconds
                records[index].courseDistanceKm = courseDistanceKm
                changed = true
            }
            if changed { save() }
            return false
        }
        records.append(TurnpointPassageRecord(gliderName: gliderName, turnpointName: turnpointName, altitudeM: altitudeM, timestamp: timestamp,
                                              gliderID: gliderID, lapSeconds: lapSeconds, courseDistanceKm: courseDistanceKm,
                                              courseName: courseName))
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
