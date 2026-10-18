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

/// 競技規則56(1): a flight that rounds some turnpoints but not the whole course scores distance only
/// - "the last valid turnpoint plus 妻沼" (管理ポイント doesn't count). The official results of
/// 2026-10-17 scored 高林給水塔→邑楽タワー as 20.2 km, i.e. including the way back to 妻沼 (ST point →
/// turnpoints → ST point), not 細則2's 14.6 km to the last turnpoint; so the table below is that
/// round distance, taking 細則2's figures where they cover the same route.
enum PartialLapDistance {
    static let checkpointName = "管理ポイント"

    /// Keyed by the turnpoints rounded in order (after 妻沼), in km.
    static let table: [[String]: Double] = [
        ["高林給水塔"]: 11.4, // 細則2 1ポイント
        ["千代田"]: 12.8, // 細則2 1ポイント
        ["高林給水塔", "千代田"]: 23.6, // = 細則2 コース②
        ["千代田", "高林給水塔"]: 23.6, // = 細則2 コース②
        ["高林給水塔", "邑楽タワー"]: 20.2, // 2026-10-17 official results
        ["千代田", "邑楽タワー"]: 18.0, // ST point round, 17.97 km
        ["高林給水塔", "邑楽タワー", "千代田"]: 26.1, // ST point round, 26.11 km
        ["千代田", "邑楽タワー", "高林給水塔"]: 26.1, // = 細則2 コース④
    ]

    /// The course's turnpoints that score, in order.
    static func scoredTurnpoints(of course: CompetitionTaskCourse) -> [String] {
        Array(course.turnpointNames.dropFirst().dropLast()).filter { $0 != checkpointName }
    }

    /// The distance points for an unfinished flight that rounded `passed` (in order), or nil if none.
    static func distanceKm(passed: [String]) -> Double? {
        table[passed.filter { $0 != checkpointName }]
    }
}

/// Reference scores for the day's completed laps and partly flown courses, per 競技規則53:
/// Ph = Rd × (500 + 500 × Rv) × Rh, where Rd = distance / the day's longest, Rv = speed / the
/// day's fastest lap (0 for a partly flown course, 規則56), and Rh = the glider's handicap / the
/// handicap of the fastest glider - or, with no completed lap, of the longest distance - taking
/// the larger one on a tie. If the top score exceeds 1000 it becomes 1000 and the rest scale with
/// it. A low goal is handled per 細則7 別表(4): crossed at 280-330 m, 50 points off the result
/// (never below 0); below 280 m, no speed points (and its speed doesn't set the day's fastest).
/// Unofficial: the rules score each pilot's last flight of the day from 1-second IGC logs, while
/// this scores every flight the app timed.
enum LapScore {
    static let goalMinAltitudeM = 330.0
    static let goalNoSpeedBelowM = 280.0
    static let lowGoalDeduction = 50.0

    enum GoalPenalty {
        case none
        /// 別表(4)①: crossed at 280 m to under 330 m.
        case deduction
        /// 別表(4)②: crossed under 280 m - Rv = 0.
        case noSpeedPoints
    }

    static func goalPenalty(altitudeM: Double?) -> GoalPenalty {
        guard let altitudeM else { return .none }
        if altitudeM < goalNoSpeedBelowM { return .noSpeedPoints }
        if altitudeM < goalMinAltitudeM { return .deduction }
        return .none
    }

    struct Lap {
        let id: UUID
        let distanceKm: Double
        /// nil for a partly flown course: no speed points.
        let lapSeconds: Double?
        let handicap: Double
        /// The goal crossing's altitude, for a completed lap.
        var goalAltitudeM: Double? = nil
        var penalty: GoalPenalty { lapSeconds == nil ? .none : LapScore.goalPenalty(altitudeM: goalAltitudeM) }
        /// The speed that earns speed points: none for a partly flown course or a goal under 280 m.
        var speedKmh: Double {
            guard let lapSeconds, penalty != .noSpeedPoints else { return 0 }
            return distanceKm / (lapSeconds / 3600)
        }
    }

    static func scores(_ laps: [Lap]) -> [UUID: Double] {
        let laps = laps.filter { $0.distanceKm > 0 && ($0.lapSeconds ?? 1) > 0 }
        guard let maxDistance = laps.map(\.distanceKm).max() else { return [:] }
        let maxSpeed = laps.map(\.speedKmh).max() ?? 0
        let reference = maxSpeed > 0 ? laps.filter { $0.speedKmh == maxSpeed } : laps.filter { $0.distanceKm == maxDistance }
        let referenceHandicap = reference.map(\.handicap).max() ?? 1
        var scores = Dictionary(uniqueKeysWithValues: laps.map { lap in
            let rv = maxSpeed > 0 ? lap.speedKmh / maxSpeed : 0
            return (lap.id, lap.distanceKm / maxDistance * (500 + 500 * rv) * lap.handicap / referenceHandicap)
        })
        if let top = scores.values.max(), top > 1000 {
            scores = scores.mapValues { $0 * 1000 / top }
        }
        // 別表の基本的考え方(1)(2): deductions come off the computed points, never below 0.
        for lap in laps where lap.penalty == .deduction {
            scores[lap.id] = max(0, (scores[lap.id] ?? 0) - lowGoalDeduction)
        }
        return scores
    }

