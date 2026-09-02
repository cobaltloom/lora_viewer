import Foundation
import CoreLocation

/// A polygon airspace zone, as published by JSAL for 妻沼滑空場: an ordered
/// list of vertices connected by straight lines back to the first one.
struct AirspaceZone {
    let name: String
    let boundary: [CLLocationCoordinate2D]

    /// Standard ray-casting point-in-polygon test. Treats lat/lon as plane
    /// coordinates, which is accurate enough at this scale (tens of km).
    func contains(_ coordinate: CLLocationCoordinate2D) -> Bool {
        var isInside = false
        var j = boundary.count - 1
        for i in 0..<boundary.count {
            let vertexI = boundary[i]
            let vertexJ = boundary[j]
            let straddles = (vertexI.latitude > coordinate.latitude) != (vertexJ.latitude > coordinate.latitude)
            if straddles {
                let longitudeAtLatitude = vertexI.longitude
                    + (coordinate.latitude - vertexI.latitude) / (vertexJ.latitude - vertexI.latitude)
                    * (vertexJ.longitude - vertexI.longitude)
                if coordinate.longitude < longitudeAtLatitude {
                    isInside.toggle()
                }
            }
            j = i
        }
        return isInside
    }
}

/// How B区域's altitude ceiling for "today" is decided.
enum UpperCeilingMode: String, Codable {
    /// Weekday vs. weekend, per JSAL's standard rule. `treatTodayAsHoliday`
    /// covers national holidays that fall on a weekday, since Foundation
    /// has no built-in Japanese holiday calendar.
    case auto
    /// A competition (or other special arrangement) is in effect, with its
    /// own granted ceiling entered directly.
    case competition
}

/// The official JSAL 妻沼滑空場 upper altitude limits: A区域 and B区域, two
/// overlapping polygons around the field, each with its own MSL ceiling.
/// B区域 is the inner, more restrictive one — its ceiling also depends on
/// the day (and can be overridden for a competition's own granted limit).
/// Like `CompetitionAltitudeGuideline`, the zone boundaries and A区域's
/// ceiling are fixed, published values; only B区域's day-dependent ceiling
/// is user-set, since Foundation can't determine that on its own.
final class UpperAltitudeGuideline: ObservableObject {
    @Published var isEnabled: Bool { didSet { persist() } }
    @Published var mode: UpperCeilingMode { didSet { persist() } }
    @Published var treatTodayAsHoliday: Bool { didSet { persist() } }
    @Published var competitionCeilingFt: Double { didSet { persist() } }

    private enum Keys {
        static let isEnabled = "upperAltIsEnabled"
        static let mode = "upperAltMode"
        static let treatTodayAsHoliday = "upperAltTreatTodayAsHoliday"
        static let competitionCeilingFt = "upperAltCompetitionCeilingFt"
    }

    init() {
        let d = UserDefaults.standard
        isEnabled = d.bool(forKey: Keys.isEnabled)
        mode = UpperCeilingMode(rawValue: d.string(forKey: Keys.mode) ?? "") ?? .auto
        treatTodayAsHoliday = d.bool(forKey: Keys.treatTodayAsHoliday)
        let storedCeiling = d.double(forKey: Keys.competitionCeilingFt)
        competitionCeilingFt = storedCeiling > 0 ? storedCeiling : 4500
    }

    private func persist() {
        let d = UserDefaults.standard
        d.set(isEnabled, forKey: Keys.isEnabled)
        d.set(mode.rawValue, forKey: Keys.mode)
        d.set(treatTodayAsHoliday, forKey: Keys.treatTodayAsHoliday)
        d.set(competitionCeilingFt, forKey: Keys.competitionCeilingFt)
    }

    private static func dms(_ latD: Double, _ latM: Double, _ lonD: Double, _ lonM: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latD + latM / 60, longitude: lonD + lonM / 60)
    }

    /// A区域: the outer polygon, ceiling always 4,500ft MSL.
    static let zoneA = AirspaceZone(name: "A区域", boundary: [
        dms(36, 12 + 35.0 / 60, 139, 22 + 45.0 / 60),
        dms(36, 14 + 10.0 / 60, 139, 28 + 23.0 / 60),
        dms(36, 13 + 14.0 / 60, 139, 34 + 46.0 / 60),
        dms(36, 15 + 18.0 / 60, 139, 37 + 30.0 / 60),
        dms(36, 17 + 6.0 / 60, 139, 36 + 43.0 / 60),
        dms(36, 21 + 11.0 / 60, 139, 26 + 48.0 / 60),
        dms(36, 16 + 11.0 / 60, 139, 18 + 48.0 / 60)
    ])

    static let zoneACeilingFt = 4500.0

    /// B区域: the inner polygon (shares its first three vertices with
    /// A区域), ceiling depends on the day — see `bZoneCeilingFt`.
    static let zoneB = AirspaceZone(name: "B区域", boundary: [
        dms(36, 12 + 35.0 / 60, 139, 22 + 45.0 / 60),
        dms(36, 14 + 10.0 / 60, 139, 28 + 23.0 / 60),
        dms(36, 13 + 14.0 / 60, 139, 34 + 46.0 / 60),
        dms(36, 10 + 40.0 / 60, 139, 31 + 15.0 / 60),
        dms(36, 10 + 37.0 / 60, 139, 25 + 0.0 / 60)
    ])

    static let zoneBWeekdayCeilingFt = 2500.0
    static let zoneBWeekendCeilingFt = 3500.0

    private static let feetToMeters = 0.3048

    /// B区域's ceiling (ft) for today, given the current mode/settings.
    var bZoneCeilingFt: Double {
        switch mode {
        case .competition:
            return competitionCeilingFt
        case .auto:
            let weekday = Calendar(identifier: .gregorian).component(.weekday, from: Date())
            let isWeekend = weekday == 1 || weekday == 7 // Sunday / Saturday
            return (isWeekend || treatTodayAsHoliday) ? Self.zoneBWeekendCeilingFt : Self.zoneBWeekdayCeilingFt
        }
    }

    /// True if `glider` is above the ceiling for whichever zone (B takes
    /// priority as the more restrictive, inner one) it's currently inside.
    /// Outside both zones, there's no published limit, so this never fires.
    func exceedsCeiling(_ glider: GliderPosition) -> Bool {
        guard isEnabled, let altM = glider.alt else { return false }
        let coordinate = glider.coordinate

        if Self.zoneB.contains(coordinate) {
            return altM > bZoneCeilingFt * Self.feetToMeters
        }
        if Self.zoneA.contains(coordinate) {
            return altM > Self.zoneACeilingFt * Self.feetToMeters
        }
        return false
    }
}
