import Foundation
import CoreLocation

/// One timed position of a glider's current flight, used to time its lap.
struct TimedPoint {
    let time: Date
    let coordinate: CLLocationCoordinate2D
    let altitudeM: Double
}

/// How far a glider has got around the selected course, per the 関東学生グライダー競技会 rules
/// (競技規則 Ver.3-2026-09-28 35-38, 52; 細則 Ver.2026-09-28 2-3). A reference only: official
/// timing uses each glider's 1-second IGC logger in SeeYou, while this works from LoRa/OGN fixes
/// a few seconds apart. Kept in step with the Android app's TaskLap.kt, which has the unit tests.
struct TaskLapStatus {
    /// When the glider crossed the start line, or nil if it hasn't yet.
    let start: Date?
    let startAltitudeM: Double?
    /// Turnpoints reached in course order, with the time each was reached.
    let passedTurnpoints: [(name: String, time: Date)]
    let totalTurnpoints: Int
    /// When it crossed the goal line after every turnpoint, or nil if it hasn't.
    let finish: Date?
    let finishAltitudeM: Double?

    var lapDuration: TimeInterval? {
        guard let start, let finish else { return nil }
        return finish.timeIntervalSince(start)
    }

    func speedKmh(courseDistanceKm: Double) -> Double? {
        guard let lapDuration, lapDuration > 0 else { return nil }
        return courseDistanceKm / (lapDuration / 3600)
    }

    /// 規則50(5): the goal line must be crossed at 330 m MSL or above.
    var isGoalTooLow: Bool {
        finishAltitudeM.map { $0 < TaskLapCalculator.minGoalAltitudeM } ?? false
    }

    /// One line for the detail card, e.g. "周回: 所要 29分25秒・平均 48.1km/h(参考)".
    func summary(courseDistanceKm: Double, now: Date) -> String {
        guard let start else { return "周回: スタート前" }
        if let lapDuration {
            let speed = speedKmh(courseDistanceKm: courseDistanceKm).map { String(format: "・平均 %.1fkm/h", $0) } ?? ""
            let low = isGoalTooLow ? "・ゴール高度 \(Int(finishAltitudeM ?? 0))m(330m未満)" : ""
            return "周回: 所要 \(Self.format(lapDuration))\(speed)\(low)(参考)"
        }
        let startText = start.formatted(date: .omitted, time: .standard)
        let elapsedMinutes = Int(now.timeIntervalSince(start) / 60)
        return "周回: スタート \(startText)・旋回点 \(passedTurnpoints.count)/\(totalTurnpoints)・経過 \(elapsedMinutes)分(参考)"
    }

    /// Short text for the badge under the map marker: "旋回 1/2" while on course, "ゴール 29:25"
    /// once finished, nil before the start.
    var markerBadge: String? {
        guard start != nil else { return nil }
        if let lapDuration {
            let seconds = Int(lapDuration)
            return String(format: "ゴール %d:%02d", seconds / 60, seconds % 60)
        }
        return "旋回 \(passedTurnpoints.count)/\(totalTurnpoints)"
    }

    /// "29分27秒"
    static func format(_ duration: TimeInterval) -> String {
        let seconds = Int(duration)
        return "\(seconds / 60)分\(seconds % 60)秒"
    }
}

enum TaskLapCalculator {
    /// 規則50(5): goal line crossing altitude (MSL).
    static let minGoalAltitudeM = 330.0
    /// 細則3(2): the start line is 300 m wide, centered on the ST point.
    private static let startLineHalfWidthM = 150.0

    /// Times `points` (one flight, oldest first) around `course`: the start line crossing, then each
    /// interior turnpoint in order - inside its 90° / 2 km sector and not below the safety guideline
    /// altitude there (rule 37(1)②) - then the first goal line crossing after the last turnpoint.
    static func evaluate(_ points: [TimedPoint], course: CompetitionTaskCourse) -> TaskLapStatus {
        let turnpoints = Array(course.turnpointNames.dropFirst().dropLast())
        guard let start = firstCrossing(points, from: 0, a: startLine.a, b: startLine.b) else {
            return TaskLapStatus(start: nil, startAltitudeM: nil, passedTurnpoints: [], totalTurnpoints: turnpoints.count, finish: nil, finishAltitudeM: nil)
        }
        var passed: [(name: String, time: Date)] = []
        var index = start.afterIndex
        while index < points.count, passed.count < turnpoints.count {
            let p = points[index]
            let name = turnpoints[passed.count]
            if isInSector(p, course: course, name: name), p.altitudeM >= (requiredAltitudeM(at: p) ?? -.infinity) {
                passed.append((name, p.time))
            }
            index += 1
        }
        guard passed.count == turnpoints.count else {
            return TaskLapStatus(start: start.time, startAltitudeM: start.altitudeM, passedTurnpoints: passed, totalTurnpoints: turnpoints.count, finish: nil, finishAltitudeM: nil)
        }
        let finish = firstCrossing(points, from: index - 1, a: goalLine.a, b: goalLine.b)
        return TaskLapStatus(start: start.time, startAltitudeM: start.altitudeM, passedTurnpoints: passed, totalTurnpoints: turnpoints.count,
                             finish: finish?.time, finishAltitudeM: finish?.altitudeM)
    }

