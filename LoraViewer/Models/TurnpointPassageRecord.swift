import Foundation

/// One recorded instance of a glider entering a competition turnpoint's
/// sector (see `AlertNotifier.notifyTurnpointPassage`), kept so it can be
/// reviewed in-app even if the push notification was missed.
struct TurnpointPassageRecord: Identifiable, Codable {
    let id: UUID
    let gliderName: String
    let turnpointName: String
    let altitudeM: Double?
    let timestamp: Date
    /// For goal and partial-lap rows: the glider's id (LoRa IMEI / OGN callsign), lap time (goal
    /// rows only) and the distance that scores - the course's for a lap, the 細則2 table's for a
    /// partly flown course - so the history can give each a reference score (see `LapScore`).
    /// Absent in records saved before scoring existed, and in other rows.
    var gliderID: String?
    var lapSeconds: Double?
    var courseDistanceKm: Double?
    /// The task course the row was judged against (sector orientation, lap): the history shows the
    /// selected course's rows, so picking another course later - e.g. once it's known which one was
    /// flown - shows that course's passages and laps instead. Absent in rows saved before this.
    var courseName: String?

    /// A completed lap, logged as "ゴール 所要…・平均…" (see `CurrentMapView.updateLapStatuses`).
    var isFinish: Bool { turnpointName.hasPrefix(Self.finishPrefix) }
    static let finishPrefix = "ゴール "

    /// A flight that landed after rounding some of the course's turnpoints but not finishing,
    /// logged as "周回未達成 旋回点 2/3(…)" (競技規則56(1): distance points only).
    var isPartialLap: Bool { turnpointName.hasPrefix(Self.partialLapPrefix) }
    static let partialLapPrefix = "周回未達成 "

    /// A goal or partial-lap row: one that can score.
    var isScoredFlight: Bool { isFinish || isPartialLap }

    init(gliderName: String, turnpointName: String, altitudeM: Double?, timestamp: Date = Date(),
         gliderID: String? = nil, lapSeconds: Double? = nil, courseDistanceKm: Double? = nil, courseName: String? = nil) {
        self.id = UUID()
        self.gliderName = gliderName
        self.turnpointName = turnpointName
        self.altitudeM = altitudeM
        self.timestamp = timestamp
        self.gliderID = gliderID
        self.lapSeconds = lapSeconds
        self.courseDistanceKm = courseDistanceKm
        self.courseName = courseName
    }

    /// Whether this row belongs to `courseName`'s history: rows from before courses were recorded
    /// count for every course.
    func belongs(toCourse courseName: String?) -> Bool {
        courseName == nil || self.courseName == nil || self.courseName == courseName
    }
}