    /// The scoring laps among passage-history rows: goal rows with a lap time, and partial-lap rows.
    static func laps(from records: [TurnpointPassageRecord], handicap: (String?) -> Double) -> [Lap] {
        records.compactMap { record in
            guard record.isScoredFlight, let distanceKm = record.courseDistanceKm,
                  record.isPartialLap || record.lapSeconds != nil else { return nil }
            return Lap(id: record.id, distanceKm: distanceKm, lapSeconds: record.lapSeconds, handicap: handicap(record.gliderID),
                       goalAltitudeM: record.isFinish ? record.altitudeM : nil)
        }
    }

    /// "To beat": the longest lap time over `distanceKm` with which a glider of `handicap` would
    /// score above everyone in `laps` - counting that its own lap is rescored with them (a faster lap
    /// becomes the day's fastest and the handicap reference, lowering the others). Assumes a goal at
    /// 330 m or above. `.infinity` if any lap would do (no completed lap yet to beat), nil if none.
    static func lapSecondsToBeat(_ laps: [Lap], distanceKm: Double, handicap: Double) -> Double? {
        guard !laps.isEmpty, distanceKm > 0 else { return nil }
        let candidate = UUID()
        func beats(_ lapSeconds: Double) -> Bool {
            let scores = self.scores(laps + [Lap(id: candidate, distanceKm: distanceKm, lapSeconds: lapSeconds, handicap: handicap)])
            let mine = scores[candidate] ?? 0
            return scores.allSatisfy { $0.key == candidate || $0.value < mine }
        }
        var fast = 60.0, slow = 6 * 3600.0
        if beats(slow) { return .infinity }
        guard beats(fast) else { return nil }
        // Faster always scores relatively better, so bisect for the slowest time that still beats.
        for _ in 0..<40 {
            let mid = (fast + slow) / 2
            if beats(mid) { fast = mid } else { slow = mid }
        }
        return fast
    }
}

/// Each team's totals before today, as `TeamRanking` adds today's flights to them (see
/// `CompetitionScoring.standingsBeforeToday`).
struct TeamStandings {
    struct Team {
        var name: String
        /// LoRa tracker ids and FLARM callsigns of the team's gliders whose flights today count.
        var gliderIds: [String] = []
        /// Team total (団体), per 規則55: each day's top three pilots, summed over the days.
        var total: Double?
        /// 全国選出点 total, per 規則60: each day's best pilot score, summed over the days.
        var national: Double?
    }

    var competition: String = ""
    var teams: [Team] = []
}

/// A competition's team scores day by day: a day's corrected/confirmed scores where someone entered
/// them, else the app's own - each team's best three reference scores of that day (best one for
/// 全国選出点), floored like the official results, on the course that day's laps were judged
/// against. Kept in step with the Android app's CompetitionScoring, which has the unit tests.
enum CompetitionScoring {
    /// The app's own team scores for one day, from that day's passage-history rows.
    static func autoDayScores(_ competition: Competition, dayRecords: [TurnpointPassageRecord], courseName: String?,
                              handicap: (String?) -> Double) -> [String: Competition.DayScore] {
        let records = dayRecords.filter { $0.belongs(toCourse: courseName) }
        let laps = LapScore.laps(from: records, handicap: handicap)
        let scores = LapScore.scores(laps)
        let gliders = Dictionary(records.map { ($0.id, $0.gliderID) }, uniquingKeysWith: { first, _ in first })
        var result: [String: Competition.DayScore] = [:]
        for team in competition.teams {
            let teamScores = laps.filter { lap in (gliders[lap.id] ?? nil).map { team.gliderIds.contains($0) } ?? false }
                .compactMap { scores[$0.id] }.map { $0.rounded(.down) }.sorted(by: >)
            result[team.name] = Competition.DayScore(total: teamScores.prefix(3).reduce(0, +), national: teamScores.first ?? 0)
        }
        return result
    }

    /// The course most of a day's laps were judged against, for days the user isn't choosing it.
    static func dayCourse(_ dayRecords: [TurnpointPassageRecord]) -> String? {
        let names = dayRecords.filter(\.isScoredFlight).compactMap(\.courseName)
        let counts = Dictionary(names.map { ($0, 1) }, uniquingKeysWith: +)
        return counts.max { ($0.value, $1.key) < ($1.value, $0.key) }?.key
    }

    /// One day's scores: the shared corrections where entered, else the app's own.
    static func dayScores(_ competition: Competition, date: Date, dayRecords: [TurnpointPassageRecord], courseName: String?,
                          handicap: (String?) -> Double) -> [String: Competition.DayScore] {
        let auto = autoDayScores(competition, dayRecords: dayRecords, courseName: courseName, handicap: handicap)
        let entered = competition.days[Competition.dateFormatter.string(from: date)] ?? [:]
        var result: [String: Competition.DayScore] = [:]
        for team in competition.teams {
            result[team.name] = Competition.DayScore(total: entered[team.name]?.total ?? auto[team.name]?.total,
                                                     national: entered[team.name]?.national ?? auto[team.name]?.national)
        }
        return result
    }

