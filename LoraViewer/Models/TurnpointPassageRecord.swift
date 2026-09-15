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

    init(gliderName: String, turnpointName: String, altitudeM: Double?, timestamp: Date = Date()) {
        self.id = UUID()
        self.gliderName = gliderName
        self.turnpointName = turnpointName
        self.altitudeM = altitudeM
        self.timestamp = timestamp
    }
}
