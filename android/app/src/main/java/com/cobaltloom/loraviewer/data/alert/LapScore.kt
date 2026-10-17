package com.cobaltloom.loraviewer.data.alert

/** One row of the competition handicap table (競技規則53), with the glider types it covers. */
data class HandicapOption(val value: Double, val types: String)

/**
 * Reference scores for the day's completed laps, per 競技規則53: Ph = Rd × (500 + 500 × Rv) × Rh,
 * where Rd = distance / the day's longest, Rv = speed / the day's fastest, and Rh = the glider's
 * handicap / the handicap of the fastest glider (the larger one on a tie). If the top score exceeds
 * 1000 it becomes 1000 and the rest scale with it. Only completed laps are scored, so with one
 * course a day every Rd is 1. Unofficial: the rules score each pilot's last flight of the day from
 * 1-second IGC logs, while this scores every lap the app timed. Kept in step with the iOS LapScore.
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

    data class Lap(val id: String, val distanceKm: Double, val lapSeconds: Double, val handicap: Double) {
        val speedKmh: Double get() = distanceKm / (lapSeconds / 3600)
    }

    fun scores(laps: List<Lap>): Map<String, Double> {
        val valid = laps.filter { it.lapSeconds > 0 && it.distanceKm > 0 }
        val maxSpeed = valid.maxOfOrNull { it.speedKmh } ?: return emptyMap()
        val maxDistance = valid.maxOf { it.distanceKm }
        val referenceHandicap = valid.filter { it.speedKmh == maxSpeed }.maxOf { it.handicap }
        val scores = valid.associate { lap ->
            lap.id to lap.distanceKm / maxDistance * (500 + 500 * lap.speedKmh / maxSpeed) * lap.handicap / referenceHandicap
        }
        val top = scores.values.max()
        return if (top > 1000) scores.mapValues { it.value * 1000 / top } else scores
    }
}
