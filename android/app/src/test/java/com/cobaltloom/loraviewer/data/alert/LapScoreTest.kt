package com.cobaltloom.loraviewer.data.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LapScoreTest {
    private fun lap(id: String, minutes: Int, seconds: Int, handicap: Double, km: Double = 26.4) =
        LapScore.Lap(id, km, minutes * 60.0 + seconds, handicap)

    private fun partial(id: String, km: Double, handicap: Double) = LapScore.Lap(id, km, null, handicap)

    @Test
    fun matchesTheDayCalculatedByHand() {
        // 2026-10-17, course ③ (26.4 km): the fastest lap (ASK23B, 1.00) sets Rv and Rh's denominator.
        val s = LapScore.scores(
            listOf(lap("chuo", 26, 15, 1.00), lap("keio", 27, 52, 0.90), lap("waseda", 28, 40, 0.90), lap("tohoku", 42, 5, 1.00)),
        )
        assertEquals(1000.0, s.getValue("chuo"), 0.05)
        assertEquals(873.8, s.getValue("keio"), 0.5)
        assertEquals(861.9, s.getValue("waseda"), 0.5)
        assertEquals(811.8, s.getValue("tohoku"), 0.5)
    }

    @Test
    fun topScoreAboveAThousandScalesEveryone() {
        // A slower but much higher-handicap glider can top 1000 before scaling.
        val s = LapScore.scores(listOf(lap("fast", 30, 0, 0.90), lap("k8", 31, 0, 1.10)))
        assertEquals(1000.0, s.values.max(), 1e-9)
        assertTrue(s.getValue("fast") < 1000.0)
    }

    @Test
    fun shorterDistanceScalesByDistance() {
        val s = LapScore.scores(listOf(lap("full", 30, 0, 1.0), lap("short", 30, 0, 1.0, km = 13.2)))
        // Same time, half the distance: Rd 0.5 and half the speed, so 0.5 × (500 + 250).
        assertEquals(375.0, s.getValue("short"), 1e-6)
    }

    @Test
    fun noLapsNoScores() {
        assertTrue(LapScore.scores(emptyList()).isEmpty())
    }

    @Test
    fun partlyFlownCoursesScoreDistanceOnly() {
        // 2026-10-17, course ③: 規則56(1) distance points by the 細則2 table, no speed points.
        val course3 = CompetitionTaskCourseData.courses[2]
        assertEquals(listOf("高林給水塔", "邑楽タワー", "千代田"), PartialLapDistance.scoredTurnpoints(course3))
        assertEquals(20.1, PartialLapDistance.distanceKm(listOf("高林給水塔", "邑楽タワー", "千代田", "管理ポイント"))!!, 0.0)
        assertEquals(14.6, PartialLapDistance.distanceKm(listOf("高林給水塔", "邑楽タワー"))!!, 0.0)
        assertEquals(null, PartialLapDistance.distanceKm(emptyList()))
        val s = LapScore.scores(
            listOf(
                lap("chuo", 26, 15, 1.00),
                partial("gakushuin", 20.1, 1.00),
                partial("kanchi", 14.6, 1.00),
                partial("meiji", 11.4, 0.95),
            ),
        )
        assertEquals(1000.0, s.getValue("chuo"), 0.05)
        assertEquals(380.7, s.getValue("gakushuin"), 0.1)
        assertEquals(276.5, s.getValue("kanchi"), 0.1)
        assertEquals(205.1, s.getValue("meiji"), 0.1)
    }

    @Test
    fun withNoLapTheLongestDistanceSetsTheHandicapReference() {
        val s = LapScore.scores(listOf(partial("a", 14.6, 0.90), partial("b", 11.4, 1.00)))
        assertEquals(500.0, s.getValue("a"), 1e-9) // Rd 1, Rv 0, Rh 0.90/0.90
        assertEquals(11.4 / 14.6 * 500 * 1.00 / 0.90, s.getValue("b"), 1e-9)
    }

    @Test
    fun lowGoalsAreDeductedOrLoseSpeedPoints() {
        // 細則7 別表(4): 280-330 m is 50 points off; under 280 m, Rv = 0 and its speed isn't the day's best.
        val s = LapScore.scores(
            listOf(
                LapScore.Lap("ok", 26.4, 30 * 60.0, 1.0, goalAltitudeM = 400.0),
                LapScore.Lap("low", 26.4, 30 * 60.0, 1.0, goalAltitudeM = 300.0),
                LapScore.Lap("tooLow", 26.4, 20 * 60.0, 1.0, goalAltitudeM = 250.0), // fastest, but no speed points
                LapScore.Lap("exactly330", 26.4, 30 * 60.0, 1.0, goalAltitudeM = 330.0),
            ),
        )
        assertEquals(1000.0, s.getValue("ok"), 1e-9)
        assertEquals(950.0, s.getValue("low"), 1e-9)
        assertEquals(500.0, s.getValue("tooLow"), 1e-9)
        assertEquals(1000.0, s.getValue("exactly330"), 1e-9)
        assertEquals(LapScore.GoalPenalty.NONE, LapScore.goalPenalty(null))
    }

    @Test
    fun toBeatTimesRescoreTheField() {
        // 2026-10-17's laps: 中央 (ASK23B 1.00) leads with 26:15 over 26.4 km.
        val laps = listOf(lap("chuo", 26, 15, 1.00), lap("keio", 27, 52, 0.90), partial("gakushuin", 20.1, 1.00))
        val chuoSeconds = 26 * 60 + 15.0
        // Same handicap as the leader: just be faster.
        assertEquals(chuoSeconds, LapScore.lapSecondsToBeat(laps, 26.4, 1.00)!!, 0.5)
        // A 0.90 glider that becomes the fastest makes itself the handicap reference, so 中央's
        // (500 + 500 v₀/v) × 1.00/0.90 must fall under its 1000: v > 1.25 v₀.
        assertEquals(chuoSeconds / 1.25, LapScore.lapSecondsToBeat(laps, 26.4, 0.90)!!, 0.5)
        // 0.95: (1 + v₀/v) / 0.95 < 2, so v > v₀ / (2 × 0.95 - 1).
        assertEquals(chuoSeconds * (2 * 0.95 - 1), LapScore.lapSecondsToBeat(laps, 26.4, 0.95)!!, 0.5)
        // Only partial laps so far: any completed lap beats them.
        assertTrue(LapScore.lapSecondsToBeat(listOf(partial("p", 20.1, 1.0)), 26.4, 1.0)!!.isInfinite())
        assertEquals(null, LapScore.lapSecondsToBeat(emptyList(), 26.4, 1.0))
    }
}
