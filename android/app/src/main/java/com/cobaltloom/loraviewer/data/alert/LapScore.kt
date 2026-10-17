package com.cobaltloom.loraviewer.data.alert

/** One row of the competition handicap table (競技規則53), with the glider types it covers. */
data class HandicapOption(val value: Double, val types: String)

/**
 * 競技規則56(1): a flight that rounds some turnpoints but not the whole course scores distance only -
 * "the last valid turnpoint plus 妻沼", by the 細則2 table below (管理ポイント doesn't count).
 */
object PartialLapDistance {
    const val CHECKPOINT_NAME = "管理ポイント"

    /** 細則2「ポイント毎の距離」, keyed by the turnpoints rounded in order (after 妻沼). */
    val table: Map<List<String>, Double> = mapOf(
        listOf("高林給水塔") to 11.4,
        listOf("千代田") to 12.8,
        listOf("高林給水塔", "千代田") to 17.7,
        listOf("千代田", "高林給水塔") to 17.8,
        listOf("高林給水塔", "邑楽タワー") to 14.6,
        listOf("千代田", "邑楽タワー") to 11.7,
        listOf("高林給水塔", "邑楽タワー", "千代田") to 20.1,
        listOf("千代田", "邑楽タワー", "高林給水塔") to 20.2,
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
 * a tie. If the top score exceeds 1000 it becomes 1000 and the rest scale with it. Unofficial: the
 * rules score each pilot's last flight of the day from 1-second IGC logs, while this scores every
 * flight the app timed. Kept in step with the iOS LapScore.
 */
object LapScore {
    const val DEFAULT_HANDICAP = 1.0

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

    /** [lapSeconds] is null for a partly flown course: no speed points. */
    data class Lap(val id: String, val distanceKm: Double, val lapSeconds: Double?, val handicap: Double) {
        val speedKmh: Double get() = lapSeconds?.let { distanceKm / (it / 3600) } ?: 0.0
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
        return if (top > 1000) scores.mapValues { it.value * 1000 / top } else scores
    }
}