    /// The teams' totals for `TeamRanking`: every competition day before today (corrected or the
    /// app's own), plus today's corrections where entered - those teams' gliders are then left out so
    /// today's flights aren't counted twice. `recordsOn` gives a day's passage-history rows.
    static func standingsBeforeToday(_ competition: Competition, today: Date, recordsOn: (Date) -> [TurnpointPassageRecord],
                                     handicap: (String?) -> Double) -> TeamStandings {
        let calendar = Calendar.current
        let before = competition.dates(until: today).filter { !calendar.isDate($0, inSameDayAs: today) }.map { date in
            let records = recordsOn(date)
            return dayScores(competition, date: date, dayRecords: records, courseName: dayCourse(records), handicap: handicap)
        }
        let todayEntered = competition.days[Competition.dateFormatter.string(from: today)] ?? [:]
        return TeamStandings(competition: competition.name, teams: competition.teams.map { team in
            let entered = todayEntered[team.name]
            return TeamStandings.Team(
                name: team.name,
                gliderIds: entered?.total != nil ? [] : team.gliderIds,
                total: before.reduce(0) { $0 + ($1[team.name]?.total ?? 0) } + (entered?.total ?? 0),
                national: before.reduce(0) { $0 + ($1[team.name]?.national ?? 0) } + (entered?.national ?? 0)
            )
        })
    }
}

/// Provisional team standings: the official totals plus today's reference scores (see `LapScore`) of
/// each team's gliders - the best three flights for the team total and the best one for 全国選出点,
/// floored like the official results. Pilots aren't known, so each flight counts as a different
/// pilot's last flight. Also finds the lap time a team needs to reach a given rank today. Kept in
/// step with the Android app's TeamRanking, which has the unit tests.
enum TeamRanking {
    struct Row: Identifiable {
        let name: String
        let official: Double
        let today: Double
        let nationalOfficial: Double
        let nationalToday: Double
        /// 1 = top; teams level on points share a rank.
        var rank: Int
        let flightsToday: Int
        var total: Double { official + today }
        var nationalTotal: Double { nationalOfficial + nationalToday }
        var id: String { name }
    }

    /// One of today's scored flights: its lap (for rescoring) and the glider that flew it.
    struct Flight {
        let lap: LapScore.Lap
        let gliderID: String?
    }

    static func rows(_ standings: TeamStandings, flights: [Flight], includeToday: Bool) -> [Row] {
        rows(standings, scores: includeToday ? LapScore.scores(flights.map(\.lap)) : [:], flights: flights, includeToday: includeToday)
    }

    private static func rows(_ standings: TeamStandings, scores: [UUID: Double], flights: [Flight], includeToday: Bool) -> [Row] {
        let partial: [Row] = standings.teams.map { team in
            let teamScores: [Double] = includeToday
                ? flights.filter { flight in flight.gliderID.map { team.gliderIds.contains($0) } ?? false }
                    .compactMap { scores[$0.lap.id] }.map { $0.rounded(.down) }.sorted(by: >)
                : []
            return Row(name: team.name, official: team.total ?? 0, today: teamScores.prefix(3).reduce(0, +),
                       nationalOfficial: team.national ?? 0, nationalToday: teamScores.first ?? 0,
                       rank: 0, flightsToday: teamScores.count)
        }
        return partial.map { row in
            var ranked = row
            ranked.rank = 1 + partial.filter { $0.total > row.total }.count
            return ranked
        }.sorted { $0.rank < $1.rank }
    }

    /// The slowest lap over `distanceKm` in `teamName`'s glider (`gliderID`, `handicap`) with which the
    /// team would rank `targetRank` or better today, rescoring the whole field with it. Assumes a goal
    /// at 330 m or above. `.infinity` if any lap would do, nil if no lap could.
    static func lapSecondsToReach(_ standings: TeamStandings, flights: [Flight], teamName: String, gliderID: String,
                                  handicap: Double, distanceKm: Double, targetRank: Int) -> Double? {
        guard distanceKm > 0, standings.teams.contains(where: { $0.name == teamName }) else { return nil }
        let candidate = UUID()
        func reaches(_ lapSeconds: Double) -> Bool {
            let all = flights + [Flight(lap: LapScore.Lap(id: candidate, distanceKm: distanceKm, lapSeconds: lapSeconds, handicap: handicap),
                                        gliderID: gliderID)]
            let ranked = rows(standings, scores: LapScore.scores(all.map(\.lap)), flights: all, includeToday: true)
            return (ranked.first { $0.name == teamName }?.rank ?? Int.max) <= targetRank
        }
        var fast = 60.0, slow = 6 * 3600.0
        if reaches(slow) { return .infinity }
        guard reaches(fast) else { return nil }
        // Faster always helps the team relatively, so bisect for the slowest lap that still reaches.
        for _ in 0..<40 {
            let mid = (fast + slow) / 2
            if reaches(mid) { fast = mid } else { slow = mid }
        }
        return fast
    }
}
