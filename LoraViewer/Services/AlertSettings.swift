import Foundation
import CoreLocation

/// How serious an alert is. Ordered so the more severe one wins when a
/// glider triggers more than one rule.
enum AlertSeverity: Int, Comparable {
    case caution = 1
    case warning = 2

    static func < (lhs: AlertSeverity, rhs: AlertSeverity) -> Bool { lhs.rawValue < rhs.rawValue }

    var label: String {
        switch self {
        case .caution: return "注意"
        case .warning: return "警告"
        }
    }
}

/// One step of the "steps" calculation mode: past `distanceKm` from the
/// reference point, at least `minimumAltitudeM` (MSL) is required. Mirrors
/// how `CompetitionAltitudeGuideline`'s official table is structured, but
/// here the steps are whatever the person configuring this decides.
struct AltitudeStep: Identifiable, Codable, Equatable {
    var id = UUID()
    var distanceKm: Double
    var minimumAltitudeM: Double
}

/// How the custom rule turns distance-from-reference-point into a required
/// altitude.
enum AltitudeCalculationMode: String, Codable, Hashable {
    /// A list of discrete distance/altitude steps (see `AltitudeStep`). Only
    /// ever produces `.warning`-level alerts.
    case steps
    /// A continuous "final glide" calculation: `arrivalAltitudeM` needed
    /// right at the reference point, plus one more meter of altitude for
    /// every `glideRatio` meters of distance beyond it — checked at two
    /// glide ratios for a two-stage alert. `warningGlideRatio` should be the
    /// higher (more optimistic, closer to the glider's actual best glide)
    /// of the two: a lower L/D produces a higher required-altitude line, so
    /// it's crossed first and should be the gentler `.caution` stage.
    case glideRatio
}

/// Configurable "minimum altitude beyond a distance" safety rule: gliders
/// have no engine, so past a given distance from the field they need enough
/// altitude (MSL, matching the site's own altitude data) to glide back.
/// `mode` picks which of two ways to compute that required altitude is
/// active; each mode keeps its own settings so switching between them
/// doesn't lose either one's configuration.
///
/// This is an advisory aid only, not a certified instrument — the threshold
/// values are whatever the person configuring this decides are safe.
final class AlertSettings: ObservableObject {
    @Published var isEnabled: Bool { didSet { persist() } }
    @Published var mode: AltitudeCalculationMode { didSet { persist() } }
    @Published var useCustomReference: Bool { didSet { persist() } }
    @Published var customLatitude: Double { didSet { persist() } }
    @Published var customLongitude: Double { didSet { persist() } }
    @Published var steps: [AltitudeStep] { didSet { persist() } }
    /// Required MSL altitude right at the reference point, in glide-ratio mode.
    @Published var arrivalAltitudeM: Double { didSet { persist() } }
    /// The more conservative (lower) of the two glide ratios, in glide-ratio
    /// mode — crossed first, so it drives the `.caution` stage.
    @Published var cautionGlideRatio: Double { didSet { persist() } }
    /// The more optimistic (higher) of the two glide ratios, in glide-ratio
    /// mode — crossed only once things are genuinely critical, so it drives
    /// the `.warning` stage.
    @Published var warningGlideRatio: Double { didSet { persist() } }
    /// Altitude (MSL) at or below which a position is treated as "on the
    /// ground, not actually flying" and never alerted on, regardless of
    /// distance — otherwise a glider parked at the field or landed out
    /// somewhere would sit in permanent "low altitude" alert. Shared by both
    /// this rule and `CompetitionAltitudeGuideline` since it's about
    /// distinguishing flying from not, not about either rule's own numbers.
    @Published var minimumFlyingAltitudeM: Double { didSet { persist() } }

    private enum Keys {
        static let isEnabled = "altIsEnabled"
        static let mode = "altMode"
        static let useCustomReference = "altUseCustomReference"
        static let customLat = "altCustomLat"
        static let customLon = "altCustomLon"
        static let steps = "altSteps"
        static let arrivalAltM = "altArrivalAltitudeM"
        static let cautionGlideRatio = "altCautionGlideRatio"
        static let warningGlideRatio = "altWarningGlideRatio"
        static let minFlyingAltM = "altMinimumFlyingAltitudeM"
        // Superseded by `steps`, kept only to migrate existing values once.
        static let legacyDistanceKm = "altDistanceThresholdKm"
        static let legacyMinAltM = "altMinimumAltitudeM"
        // Superseded by cautionGlideRatio/warningGlideRatio, migrated once.
        static let legacyGlideRatio = "altGlideRatio"
    }

