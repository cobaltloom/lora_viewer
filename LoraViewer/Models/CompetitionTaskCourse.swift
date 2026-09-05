import Foundation
import CoreLocation

/// One of JSAL's published turnpoint courses for 妻沼滑空場 competitions
/// (Ver.2026-01-26): an ordered loop of named turnpoints, starting and
/// ending at the airfield itself.
struct CompetitionTaskCourse: Identifiable {
    let name: String
    let distanceKm: Double
    let turnpointNames: [String]
    var id: String { name }
}

/// The named turnpoints, reference points, and published task courses from
/// JSAL's own document. Like `CompetitionAltitudeGuideline`, this is a
/// fixed, published reference — shown on the map as a visual aid only, not
/// as an authoritative task file (start/goal line orientation in
/// particular is set per-task by the organizers and isn't reproduced here).
enum CompetitionTaskCourseData {
    private static func dms(_ latD: Double, _ latM: Double, _ latS: Double, _ lonD: Double, _ lonM: Double, _ lonS: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude: latD + latM / 60 + latS / 3600,
            longitude: lonD + lonM / 60 + lonS / 3600
        )
    }

    /// Named turnpoints, keyed by name. "妻沼" is the airfield itself —
    /// every course starts and ends there.
    static let turnpoints: [String: CLLocationCoordinate2D] = [
        "妻沼": CompetitionAltitudeGuideline.referenceCoordinate,
        "高林給水塔": dms(36, 14, 51, 139, 22, 7),
        "千代田": dms(36, 12, 26, 139, 29, 13),
        "邑楽タワー": dms(36, 15, 11, 139, 27, 45),
        "管理ポイント": dms(36, 12, 29, 139, 25, 21),
    ]

    /// Order to draw turnpoint markers in (excludes "妻沼", already shown
    /// as the airfield/reference marker elsewhere).
    static let turnpointDisplayOrder = ["高林給水塔", "千代田", "邑楽タワー", "管理ポイント"]

    /// Radius of a turnpoint's sector, per JSAL rule 43 (and the same value
    /// for 管理ポイント's own transit sector): a real sector is a
    /// directional 90° wedge, simplified here to a full circle for both
    /// map visualization and turnpoint-passage detection.
    static let turnpointRadiusKm = 2.0
    static let managementPointRadiusKm = turnpointRadiusKm

    static let courses: [CompetitionTaskCourse] = [
        CompetitionTaskCourse(name: "① 妻沼-高林給水塔-千代田-(管理ポイント)-妻沼", distanceKm: 24.0, turnpointNames: ["妻沼", "高林給水塔", "千代田", "管理ポイント", "妻沼"]),
        CompetitionTaskCourse(name: "② 妻沼-千代田-高林給水塔-妻沼", distanceKm: 23.6, turnpointNames: ["妻沼", "千代田", "高林給水塔", "妻沼"]),
        CompetitionTaskCourse(name: "③ 妻沼-高林給水塔-邑楽タワー-千代田-(管理ポイント)-妻沼", distanceKm: 26.4, turnpointNames: ["妻沼", "高林給水塔", "邑楽タワー", "千代田", "管理ポイント", "妻沼"]),
        CompetitionTaskCourse(name: "④ 妻沼-千代田-邑楽タワー-高林給水塔-妻沼", distanceKm: 26.1, turnpointNames: ["妻沼", "千代田", "邑楽タワー", "高林給水塔", "妻沼"]),
    ]

    /// The goal line: a fixed segment between two published points.
    static let goalLinePointA = dms(36, 12, 47, 139, 24, 57)
    static let goalLinePointB = dms(36, 12, 24, 139, 24, 29)

    /// The start line's center point. Its actual orientation (~perpendicular
    /// to the winch tow path, 300m wide) is set per-task by the organizers,
    /// so only the center point is shown, not a drawn line.
    static let startPoint = dms(36, 12, 48, 139, 24, 59)

    static func coordinates(for course: CompetitionTaskCourse) -> [CLLocationCoordinate2D] {
        course.turnpointNames.compactMap { turnpoints[$0] }
    }

    /// The bisector heading (degrees, 0 = north, clockwise) of the 90°
    /// turnpoint sector at `turnpointName` within `course`, per JSAL rule
    /// 43: the bisector of the incoming leg's direction (from the previous
    /// point to the turnpoint) and the outgoing leg's direction (from the
    /// turnpoint to the next point), each as a ray from the turnpoint.
    /// `nil` if `turnpointName` isn't an interior point of `course` (not
    /// part of it, or it's the course's start/finish point), in which case
    /// no course-specific sector orientation is defined.
    static func sectorBearing(in course: CompetitionTaskCourse, turnpointName: String) -> Double? {
        guard let index = course.turnpointNames.firstIndex(of: turnpointName),
              index > 0, index < course.turnpointNames.count - 1,
              let previous = turnpoints[course.turnpointNames[index - 1]],
              let current = turnpoints[turnpointName],
              let next = turnpoints[course.turnpointNames[index + 1]]
        else { return nil }
        let bearingIn = previous.bearingDegrees(to: current)
        let bearingOut = current.bearingDegrees(to: next)
        return normalizedDegrees(bearingIn + shortestAngleDifference(from: bearingIn, to: bearingOut) / 2)
    }

    /// Whether `bearingDegrees` (measured from the turnpoint) falls within
    /// the 90° sector centered on `bisectorDegrees` (45° either side).
    static func isBearing(_ bearingDegrees: Double, withinSectorCenteredOn bisectorDegrees: Double) -> Bool {
        abs(shortestAngleDifference(from: bisectorDegrees, to: bearingDegrees)) <= 45
    }
}

private func normalizedDegrees(_ degrees: Double) -> Double {
    let mod = degrees.truncatingRemainder(dividingBy: 360)
    return mod < 0 ? mod + 360 : mod
}

private func shortestAngleDifference(from a: Double, to b: Double) -> Double {
    let diff = normalizedDegrees(b - a)
    return diff > 180 ? diff - 360 : diff
}
