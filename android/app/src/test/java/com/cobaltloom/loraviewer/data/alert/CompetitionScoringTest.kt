package com.cobaltloom.loraviewer.data.alert

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CompetitionScoringTest {
    private val c3 = CompetitionTaskCourseData.courses[2].name
    private val c1 = CompetitionTaskCourseData.courses[0].name
    private fun lap(id: String, minutes: Int) = LapResult(id, 0.0, finish = minutes * 60.0, lapSeconds = minutes * 60.0, distanceKm = 26.5, goalAltitudeM = 400.0)
    private fun partial(id: String, km: Double) = LapResult(id, 0.0, distanceKm = km, passed = listOf("高林給水塔"))

    private val day1 = LocalDate.of(2026, 10, 11)
    private val day2 = LocalDate.of(2026, 10, 12)
    private val day3 = LocalDate.of(2026, 10, 13)
    private val competition = Competition(name = "test", startDate = day1.toString(), endDate = "2026-10-14")
    private val results: Map<String, DayResults> = mapOf(
        // Course ③ was flown: more laps completed on it than on ①, whose sectors some laps also clip.
        day1.toString() to mapOf(
            c3 to listOf(lap("a1", 30), lap("b1", 40), lap("b1", 60), partial("c1", 20.2)),
            c1 to listOf(lap("a1", 25)),
        ),
        day2.toString() to mapOf(c3 to listOf(lap("b1", 30))),
    )
    private val handicap: (String) -> Double = { 1.0 }

    @Test
    fun theDaysCourseIsTheOneWithMostLapsUnlessSet() {
        assertEquals(c3, CompetitionScoring.dayCourse(competition, day1, results[day1.toString()]))
        val set = competition.copy(days = mapOf(day1.toString() to Competition.Day(course = c1)))
        assertEquals(c1, CompetitionScoring.dayCourse(set, day1, results[day1.toString()]))
        assertEquals(null, CompetitionScoring.dayCourse(competition, day3, null))
    }

    @Test
    fun everyGliderThatFlewIsATeam() {
        assertEquals(listOf("a1", "b1", "c1"), CompetitionScoring.gliders(competition, day3, results))
    }

    @Test
    fun earlierDaysAddUpFromTheServersLaps() {
        val day1Scores = CompetitionScoring.dayScores(competition, day1, results[day1.toString()], handicap)
        assertEquals(1000.0, day1Scores.getValue("a1").total!!, 0.0)
        assertEquals(875.0 + 750.0, day1Scores.getValue("b1").total!!, 0.0) // two laps of one glider (two pilots)
        assertEquals(875.0, day1Scores.getValue("b1").national!!, 0.0)
        assertEquals(381.0, day1Scores.getValue("c1").total!!, 0.0) // 20.2 / 26.5 × 500
        val standings = CompetitionScoring.standingsBeforeToday(competition, day3, results, handicap)
        assertEquals(1000.0, standings.teams.first { it.name == "a1" }.total!!, 0.0)
        assertEquals(1625.0 + 1000.0, standings.teams.first { it.name == "b1" }.total!!, 0.0)
    }

    @Test
    fun correctionsReplaceTheServersScores() {
        val corrected = competition.copy(
            days = mapOf(
                day1.toString() to Competition.Day(scores = mapOf("a1" to Competition.DayScore(total = 900.0))), // a penalty
                day3.toString() to Competition.Day(scores = mapOf("b1" to Competition.DayScore(total = 500.0, national = 500.0))), // today
            ),
        )
        val standings = CompetitionScoring.standingsBeforeToday(corrected, day3, results, handicap)
        val a = standings.teams.first { it.name == "a1" }
        assertEquals(900.0, a.total!!, 0.0)
        assertEquals(1000.0, a.national!!, 0.0)
        val b = standings.teams.first { it.name == "b1" }
        assertEquals(1625.0 + 1000.0 + 500.0, b.total!!, 0.0) // today's correction included...
        assertEquals(emptyList<String>(), b.gliderIds) // ...so today's flights no longer add to it
    }
}
