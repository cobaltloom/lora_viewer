package com.cobaltloom.loraviewer.data.alert

/** One row of the competition handicap table (競技規則53), with the glider types it covers. */
data class HandicapOption(val value: Double, val types: String)

/**
 * 競技規則56(1): a flight that rounds some turnpoints but not the whole course scores distance only -
 * "the last valid turnpoint plus 妻沼" (管理ポイント doesn't count). The official results of
 * 2026-10-17 scored 高林給水塔→邑楽タワー as 20.2 km, i.e. including the way back to 妻沼 (ST point →
 * turnpoints → ST point), not 細則2's 14.6 km to the last turnpoint; so the table below is that round
 * distance, taking 細則2's figures where they cover the same route. Kept in step with iOS.
 */
object PartialLapDistance {
    const val CHECKPOINT_NAME = "管理ポイント"

    /** Keyed by the turnpoints rounded in order (after 妻沼), in km. */
    val table: Map<List<String>, Double> = mapOf(
        listOf("高林給水塔") to 11.4, // 細則2 1ポイント
        listOf("千代田") to 12.8, // 細則2 1ポイント
        listOf("高林給水塔", "千代田") to 23.6, // = 細則2 コース②
        listOf("千代田", "高林給水塔") to 23.6, // = 細則2 コース②
        listOf("高林給水塔", "邑楽タワー") to 20.2, // 2026-10-17 official results
        listOf("千代田", "邑楽タワー") to 18.0, // ST point round, 17.97 km
        listOf("高林給水塔", "邑楽タワー", "千代田") to 26.1, // ST point round, 26.11 km
        listOf("千代田", "邑楽タワー", "高林給水塔") to 26.1, // = 細則2 コース④
    )

    /** The course's turnpoints that score, in order. */
    fun scoredTurnpoints(course: CompetitionTaskCourse): List<String> =
        course.turnpointNames.drop(1).dropLast(1).filter { it != CHECKPOINT_NAME }

    /** The distance points for an unfinished flight that rounded [passed] (in order), or null if none. */
    fun distanceKm(passed: List<String>): Double? = table[passed.filter { it != CHECKPOINT_NAME }]
}

/**
 * Reference scores for the day's completed laps and partly flown courses, per 競技規則53:
 * Ph = Rd × (500 + 500 × Rv) × Rh, where Rd = distance / the day's longest, Rv = speed / the day's
 * fastest lap (0 for a partly flown course, 規則56), and Rh = the glider's handicap / the handicap of
 * the fastest glider - or, with no completed lap, of the longest distance - taking the larger one on
 * a tie. If the top score exceeds 1000 it becomes 1000 and the rest scale with it. A low goal is
 * handled per 細則7 別表(4): crossed at 280-330 m, 50 points off the result (never below 0); below
 * 280 m, no speed points (and its speed doesn't set the day's fastest). Unofficial: the rules score
 * each pilot's last flight of the day from 1-second IGC logs, while this scores every flight the app
 * timed. Kept in step with the iOS LapScore.
 */
object LapScore {
    const val DEFAULT_HANDICAP = 1.0
    const val GOAL_MIN_ALTITUDE_M = 330.0
    const val GOAL_NO_SPEED_BELOW_M = 280.0
    const val LOW_GOAL_DEDUCTION = 50.0

    enum class GoalPenalty {
        NONE,

        /** 別表(4)①: crossed at 280 m to under 330 m. */
        DEDUCTION,

        /** 別表(4)②: crossed under 280 m - Rv = 0. */
        NO_SPEED_POINTS,
    }

    fun goalPenalty(altitudeM: Double?): GoalPenalty = when {
        altitudeM == null -> GoalPenalty.NONE
        altitudeM < GOAL_NO_SPEED_BELOW_M -> GoalPenalty.NO_SPEED_POINTS
        altitudeM < GOAL_MIN_ALTITUDE_M -> GoalPenalty.DEDUCTION
        else -> GoalPenalty.NONE
    }

    /** Known club gliders that aren't 1.00, so laps score right even for gliders that have landed
     * and left the member list (as of 第42回関東学生グライダー競技会, 2026-10): LoRa tracker ids from
     * the site's board, and the FLARM each glider was seen carrying. Spare LoRa trackers
     * (000D-000F) move between gliders, so they're left out. Kept in step with iOS. */
    val builtInHandicaps = mapOf(
        "0006" to 0.90, "ICA84C247" to 0.90, // 早稲田 LS4
        "0007" to 0.95, "FLRDB0774" to 0.95, // 明治 DG-100G
        "0008" to 0.90, "FLRDB0775" to 0.90, // 慶應 Discus
        "000B" to 0.90, "ICA841083" to 0.90, // 法政 Discus
    )

