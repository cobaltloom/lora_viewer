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
    /// For goal rows: the glider's id (LoRa IMEI / OGN callsign), lap time and course distance, so
    /// the history can give each completed lap a reference score (see `LapScore`). Absent in
    /// records saved before scoring existed, and in other rows.
    var gliderID: String?
    var lapSeconds: Double?
    var courseDistanceKm: Double?

    /// A completed lap, logged as "ゴール 所要…・平均…" (see `CurrentMapView.updateLapStatuses`).
    var isFinish: Bool { turnpointName.hasPrefix(Self.finishPrefix) }
    static let finishPrefix = "ゴール "

    init(gliderName: String, turnpointName: String, altitudeM: Double?, timestamp: Date = Date(),
         gliderID: String? = nil, lapSeconds: Double? = nil, courseDistanceKm: Double? = nil) {
        self.id = UUID()
        self.gliderName = gliderName
        self.turnpointName = turnpointName
        self.altitudeM = altitudeM
        self.timestamp = timestamp
        self.gliderID = gliderID
        self.lapSeconds = lapSeconds
        self.courseDistanceKm = courseDistanceKm
    }
}
