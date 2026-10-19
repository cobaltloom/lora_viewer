package com.cobaltloom.loraviewer.data.alert

import kotlin.math.floor

/**
 * Each team's totals before today, as [TeamRanking] adds today's flights to them (see
 * [CompetitionScoring.standingsBeforeToday]).
 */
data class TeamStandings(
    val competition: String = "",
    val teams: List<Team> = emptyList(),
) {
    data class Team(
        val name: String,
        /** LoRa tracker ids and FLARM callsigns of the team's gliders whose flights today count. */
        val gliderIds: List<String> = emptyList(),
        /** Team total (団体), per 規則55: each day's top three pilots, summed over the days. */
        val total: Double? = null,
        /** 全国選出点 total, per 規則60: each day's best pilot score, summed over the days. */
        val national: Double? = null,
    )
}

/**
 * Provisional team standings: the official totals plus today's reference scores (see [LapScore]) of
 * each team's gliders - the best three flights for the team total and the best one for 全国選出点,
 * floored like the official results. Pilots aren't known, so each flight counts as a different pilot's
 * last flight. Also finds the lap time a team needs to reach a given rank today.
 */
object TeamRanking {
    data class Row(
        val name: String,
        val official: Double,
        val today: Double,
        val nationalOfficial: Double,
        val nationalToday: Double,
        /** 1 = top; teams level on points share a rank. */
        val rank: Int,
        val flightsToday: Int,
    ) {
        val total: Double get() = official + today
        val nationalTotal: Double get() = nationalOfficial + nationalToday
    }

    /** One of today's scored flights: its lap (for rescoring) and the glider that flew it. */
    data class Flight(val lap: LapScore.Lap, val gliderId: String?)

    fun rows(standings: TeamStandings, flights: List<Flight>, includeToday: Boolean): List<Row> =
        rows(standings, if (includeToday) LapScore.scores(flights.map { it.lap }) else emptyMap(), flights, includeToday)

    private fun rows(standings: TeamStandings, scores: Map<String, Double>, flights: List<Flight>, includeToday: Boolean): List<Row> {
        val partial = standings.teams.map { team ->
            val teamScores = if (includeToday) {
                flights.filter { it.gliderId != null && it.gliderId in team.gliderIds }
                    .mapNotNull { scores[it.lap.id] }.map { floor(it) }.sortedDescending()
            } else {
                emptyList()
            }
            Row(
                name = team.name,
                official = team.total ?: 0.0,
                today = teamScores.take(3).sum(),
                nationalOfficial = team.national ?: 0.0,
                nationalToday = teamScores.firstOrNull() ?: 0.0,
                rank = 0,
                flightsToday = teamScores.size,
            )
        }
        return partial.map { row -> row.copy(rank = 1 + partial.count { it.total > row.total }) }.sortedBy { it.rank }
    }

    /**
     * The slowest lap over [distanceKm] in [teamName]'s glider ([gliderId], [handicap]) with which the
     * team would rank [targetRank] or better today, rescoring the whole field with it. Assumes a goal
     * at 330 m or above. [Double.POSITIVE_INFINITY] if any lap would do, null if no lap could.
     */
    fun lapSecondsToReach(
        standings: TeamStandings,
        flights: List<Flight>,
        teamName: String,
        gliderId: String,
        handicap: Double,
        distanceKm: Double,
        targetRank: Int,
    ): Double? {
        if (distanceKm <= 0 || standings.teams.none { it.name == teamName }) return null
        val candidateId = "\u0000reach"
        fun reaches(lapSeconds: Double): Boolean {
            val all = flights + Flight(LapScore.Lap(candidateId, distanceKm, lapSeconds, handicap), gliderId)
            val rows = rows(standings, LapScore.scores(all.map { it.lap }), all, includeToday = true)
            return (rows.firstOrNull { it.name == teamName }?.rank ?: Int.MAX_VALUE) <= targetRank
        }
        var fast = 60.0
        var slow = 6 * 3600.0
        if (reaches(slow)) return Double.POSITIVE_INFINITY
        if (!reaches(fast)) return null
        // Faster always helps the team relatively, so bisect for the slowest lap that still reaches.
        repeat(40) {
            val mid = (fast + slow) / 2
            if (reaches(mid)) fast = mid else slow = mid
        }
        return fast
    }
}
