package com.cobaltloom.loraviewer.data.alert

import java.time.LocalDate
import kotlin.math.floor
import kotlinx.serialization.Serializable

/**
 * A competition as set up in the app (shared through the track server): its period, and per day the
 * course flown and any corrected team scores, plus shared handicaps. The teams are the gliders
 * themselves (each source's own ids), and their laps come from the track server, which times every
 * flight whether or not any app was open (see [LapResult]).
 */
@Serializable
data class Competition(
    val name: String = "",
    /** First and last competition days, "yyyy-MM-dd". */
    val startDate: String? = null,
    val endDate: String? = null,
    /** Date ("yyyy-MM-dd") → that day's settings. */
    val days: Map<String, Day> = emptyMap(),
    /** Glider id → competition handicap, overriding each device's own. */
    val handicaps: Map<String, Double> = emptyMap(),
    val updatedAt: Long? = null,
) {
    @Serializable
    data class Day(
        /** The course flown that day (an app course name); null = the one most laps were completed on. */
        val course: String? = null,
        /** Glider id → corrected scores (penalties, or where they differ from the official results). */
        val scores: Map<String, DayScore> = emptyMap(),
    )

    /** A team's score for one day: 団体 (best three) and 全国選出点 (best one). */
    @Serializable
    data class DayScore(val total: Double? = null, val national: Double? = null)

    /** The competition days up to and including [until], in order. */
    fun dates(until: LocalDate): List<LocalDate> {
        val start = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return emptyList()
        val end = minOf(endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: until, until)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.take(366).toList()
    }
}

/**
 * One lap, or partly flown course, the track server timed (see its /results): [finish] and
 * [lapSeconds] for a lap, [passed] scoring turnpoints for a partly flown course.
 */
@Serializable
data class LapResult(
    val id: String,
    val start: Double,
    val finish: Double? = null,
    val lapSeconds: Double? = null,
    val distanceKm: Double,
    val goalAltitudeM: Double? = null,
    val passed: List<String> = emptyList(),
)

/** One day's server results: course name → laps and partly flown courses. */
typealias DayResults = Map<String, List<LapResult>>

/**
 * A competition's team scores day by day from the track server's results: each glider's best three
 * reference scores that day (best one for 全国選出点), floored like the official results, on the
 * day's course - unless corrected.
 */
object CompetitionScoring {
    /** The day's course: as set, else the one with the most laps completed (then the most entries). */
    fun dayCourse(competition: Competition, date: LocalDate, results: DayResults?): String? =
        competition.days[date.toString()]?.course
            ?: results?.filterValues { it.isNotEmpty() }
                ?.maxWithOrNull(compareBy<Map.Entry<String, List<LapResult>>>({ e -> e.value.count { it.finish != null } }, { it.value.size }))
                ?.key

    /** A day's flights on [course], for scoring. */
    fun flights(results: DayResults?, course: String?, handicap: (String) -> Double): List<TeamRanking.Flight> =
        results?.get(course).orEmpty().mapIndexed { i, r ->
            TeamRanking.Flight(LapScore.Lap("$course#$i", r.distanceKm, r.lapSeconds, handicap(r.id), r.goalAltitudeM), r.id)
        }

    /** Every glider with a result on a competition day up to [today], or a corrected score. */
    fun gliders(competition: Competition, today: LocalDate, results: Map<String, DayResults>): List<String> {
        val dates = competition.dates(today).map { it.toString() }.toSet()
        val fromResults = results.filterKeys { it in dates }.values.flatMap { day -> day.values.flatten().map { it.id } }
        val corrected = competition.days.filterKeys { it in dates }.values.flatMap { it.scores.keys }
        return (fromResults + corrected).distinct().sorted()
    }

    /** One day's scores per glider: corrections where entered, else the server results'. */
    fun dayScores(
        competition: Competition,
        date: LocalDate,
        results: DayResults?,
        handicap: (String) -> Double,
    ): Map<String, Competition.DayScore> {
        val flights = flights(results, dayCourse(competition, date, results), handicap)
        val scores = LapScore.scores(flights.map { it.lap })
        val auto = flights.groupBy { it.gliderId!! }.mapValues { (_, own) ->
            val best = own.mapNotNull { scores[it.lap.id] }.map { floor(it) }.sortedDescending()
            Competition.DayScore(best.take(3).sum(), best.firstOrNull() ?: 0.0)
        }
        val entered = competition.days[date.toString()]?.scores.orEmpty()
        return (auto.keys + entered.keys).associateWith { id ->
            Competition.DayScore(entered[id]?.total ?: auto[id]?.total ?: 0.0, entered[id]?.national ?: auto[id]?.national ?: 0.0)
        }
    }

    /**
     * Each glider's totals over the competition days before [today], plus today's corrections where
     * entered - those gliders are then left out of today's flights so they aren't counted twice - for
     * [TeamRanking], which adds today's flights. Team names are the glider ids.
     */
    fun standingsBeforeToday(
        competition: Competition,
        today: LocalDate,
        results: Map<String, DayResults>,
        handicap: (String) -> Double,
    ): TeamStandings {
        val before = competition.dates(today).filter { it.isBefore(today) }
            .map { dayScores(competition, it, results[it.toString()], handicap) }
        val todayEntered = competition.days[today.toString()]?.scores.orEmpty()
        return TeamStandings(
            competition = competition.name,
            teams = gliders(competition, today, results).map { id ->
                val entered = todayEntered[id]
                TeamStandings.Team(
                    name = id,
                    gliderIds = if (entered?.total != null) emptyList() else listOf(id),
                    total = before.sumOf { it[id]?.total ?: 0.0 } + (entered?.total ?: 0.0),
                    national = before.sumOf { it[id]?.national ?: 0.0 } + (entered?.national ?: 0.0),
                )
            },
        )
    }
}