    fun builtInDefault(id: String): Double = builtInHandicaps[id] ?: DEFAULT_HANDICAP

    val handicapOptions = listOf(
        HandicapOption(0.90, "Discus, LS4, LS8, ASW24, ASW28"),
        HandicapOption(0.95, "G102(引込脚), シーラス, ASW19, DG-101G, クラブリベレ"),
        HandicapOption(1.00, "ASK23, SZD51, ASK21, G102(固定脚)"),
        HandicapOption(1.05, "Ka6, SZD50, PW-5"),
        HandicapOption(1.10, "K-8, ASK13"),
    )

    /** [lapSeconds] is null for a partly flown course: no speed points. [goalAltitudeM] is the goal
     * crossing's altitude, for a completed lap. */
    data class Lap(
        val id: String,
        val distanceKm: Double,
        val lapSeconds: Double?,
        val handicap: Double,
        val goalAltitudeM: Double? = null,
    ) {
        val penalty: GoalPenalty get() = if (lapSeconds == null) GoalPenalty.NONE else goalPenalty(goalAltitudeM)

        /** The speed that earns speed points: none for a partly flown course or a goal under 280 m. */
        val speedKmh: Double
            get() = if (lapSeconds == null || penalty == GoalPenalty.NO_SPEED_POINTS) 0.0 else distanceKm / (lapSeconds / 3600)
    }

    fun scores(laps: List<Lap>): Map<String, Double> {
        val valid = laps.filter { it.distanceKm > 0 && (it.lapSeconds ?: 1.0) > 0 }
        val maxDistance = valid.maxOfOrNull { it.distanceKm } ?: return emptyMap()
        val maxSpeed = valid.maxOf { it.speedKmh }
        val reference = if (maxSpeed > 0) valid.filter { it.speedKmh == maxSpeed } else valid.filter { it.distanceKm == maxDistance }
        val referenceHandicap = reference.maxOf { it.handicap }
        val scores = valid.associate { lap ->
            val rv = if (maxSpeed > 0) lap.speedKmh / maxSpeed else 0.0
            lap.id to lap.distanceKm / maxDistance * (500 + 500 * rv) * lap.handicap / referenceHandicap
        }
        val top = scores.values.max()
        val scaled = if (top > 1000) scores.mapValues { it.value * 1000 / top } else scores
        // 別表の基本的考え方(1)(2): deductions come off the computed points, never below 0.
        val deducted = valid.filter { it.penalty == GoalPenalty.DEDUCTION }.map { it.id }.toSet()
        return scaled.mapValues { (id, points) -> if (id in deducted) maxOf(0.0, points - LOW_GOAL_DEDUCTION) else points }
    }

    /** The scoring laps among passage-history rows: goal rows with a lap time, and partial-lap rows. */
    fun laps(records: List<TurnpointPassageRecord>, handicap: (String?) -> Double): List<Lap> = records.mapNotNull { record ->
        val distanceKm = record.courseDistanceKm ?: return@mapNotNull null
        if (!record.isScoredFlight || (record.isFinish && record.lapSeconds == null)) return@mapNotNull null
        Lap(record.id, distanceKm, record.lapSeconds, handicap(record.gliderId), if (record.isFinish) record.altitudeM else null)
    }

    /**
     * "To beat": the longest lap time over [distanceKm] with which a glider of [handicap] would score
     * above everyone in [laps] - counting that its own lap is rescored with them (a faster lap becomes
     * the day's fastest and the handicap reference, lowering the others). Assumes a goal at 330 m or
     * above. [Double.POSITIVE_INFINITY] if any lap would do (no completed lap yet to beat), null if none.
     */
    fun lapSecondsToBeat(laps: List<Lap>, distanceKm: Double, handicap: Double): Double? {
        if (laps.isEmpty() || distanceKm <= 0) return null
        val candidate = "\u0000toBeat"
        fun beats(lapSeconds: Double): Boolean {
            val scores = scores(laps + Lap(candidate, distanceKm, lapSeconds, handicap))
            val mine = scores[candidate] ?: 0.0
            return scores.all { (id, points) -> id == candidate || points < mine }
        }
        var fast = 60.0
        var slow = 6 * 3600.0
        if (beats(slow)) return Double.POSITIVE_INFINITY
        if (!beats(fast)) return null
        // Faster always scores relatively better, so bisect for the slowest time that still beats.
        repeat(40) {
            val mid = (fast + slow) / 2
            if (beats(mid)) fast = mid else slow = mid
        }
        return fast
    }
}
