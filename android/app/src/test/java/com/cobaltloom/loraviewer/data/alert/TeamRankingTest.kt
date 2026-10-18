package com.cobaltloom.loraviewer.data.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamRankingTest {
    private fun flight(id: String, glider: String, minutes: Int, handicap: Double = 1.0, km: Double = 26.5) =
        TeamRanking.Flight(LapScore.Lap(id, km, minutes * 60.0, handicap), glider)

    private val standings = TeamStandings(
        competition = "test",
        teams = listOf(
            TeamStandings.Team("A", listOf("a1"), total = 1000.0, national = 600.0),
            TeamStandings.Team("B", listOf("b1", "b2"), total = 900.0, national = 700.0),
            TeamStandings.Team("C", listOf("c1"), total = 100.0),
        ),
    )

    @Test
    fun officialPlusTodaysBestThreeFlights() {
        val flights = listOf(
            flight("1", "a1", 30), // fastest: 1000
            flight("2", "b1", 40), // 500 + 500 × 30/40 = 875
            flight("3", "b2", 60), // 750
            flight("4", "b1", 45), // 833.3 → 833
            flight("5", "b2", 120), // 625, 4th best of B: not counted
            flight("6", "x9", 20), // not in any team, but the day's fastest: rescoring everyone
        )
        val rows = TeamRanking.rows(standings, flights, includeToday = true)
        val b = rows.first { it.name == "B" }
        // With x9's 20:00 the fastest: B's 40:00 = 500 + 500 × 20/40 = 750, 45:00 = 722, 60:00 = 666.
        assertEquals(750.0 + 722.0 + 666.0, b.today, 0.0)
        assertEquals(750.0, b.nationalToday, 0.0)
        assertEquals(4, b.flightsToday)
        assertEquals(listOf("B", "A", "C"), rows.map { it.name })
        assertEquals(listOf(1, 2, 3), rows.map { it.rank })
        // Official totals that already include today: no provisional part.
        val official = TeamRanking.rows(standings, flights, includeToday = false)
        assertEquals(listOf("A", "B", "C"), official.map { it.name })
        assertEquals(0.0, official.first().today, 0.0)
    }

    @Test
    fun lapNeededToReachARank() {
        val flights = listOf(flight("1", "a1", 30))
        // B (900) passes A (1000 + today's 1000 at 30:00) only by flying faster than A: then B's lap is
        // 1000 and A's becomes floor(500 + 500 × T/1800), so B is at least level (rank 1, shared) while
        // that's at most 900 → T < 1443.6 s.
        assertEquals(1443.6, TeamRanking.lapSecondsToReach(standings, flights, "B", "b1", 1.0, 26.5, 1)!!, 0.5)
        // C (100) can't catch A whatever it flies: A keeps at least 1500.
        assertNull(TeamRanking.lapSecondsToReach(standings, flights, "C", "c1", 1.0, 26.5, 1))
        // B is already second with no flight today.
        assertTrue(TeamRanking.lapSecondsToReach(standings, flights, "B", "b1", 1.0, 26.5, 2)!!.isInfinite())
        assertNull(TeamRanking.lapSecondsToReach(standings, flights, "nobody", "b1", 1.0, 26.5, 1))
    }
}
