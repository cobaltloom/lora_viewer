import Foundation
import CoreLocation

/// The official JSAL 妻沼滑空場 competition altitude guideline (Ver.
/// 2026-01-26): a minimum MSL altitude required beyond a given distance from
/// the field center, stepping up as distance grows. Unlike `AlertSettings`
/// (the user's own configurable distance/altitude rule), this is a fixed,
/// published table — it can only be turned on or off, not edited, so it
/// stays correct as long as it matches the source document. Update the
/// table and reference coordinate here if JSAL publishes a new version.
final class CompetitionAltitudeGuideline: ObservableObject {
    @Published var isEnabled: Bool {
        didSet { UserDefaults.standard.set(isEnabled, forKey: Self.storageKey) }
    }
    /// Whether to draw the published turnpoints and a selected task course
    /// on the map — a separate toggle from the altitude guideline itself,
    /// since someone might want one without the other.
    @Published var showTaskCourse: Bool {
        didSet { UserDefaults.standard.set(showTaskCourse, forKey: Self.showTaskCourseKey) }
    }
    /// Index into `CompetitionTaskCourseData.courses`, or nil to show just
    /// the turnpoints without connecting them into a course line.
    @Published var selectedCourseIndex: Int? {
        didSet {
            if let selectedCourseIndex {
                UserDefaults.standard.set(selectedCourseIndex, forKey: Self.selectedCourseIndexKey)
            } else {
                UserDefaults.standard.removeObject(forKey: Self.selectedCourseIndexKey)
            }
        }
    }

    private static let storageKey = "competitionGuidelineEnabled"
    private static let showTaskCourseKey = "competitionShowTaskCourse"
    private static let selectedCourseIndexKey = "competitionSelectedCourseIndex"

    /// 妻沼滑空場中心: N36°12'41", E139°25'08" per the guideline document.
    static let referenceCoordinate = CLLocationCoordinate2D(
        latitude: 36 + 12.0 / 60 + 41.0 / 3600,
        longitude: 139 + 25.0 / 60 + 8.0 / 3600
    )

    /// Below this distance the guideline sets no minimum altitude at all.
    static let innerRadiusKm = 2.5

    /// Every distance the table's required altitude changes at, including
    /// the inner "no restriction" radius — drawn as rings on the map so the
    /// guideline's zones are visible at a glance, not just its innermost one.
    static let boundaryDistancesKm: [Double] = [2.5, 3, 4, 5, 6, 7, 8, 9, 10]

    init() {
        isEnabled = UserDefaults.standard.bool(forKey: Self.storageKey)
        showTaskCourse = UserDefaults.standard.bool(forKey: Self.showTaskCourseKey)
        if UserDefaults.standard.object(forKey: Self.selectedCourseIndexKey) != nil {
            selectedCourseIndex = UserDefaults.standard.integer(forKey: Self.selectedCourseIndexKey)
        } else {
            selectedCourseIndex = nil
        }
    }

    /// The minimum MSL altitude (meters) required at this distance, or nil
    /// if this distance is close enough to the field that no minimum
    /// applies.
    ///
    ///   2.5-3km: 350m, then +70m per additional km, capped at 910m (10km+)
    static func requiredAltitudeM(atDistanceKm distanceKm: Double) -> Double? {
        guard distanceKm >= innerRadiusKm else { return nil }
        if distanceKm < 3 { return 350 }
        if distanceKm >= 10 { return 910 }
        let bracket = Int(distanceKm.rounded(.down)) // 3...9
        return 420 + 70 * Double(bracket - 3)
    }

    /// `minimumFlyingAltitudeM` comes from `AlertSettings` — a position at or
    /// below it is treated as on the ground (parked, or landed out), never
    /// alerted on regardless of distance, so a stationary glider doesn't sit
    /// in permanent "low altitude" alert.
    func isBelowGuideline(_ glider: GliderPosition, minimumFlyingAltitudeM: Double) -> Bool {
        guard isEnabled, let alt = glider.alt, alt > minimumFlyingAltitudeM else { return false }

        let referenceLocation = CLLocation(latitude: Self.referenceCoordinate.latitude, longitude: Self.referenceCoordinate.longitude)
        let gliderLocation = CLLocation(latitude: glider.lat, longitude: glider.lon)
        let distanceKm = referenceLocation.distance(from: gliderLocation) / 1000.0

        guard let required = Self.requiredAltitudeM(atDistanceKm: distanceKm) else { return false }
        return alt < required
    }
}
