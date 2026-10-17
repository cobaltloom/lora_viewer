package com.cobaltloom.loraviewer.data.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LapScoreTest {
    private fun lap(id: String, minutes: Int, seconds: Int, handicap: Double, km: Double = 26.4) =
        LapScore.Lap(id, km, minutes * 60.0 + seconds, handicap)

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
}
