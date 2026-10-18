package com.cobaltloom.loraviewer.data.alert

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CompetitionScoringTest {
    private val course = "③"
    private fun goal(day: LocalDate, glider: String, minutes: Int, handicap: String? = null) = TurnpointPassageRecord(
        gliderName = glider,
        turnpointName = TurnpointPassageRecord.FINISH_PREFIX + "x",
        altitudeM = 400.0,
        timestampMillis = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        gliderId = glider,
        lapSeconds = minutes * 60.0,
        courseDistanceKm = 26.5,
        courseName = course,
    )

    private val day1 = LocalDate.of(2026, 10, 11)
    private val day2 = LocalDate.of(2026, 10, 12)
    private val day3 = LocalDate.of(2026, 10, 13)
    private val competition = Competition(
        name = "test",
        startDate = day1.toString(),
        endDate = LocalDate.of(2026, 10, 14).toString(),
        teams = listOf(Competition.Team("A", listOf("a1")), Competition.Team("B", listOf("b1", "b2"))),
    )
    private val records = listOf(
        goal(day1, "a1", 30), // 1000
        goal(day1, "b1", 40), // 875
        goal(day1, "b2", 60), // 750
        goal(day2, "b1", 30), // 1000 (only lap that day)
    )
    private fun recordsOn(date: LocalDate) = records.filter { it.localDate == date }

    @Test
    fun competitionDaysStopAtToday() {
        assertEquals(listOf(day1, day2, day3), competition.dates(day3))
        assertEquals(4, competition.dates(LocalDate.of(2026, 10, 30)).size)
        assertEquals(emptyList<LocalDate>(), competition.dates(day1.minusDays(1)))
    }

    @Test
    fun earlierDaysAddUpFromTheDevicesOwnLaps() {
        val day1Scores = CompetitionScoring.autoDayScores(competition, recordsOn(day1), course) { 1.0 }
        assertEquals(1000.0, day1Scores.getValue("A").total!!, 0.0)
        assertEquals(875.0 + 750.0, day1Scores.getValue("B").total!!, 0.0)
        assertEquals(875.0, day1Scores.getValue("B").national!!, 0.0)
        val standings = CompetitionScoring.standingsBeforeToday(competition, day3, ::recordsOn) { 1.0 }
        assertEquals(1000.0, standings.teams.first { it.name == "A" }.total!!, 0.0)
        assertEquals(1625.0 + 1000.0, standings.teams.first { it.name == "B" }.total!!, 0.0)
        assertEquals(875.0 + 1000.0, standings.teams.first { it.name == "B" }.national!!, 0.0)
    }

    @Test
    fun correctionsReplaceTheDevicesScores() {
        val corrected = competition.copy(
            days = mapOf(
                day1.toString() to mapOf("A" to Competition.DayScore(total = 900.0)), // e.g. a 100-point penalty
                day3.toString() to mapOf("B" to Competition.DayScore(total = 500.0, national = 500.0)), // today
            ),
        )
        val standings = CompetitionScoring.standingsBeforeToday(corrected, day3, ::recordsOn) { 1.0 }
        val a = standings.teams.first { it.name == "A" }
        assertEquals(900.0, a.total!!, 0.0) // corrected
        assertEquals(1000.0, a.national!!, 0.0) // not corrected: the device's own
        val b = standings.teams.first { it.name == "B" }
        assertEquals(1625.0 + 1000.0 + 500.0, b.total!!, 0.0) // today's correction included...
        assertEquals(emptyList<String>(), b.gliderIds) // ...so today's flights no longer add to it
        assertEquals(listOf("a1"), a.gliderIds)
    }
}