    init() {
        let d = UserDefaults.standard
        isEnabled = d.bool(forKey: Keys.isEnabled)
        mode = AltitudeCalculationMode(rawValue: d.string(forKey: Keys.mode) ?? "") ?? .steps
        useCustomReference = d.bool(forKey: Keys.useCustomReference)
        customLatitude = d.double(forKey: Keys.customLat)
        customLongitude = d.double(forKey: Keys.customLon)

        if let data = d.data(forKey: Keys.steps),
           let decoded = try? JSONDecoder().decode([AltitudeStep].self, from: data) {
            steps = decoded
        } else {
            let legacyDistance = d.double(forKey: Keys.legacyDistanceKm)
            let legacyAltitude = d.double(forKey: Keys.legacyMinAltM)
            if legacyDistance > 0, legacyAltitude > 0 {
                // Carry over a single-threshold setup from before this was a list.
                steps = [AltitudeStep(distanceKm: legacyDistance, minimumAltitudeM: legacyAltitude)]
            } else {
                steps = [AltitudeStep(distanceKm: 3.0, minimumAltitudeM: 300)]
            }
        }

        let storedArrivalAlt = d.double(forKey: Keys.arrivalAltM)
        arrivalAltitudeM = storedArrivalAlt > 0 ? storedArrivalAlt : 300

        let storedWarningRatio = d.double(forKey: Keys.warningGlideRatio)
        let storedCautionRatio = d.double(forKey: Keys.cautionGlideRatio)
        if storedWarningRatio > 0, storedCautionRatio > 0 {
            warningGlideRatio = storedWarningRatio
            cautionGlideRatio = storedCautionRatio
        } else {
            // Migrate the old single glide ratio into the warning stage, and
            // derive a slightly more conservative one for the caution stage.
            let legacyRatio = d.double(forKey: Keys.legacyGlideRatio)
            let baseRatio = legacyRatio > 0 ? legacyRatio : 30
            warningGlideRatio = baseRatio
            cautionGlideRatio = max(baseRatio - 10, 5)
        }

        let storedFlyingAlt = d.double(forKey: Keys.minFlyingAltM)
        minimumFlyingAltitudeM = storedFlyingAlt > 0 ? storedFlyingAlt : 60
    }

    /// The point distance is measured from: the custom point if the user set
    /// one, otherwise the site's own configured map center (normally the
    /// airfield itself).
    func referenceCoordinate(default defaultCoordinate: CLLocationCoordinate2D?) -> CLLocationCoordinate2D? {
        if useCustomReference {
            return CLLocationCoordinate2D(latitude: customLatitude, longitude: customLongitude)
        }
        return defaultCoordinate
    }

    /// The minimum MSL altitude (meters) required at this distance in
    /// `.steps` mode, or the required altitude at the given glide ratio in
    /// `.glideRatio` mode. Returns nil only in `.steps` mode, when closer
    /// than every configured step (no restriction applies).
    func requiredAltitudeM(atDistanceKm distanceKm: Double, glideRatio: Double? = nil) -> Double? {
        switch mode {
        case .steps:
            return steps
                .filter { $0.distanceKm <= distanceKm }
                .max { $0.distanceKm < $1.distanceKm }?
                .minimumAltitudeM
        case .glideRatio:
            let ratio = glideRatio ?? warningGlideRatio
            guard ratio > 0 else { return nil }
            return arrivalAltitudeM + (distanceKm * 1000) / ratio
        }
    }

    /// The severity of this rule's alert for the glider, or nil if it
    /// doesn't apply. `.steps` mode is a single threshold, always
    /// `.warning` when triggered; `.glideRatio` mode checks both glide
    /// ratios and returns the more severe one that's crossed.
    func alertSeverity(for glider: GliderPosition, defaultReference: CLLocationCoordinate2D?) -> AlertSeverity? {
        guard isEnabled, let alt = glider.alt, alt > minimumFlyingAltitudeM else { return nil }
        guard let reference = referenceCoordinate(default: defaultReference) else { return nil }

        let referenceLocation = CLLocation(latitude: reference.latitude, longitude: reference.longitude)
        let gliderLocation = CLLocation(latitude: glider.lat, longitude: glider.lon)
        let distanceKm = referenceLocation.distance(from: gliderLocation) / 1000.0

        switch mode {
        case .steps:
            guard let required = requiredAltitudeM(atDistanceKm: distanceKm) else { return nil }
            return alt < required ? .warning : nil
        case .glideRatio:
            if let warningRequired = requiredAltitudeM(atDistanceKm: distanceKm, glideRatio: warningGlideRatio), alt < warningRequired {
                return .warning
            }
            if let cautionRequired = requiredAltitudeM(atDistanceKm: distanceKm, glideRatio: cautionGlideRatio), alt < cautionRequired {
                return .caution
            }
            return nil
        }
    }

    /// Appends a new step continuing the existing pattern (or a sensible
    /// starting point if there are none yet), then keeps the list sorted by
    /// distance so `requiredAltitudeM` and the settings UI stay consistent.
    func addStep() {
        guard let last = steps.max(by: { $0.distanceKm < $1.distanceKm }) else {
            // Nothing yet — start at 3km/350m, then grow the same way the
            // competition guideline does after its own first bracket.
            steps.append(AltitudeStep(distanceKm: 3.0, minimumAltitudeM: 350))
            return
        }
        // Keep growing the same way the guideline does after that: +1km, +70m.
        let newStep = AltitudeStep(distanceKm: last.distanceKm + 1.0, minimumAltitudeM: last.minimumAltitudeM + 70)
        steps.append(newStep)
        steps.sort { $0.distanceKm < $1.distanceKm }
    }

    private func persist() {
        let d = UserDefaults.standard
        d.set(isEnabled, forKey: Keys.isEnabled)
        d.set(mode.rawValue, forKey: Keys.mode)
        d.set(useCustomReference, forKey: Keys.useCustomReference)
        d.set(customLatitude, forKey: Keys.customLat)
        d.set(customLongitude, forKey: Keys.customLon)
        if let data = try? JSONEncoder().encode(steps) {
            d.set(data, forKey: Keys.steps)
        }
        d.set(arrivalAltitudeM, forKey: Keys.arrivalAltM)
        d.set(cautionGlideRatio, forKey: Keys.cautionGlideRatio)
        d.set(warningGlideRatio, forKey: Keys.warningGlideRatio)
        d.set(minimumFlyingAltitudeM, forKey: Keys.minFlyingAltM)
    }
}