    private static func isInSector(_ p: TimedPoint, course: CompetitionTaskCourse, name: String) -> Bool {
        guard let tp = CompetitionTaskCourseData.turnpoints[name],
              let bisector = CompetitionTaskCourseData.sectorBearing(in: course, turnpointName: name) else { return false }
        let distance = CLLocation(latitude: tp.latitude, longitude: tp.longitude)
            .distance(from: CLLocation(latitude: p.coordinate.latitude, longitude: p.coordinate.longitude))
        return distance <= CompetitionTaskCourseData.turnpointRadiusKm * 1000
            && CompetitionTaskCourseData.isBearing(tp.bearingDegrees(to: p.coordinate), withinSectorCenteredOn: bisector)
    }

    private static func requiredAltitudeM(at p: TimedPoint) -> Double? {
        let center = CompetitionAltitudeGuideline.referenceCoordinate
        let distanceKm = CLLocation(latitude: center.latitude, longitude: center.longitude)
            .distance(from: CLLocation(latitude: p.coordinate.latitude, longitude: p.coordinate.longitude)) / 1000
        return CompetitionAltitudeGuideline.requiredAltitudeM(atDistanceKm: distanceKm)
    }

    // MARK: - Line crossings, on a local flat projection around the field (fine at this scale)

    private struct XY { let x: Double; let y: Double }

    private static let origin = CompetitionTaskCourseData.startPoint
    private static let metersPerDegLat = 110_574.0
    private static let metersPerDegLon = 111_320.0 * cos(origin.latitude * .pi / 180)

    private static func xy(_ c: CLLocationCoordinate2D) -> XY {
        XY(x: (c.longitude - origin.longitude) * metersPerDegLon, y: (c.latitude - origin.latitude) * metersPerDegLat)
    }

    private static let goalLine = (a: xy(CompetitionTaskCourseData.goalLinePointA), b: xy(CompetitionTaskCourseData.goalLinePointB))

    /// The start line crosses the runway at right angles, as the goal line does (細則3), so it runs
    /// parallel to the goal line through the ST point (the projection origin).
    private static let startLine: (a: XY, b: XY) = {
        let dx = goalLine.b.x - goalLine.a.x
        let dy = goalLine.b.y - goalLine.a.y
        let len = (dx * dx + dy * dy).squareRoot()
        let ux = dx / len * startLineHalfWidthM
        let uy = dy / len * startLineHalfWidthM
        return (XY(x: -ux, y: -uy), XY(x: ux, y: uy))
    }()

    private struct Crossing {
        let time: Date
        let altitudeM: Double
        let afterIndex: Int
    }

    /// The first track segment from `from` on that crosses segment a-b, with time and altitude
    /// interpolated to the crossing point.
    private static func firstCrossing(_ points: [TimedPoint], from: Int, a: XY, b: XY) -> Crossing? {
        guard points.count >= 2 else { return nil }
        for i in max(from, 0)..<(points.count - 1) {
            let p = points[i], q = points[i + 1]
            guard let t = intersection(xy(p.coordinate), xy(q.coordinate), a, b) else { continue }
            return Crossing(
                time: p.time.addingTimeInterval(q.time.timeIntervalSince(p.time) * t),
                altitudeM: p.altitudeM + (q.altitudeM - p.altitudeM) * t,
                afterIndex: i + 1
            )
        }
        return nil
    }

    /// Where along p→q (0...1) it crosses a-b, or nil if it doesn't.
    private static func intersection(_ p: XY, _ q: XY, _ a: XY, _ b: XY) -> Double? {
        let rx = q.x - p.x, ry = q.y - p.y
        let sx = b.x - a.x, sy = b.y - a.y
        let denom = rx * sy - ry * sx
        guard denom != 0 else { return nil }
        let t = ((a.x - p.x) * sy - (a.y - p.y) * sx) / denom
        let u = ((a.x - p.x) * ry - (a.y - p.y) * rx) / denom
        return (0...1).contains(t) && (0...1).contains(u) ? t : nil
    }
}
