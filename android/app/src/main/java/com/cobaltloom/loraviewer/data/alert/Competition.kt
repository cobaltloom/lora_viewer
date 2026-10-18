package com.cobaltloom.loraviewer.data.alert

import java.time.LocalDate
import kotlin.math.floor
import kotlinx.serialization.Serializable

/**
 * A competition as a representative set it up (shared through the track server): its period, its
 * teams and their gliders, and any day's team scores someone corrected or confirmed. Every other
 * day's scores are worked out by each app from its own lap records (see [CompetitionScoring]).
 */
@Serializable
data class Competition(
    val name: String = "",
    /** First and last competition days, "yyyy-MM-dd". */
    val startDate: String? = null,
    val endDate: String? = null,
    val teams: List<Team> = emptyList(),
    /** Date ("yyyy-MM-dd") → team name → that day's scores, overriding the app's own. */
    val days: Map<String, Map<String, DayScore>> = emptyMap(),
    val updatedAt: Long? = null,
) {
    @Serializable
    data class Team(
        val name: String,
        /** LoRa tracker ids and FLARM callsigns of the team's gliders. */
        val gliderIds: List<String> = emptyList(),
    )

    /** A team's score for one day: 団体 (best three) and 全国選出点 (best one). */
    @Serializable
    data class DayScore(val total: Double? = null, val national: Double? = null)

    /** The competition days up to and including [until], in order. */
    fun dates(until: LocalDate): List<LocalDate> {
        val start = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return emptyList()
        val end = minOf(endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: until, until)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
    }
}

/**
 * A competition's team scores day by day: a day's corrected/confirmed scores where someone entered
 * them, else the app's own - each team's best three reference scores of that day (best one for
 * 全国選出点), floored like the official results, on the course that day's laps were judged against.
 */
object CompetitionScoring {
    /** The app's own team scores for one day, from that day's passage-history rows. */
    fun autoDayScores(
        competition: Competition,
        dayRecords: List<TurnpointPassageRecord>,
        courseName: String?,
        handicap: (String?) -> Double,
    ): Map<String, Competition.DayScore> {
        val records = dayRecords.filter { it.belongsToCourse(courseName) }
        val laps = LapScore.laps(records, handicap)
        val scores = LapScore.scores(laps)
        val gliders = records.associate { it.id to it.gliderId }
        return competition.teams.associate { team ->
            val teamScores = laps.filter { gliders[it.id] in team.gliderIds }
                .mapNotNull { scores[it.id] }.map { floor(it) }.sortedDescending()
            team.name to Competition.DayScore(teamScores.take(3).sum(), teamScores.firstOrNull() ?: 0.0)
        }
    }

    /** The course most of a day's laps were judged against, for days the user isn't choosing it. */
    fun dayCourse(dayRecords: List<TurnpointPassageRecord>): String? =
        dayRecords.filter { it.isScoredFlight }.mapNotNull { it.courseName }.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key

    /** One day's scores: the shared corrections where entered, else the app's own. */
    fun dayScores(
        competition: Competition,
        date: LocalDate,
        dayRecords: List<TurnpointPassageRecord>,
        courseName: String?,
        handicap: (String?) -> Double,
    ): Map<String, Competition.DayScore> {
        val auto = autoDayScores(competition, dayRecords, courseName, handicap)
        val entered = competition.days[date.toString()].orEmpty()
        return competition.teams.associate { team ->
            val a = auto[team.name]
            val e = entered[team.name]
            team.name to Competition.DayScore(e?.total ?: a?.total, e?.national ?: a?.national)
        }
    }

    /**
     * The teams' totals for [TeamRanking]: every competition day before [today] (corrected or the
     * app's own), plus today's corrections where entered - those teams' gliders are then left out so
     * today's flights aren't counted twice. [recordsOn] gives a day's passage-history rows.
     */
    fun standingsBeforeToday(
        competition: Competition,
        today: LocalDate,
        recordsOn: (LocalDate) -> List<TurnpointPassageRecord>,
        handicap: (String?) -> Double,
    ): TeamStandings {
        val before = competition.dates(today).filter { it.isBefore(today) }.map { date ->
            val records = recordsOn(date)
            dayScores(competition, date, records, dayCourse(records), handicap)
        }
        val todayEntered = competition.days[today.toString()].orEmpty()
        return TeamStandings(
            competition = competition.name,
            teams = competition.teams.map { team ->
                val entered = todayEntered[team.name]
                TeamStandings.Team(
                    name = team.name,
                    gliderIds = if (entered?.total != null) emptyList() else team.gliderIds,
                    total = before.sumOf { it[team.name]?.total ?: 0.0 } + (entered?.total ?: 0.0),
                    national = before.sumOf { it[team.name]?.national ?: 0.0 } + (entered?.national ?: 0.0),
                )
            },
        )
    }
}
