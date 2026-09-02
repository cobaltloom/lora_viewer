import Foundation
import CoreLocation

/// Configurable "minimum altitude beyond a distance" safety rule: gliders
/// have no engine, so past a certain distance from the field they need
/// enough altitude (MSL, matching the site's own altitude data) to glide
/// back. This flags any current position more than `distanceThresholdKm`
/// from the reference point and below `minimumAltitudeM`.
///
/// This is an advisory aid only, not a certified instrument — the threshold
/// values are whatever the person configuring this decides are safe.
final class AlertSettings: ObservableObject {
    @Published var isEnabled: Bool { didSet { persist() } }
    @Published var useCustomReference: Bool { didSet { persist() } }
    @Published var customLatitude: Double { didSet { persist() } }
    @Published var customLongitude: Double { didSet { persist() } }
    @Published var distanceThresholdKm: Double { didSet { persist() } }
    @Published var minimumAltitudeM: Double { didSet { persist() } }
    /// Altitude (MSL) at or below which a position is treated as "on the
    /// ground, not actually flying" and never alerted on, regardless of
    /// distance — otherwise a glider parked at the field or landed out
    /// somewhere would sit in permanent "low altitude" alert. Shared by both
    /// this rule and `CompetitionAltitudeGuideline` since it's about
    /// distinguishing flying from not, not about either rule's own numbers.
    @Published var minimumFlyingAltitudeM: Double { didSet { persist() } }

    private enum Keys {
        static let isEnabled = "altIsEnabled"
        static let useCustomReference = "altUseCustomReference"
        static let customLat = "altCustomLat"
        static let customLon = "altCustomLon"
        static let distanceKm = "altDistanceThresholdKm"
        static let minAltM = "altMinimumAltitudeM"
        static let minFlyingAltM = "altMinimumFlyingAltitudeM"
    }

    init() {
        let d = UserDefaults.standard
        isEnabled = d.bool(forKey: Keys.isEnabled)
        useCustomReference = d.bool(forKey: Keys.useCustomReference)
        customLatitude = d.double(forKey: Keys.customLat)
        customLongitude = d.double(forKey: Keys.customLon)
        let storedDistance = d.double(forKey: Keys.distanceKm)
        distanceThresholdKm = storedDistance > 0 ? storedDistance : 3.0
        let storedAlt = d.double(forKey: Keys.minAltM)
        minimumAltitudeM = storedAlt > 0 ? storedAlt : 300
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

    func isBelowSafeAltitude(_ glider: GliderPosition, defaultReference: CLLocationCoordinate2D?) -> Bool {
        guard isEnabled, let alt = glider.alt, alt > minimumFlyingAltitudeM else { return false }
        guard let reference = referenceCoordinate(default: defaultReference) else { return false }

        let referenceLocation = CLLocation(latitude: reference.latitude, longitude: reference.longitude)
        let gliderLocation = CLLocation(latitude: glider.lat, longitude: glider.lon)
        let distanceKm = referenceLocation.distance(from: gliderLocation) / 1000.0

        guard distanceKm > distanceThresholdKm else { return false }
        return alt < minimumAltitudeM
    }

    private func persist() {
        let d = UserDefaults.standard
        d.set(isEnabled, forKey: Keys.isEnabled)
        d.set(useCustomReference, forKey: Keys.useCustomReference)
        d.set(customLatitude, forKey: Keys.customLat)
        d.set(customLongitude, forKey: Keys.customLon)
        d.set(distanceThresholdKm, forKey: Keys.distanceKm)
        d.set(minimumAltitudeM, forKey: Keys.minAltM)
        d.set(minimumFlyingAltitudeM, forKey: Keys.minFlyingAltM)
    }
}
