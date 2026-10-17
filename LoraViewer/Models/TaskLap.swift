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

/// Remembers each glider's last lap status so a refresh only re-times gliders whose track changed.
/// A finished lap is reused while its track just grows at the end, since points after the finish
/// can't change it (start, turnpoints and finish are each the first match in order).
final class TaskLapCache {
    private struct Entry {
        let courseKey: String
        let firstTime: Date
        let lastTime: Date
        let count: Int
        let status: TaskLapStatus
    }

    private var entries: [String: Entry] = [:]

    func statuses(for tracks: [String: [TimedPoint]], course: CompetitionTaskCourse) -> [String: TaskLapStatus] {
        let courseKey = course.turnpointNames.joined(separator: ",")
        entries = entries.filter { tracks[$0.key] != nil }
        var result: [String: TaskLapStatus] = [:]
        for (id, points) in tracks {
            guard let first = points.first?.time, let last = points.last?.time else {
                result[id] = TaskLapCalculator.evaluate(points, course: course)
                continue
            }
            if let entry = entries[id], entry.courseKey == courseKey, entry.firstTime == first,
               (entry.lastTime == last && entry.count == points.count)
                || (entry.status.finish != nil && points.count >= entry.count) {
                result[id] = entry.status
                continue
            }
            let status = TaskLapCalculator.evaluate(points, course: course)
            entries[id] = Entry(courseKey: courseKey, firstTime: first, lastTime: last, count: points.count, status: status)
            result[id] = status
        }
        return result
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
    /// Turnpoints are checked along the line between fixes (see `TurnpointSector.firstEntry`), so a
    /// sector clipped between two sparse fixes still counts.
    static func evaluate(_ points: [TimedPoint], course: CompetitionTaskCourse) -> TaskLapStatus {
        let turnpoints = Array(course.turnpointNames.dropFirst().dropLast())
        guard let start = firstCrossing(points, from: 0, a: startLine.a, b: startLine.b) else {
            return TaskLapStatus(start: nil, startAltitudeM: nil, passedTurnpoints: [], totalTurnpoints: turnpoints.count, finish: nil, finishAltitudeM: nil)
        }
        var passed: [(name: String, time: Date)] = []
        // The segment points[index - 1] → points[index] is checked, from the start crossing on.
        var index = start.afterIndex
        var lastEntry = 0.0
        while index < points.count, passed.count < turnpoints.count {
            let a = points[index - 1], b = points[index]
            let name = turnpoints[passed.count]
            let t = TurnpointSector.firstEntry(from: a, to: b, course: course, name: name) { t in
                let p = TurnpointSector.interpolate(a, b, t)
                return p.altitudeM >= (requiredAltitudeM(at: p) ?? -.infinity)
            }
            if let t {
                // The next turnpoint is looked for from the same segment, in case it's that close.
                passed.append((name, TurnpointSector.interpolate(a, b, t).time))
                lastEntry = t
            } else {
                index += 1
            }
        }
        guard passed.count == turnpoints.count else {
            return TaskLapStatus(start: start.time, startAltitudeM: start.altitudeM, passedTurnpoints: passed, totalTurnpoints: turnpoints.count, finish: nil, finishAltitudeM: nil)
        }
        let finish = firstCrossing(points, from: index - 1, a: goalLine.a, b: goalLine.b, minFraction: lastEntry)
        return TaskLapStatus(start: start.time, startAltitudeM: start.altitudeM, passedTurnpoints: passed, totalTurnpoints: turnpoints.count,
                             finish: finish?.time, finishAltitudeM: finish?.altitudeM)
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

    /// The first track segment from `from` on that crosses segment a-b - on segment `from` itself,
    /// only at or past `minFraction` of the way along it - with time and altitude interpolated to
    /// the crossing point.
    private static func firstCrossing(_ points: [TimedPoint], from: Int, a: XY, b: XY, minFraction: Double = 0) -> Crossing? {
        guard points.count >= 2, max(from, 0) < points.count - 1 else { return nil }
        for i in max(from, 0)..<(points.count - 1) {
            let p = points[i], q = points[i + 1]
            guard let t = intersection(xy(p.coordinate), xy(q.coordinate), a, b), i != from || t >= minFraction else { continue }
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

/// Reference scores for the day's completed laps, per 競技規則53: Ph = Rd × (500 + 500 × Rv) × Rh,
/// where Rd = distance / the day's longest, Rv = speed / the day's fastest, and Rh = the glider's
/// handicap / the handicap of the fastest glider (the larger one on a tie). If the top score
/// exceeds 1000 it becomes 1000 and the rest scale with it. Only completed laps are scored, so
/// with one course a day every Rd is 1. Unofficial: the rules score each pilot's last flight of
/// the day from 1-second IGC logs, while this scores every lap the app timed.
enum LapScore {
    struct Lap {
        let id: UUID
        let distanceKm: Double
        let lapSeconds: Double
        let handicap: Double
        var speedKmh: Double { distanceKm / (lapSeconds / 3600) }
    }

    static func scores(_ laps: [Lap]) -> [UUID: Double] {
        let laps = laps.filter { $0.lapSeconds > 0 && $0.distanceKm > 0 }
        guard let maxSpeed = laps.map(\.speedKmh).max(), let maxDistance = laps.map(\.distanceKm).max() else { return [:] }
        let referenceHandicap = laps.filter { $0.speedKmh == maxSpeed }.map(\.handicap).max() ?? 1
        var scores = Dictionary(uniqueKeysWithValues: laps.map { lap in
            (lap.id, lap.distanceKm / maxDistance * (500 + 500 * lap.speedKmh / maxSpeed) * lap.handicap / referenceHandicap)
        })
        if let top = scores.values.max(), top > 1000 {
            scores = scores.mapValues { $0 * 1000 / top }
        }
        return scores
    }
}
