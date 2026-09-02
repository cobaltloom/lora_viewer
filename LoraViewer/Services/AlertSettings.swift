import Foundation
import CoreLocation

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
    /// A list of discrete distance/altitude steps (see `AltitudeStep`).
    case steps
    /// A continuous "final glide" calculation: `arrivalAltitudeM` needed
    /// right at the reference point, plus one more meter of altitude for
    /// every `glideRatio` meters of distance beyond it.
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
    /// Glide ratio (L/D) used in glide-ratio mode: how many meters of
    /// distance one meter of altitude covers.
    @Published var glideRatio: Double { didSet { persist() } }
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
        static let glideRatio = "altGlideRatio"
        static let minFlyingAltM = "altMinimumFlyingAltitudeM"
        // Superseded by `steps`, kept only to migrate existing values once.
        static let legacyDistanceKm = "altDistanceThresholdKm"
        static let legacyMinAltM = "altMinimumAltitudeM"
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
        let storedGlideRatio = d.double(forKey: Keys.glideRatio)
        glideRatio = storedGlideRatio > 0 ? storedGlideRatio : 30

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

    /// The minimum MSL altitude (meters) required at this distance under the
    /// active `mode`, or nil if no restriction applies there (only possible
    /// in `.steps` mode, when closer than every configured step).
    func requiredAltitudeM(atDistanceKm distanceKm: Double) -> Double? {
        switch mode {
        case .steps:
            return steps
                .filter { $0.distanceKm <= distanceKm }
                .max { $0.distanceKm < $1.distanceKm }?
                .minimumAltitudeM
        case .glideRatio:
            guard glideRatio > 0 else { return nil }
            return arrivalAltitudeM + (distanceKm * 1000) / glideRatio
        }
    }

    func isBelowSafeAltitude(_ glider: GliderPosition, defaultReference: CLLocationCoordinate2D?) -> Bool {
        guard isEnabled, let alt = glider.alt, alt > minimumFlyingAltitudeM else { return false }
        guard let reference = referenceCoordinate(default: defaultReference) else { return false }

        let referenceLocation = CLLocation(latitude: reference.latitude, longitude: reference.longitude)
        let gliderLocation = CLLocation(latitude: glider.lat, longitude: glider.lon)
        let distanceKm = referenceLocation.distance(from: gliderLocation) / 1000.0

        guard let required = requiredAltitudeM(atDistanceKm: distanceKm) else { return false }
        return alt < required
    }

    /// Appends a new step continuing the existing pattern (or a sensible
    /// starting point if there are none yet), then keeps the list sorted by
    /// distance so `requiredAltitudeM` and the settings UI stay consistent.
    func addStep() {
        let last = steps.max { $0.distanceKm < $1.distanceKm }
        let newStep = AltitudeStep(
            distanceKm: (last?.distanceKm ?? 2.0) + 1.0,
            minimumAltitudeM: (last?.minimumAltitudeM ?? 230) + 70
        )
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
        d.set(glideRatio, forKey: Keys.glideRatio)
        d.set(minimumFlyingAltitudeM, forKey: Keys.minFlyingAltM)
    }
}
