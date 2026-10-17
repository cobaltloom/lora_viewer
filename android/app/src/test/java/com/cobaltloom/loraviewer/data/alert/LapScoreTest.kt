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
}
