import Foundation

/// Configurable "gliders getting close to each other" safety aid. Two
/// severities, mirroring `AlertSettings`: `.caution` is a quiet map-only
/// indicator (just "nearby, watch out"), while `.warning` — which also
/// pushes a notification — additionally requires the pair to be actively
/// closing distance, not just near each other, since gliders sharing a
/// thermal are commonly close together without being on a collision
/// course. Alerting fatigue on the noisy channel (notifications) was the
/// specific concern this guards against; the quiet channel (the map ring)
/// doesn't need that same restraint.
///
/// Near the field, in the landing pattern, gliders are routinely close
/// together and converging by design (following each other around the
/// circuit) — `patternExclusionRadiusKm`/`patternExclusionCeilingM` cap a
/// pair at `.caution` there, never escalating to a notification, since
/// that would fire on essentially every landing.
///
/// This is an advisory aid only, not a collision-avoidance system: position
/// data comes from periodic polling (several seconds apart at best), not
/// continuous real-time GPS.
final class ProximityAlertSettings: ObservableObject {
    @Published var isEnabled: Bool { didSet { persist() } }
    /// Horizontal distance (meters) at/below which two gliders are flagged
    /// as "nearby" on the map — no closing-trend requirement.
    @Published var cautionDistanceM: Double { didSet { persist() } }
    /// Horizontal distance (meters) at/below which — if also closing and
    /// within `maxAltitudeDifferenceM` of each other — a push notification
    /// fires.
    @Published var warningDistanceM: Double { didSet { persist() } }
    /// Vertical separation (meters) beyond which two gliders are never
    /// considered a proximity risk, regardless of horizontal distance.
    @Published var maxAltitudeDifferenceM: Double { didSet { persist() } }
    /// Distance (km) from the alert reference point within which both
    /// gliders must be for the pattern exclusion to apply.
    @Published var patternExclusionRadiusKm: Double { didSet { persist() } }
    /// Altitude (MSL, matching the site's own altitude data) at/below which
    /// both gliders must be for the pattern exclusion to apply.
    @Published var patternExclusionCeilingM: Double { didSet { persist() } }

    private enum Keys {
        static let isEnabled = "proximityIsEnabled"
        static let cautionDistanceM = "proximityCautionDistanceM"
        static let warningDistanceM = "proximityWarningDistanceM"
        static let maxAltitudeDifferenceM = "proximityMaxAltitudeDifferenceM"
        static let patternExclusionRadiusKm = "proximityPatternExclusionRadiusKm"
        static let patternExclusionCeilingM = "proximityPatternExclusionCeilingM"
    }

    init() {
        let d = UserDefaults.standard
        isEnabled = d.bool(forKey: Keys.isEnabled)

        let storedCaution = d.double(forKey: Keys.cautionDistanceM)
        cautionDistanceM = storedCaution > 0 ? storedCaution : 500

        let storedWarning = d.double(forKey: Keys.warningDistanceM)
        warningDistanceM = storedWarning > 0 ? storedWarning : 150

        let storedMaxAltDiff = d.double(forKey: Keys.maxAltitudeDifferenceM)
        maxAltitudeDifferenceM = storedMaxAltDiff > 0 ? storedMaxAltDiff : 150

        let storedPatternRadius = d.double(forKey: Keys.patternExclusionRadiusKm)
        patternExclusionRadiusKm = storedPatternRadius > 0 ? storedPatternRadius : 1.5

        let storedPatternCeiling = d.double(forKey: Keys.patternExclusionCeilingM)
        patternExclusionCeilingM = storedPatternCeiling > 0 ? storedPatternCeiling : 280
    }

    private func persist() {
        let d = UserDefaults.standard
        d.set(isEnabled, forKey: Keys.isEnabled)
        d.set(cautionDistanceM, forKey: Keys.cautionDistanceM)
        d.set(warningDistanceM, forKey: Keys.warningDistanceM)
        d.set(maxAltitudeDifferenceM, forKey: Keys.maxAltitudeDifferenceM)
        d.set(patternExclusionRadiusKm, forKey: Keys.patternExclusionRadiusKm)
        d.set(patternExclusionCeilingM, forKey: Keys.patternExclusionCeilingM)
    }
}
