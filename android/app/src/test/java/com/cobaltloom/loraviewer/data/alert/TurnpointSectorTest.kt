package com.cobaltloom.loraviewer.data.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TurnpointSectorTest {
    // ② 妻沼-千代田-高林給水塔-妻沼
    private val course = CompetitionTaskCourseData.courses[1]
    private val tp = CompetitionTaskCourseData.turnpoints.getValue("千代田")
    private val bisector = CompetitionTaskCourseData.sectorBearing(course, "千代田")!!

    private fun fix(distanceM: Double, bearing: Double, t: Long, alt: Double = 700.0): TimedPoint {
        val p = destinationCoordinate(tp.latitude, tp.longitude, distanceM, bearing)
        return TimedPoint(t * 1000, p.latitude, p.longitude, alt)
    }

    // Both fixes 2.3 km out (outside the 2 km sector), 60° apart: the line between them passes
    // ~1.99 km from the turnpoint, inside the sector, around its midpoint.
    private val before = fix(2300.0, bisector - 30, 0)
    private val after = fix(2300.0, bisector + 30, 15)

    @Test
    fun clippedSectorBetweenSparseFixesIsFound() {
        assertNull(TurnpointSector.firstEntry(null, after, course, "千代田"))
        val t = TurnpointSector.firstEntry(before, after, course, "千代田")
        assertNotNull(t)
        assertEquals(0.5, t!!, 0.1)
        assertEquals(7_500.0, TurnpointSector.interpolate(before, after, t).timeMillis.toDouble(), 1_500.0)
    }

    @Test
    fun fixesTooFarApartAreNotJoined() {
        val late = fix(2300.0, bisector + 30, 150)
        assertNull(TurnpointSector.firstEntry(before, late, course, "千代田"))
    }

    @Test
    fun entryIsTimedWhereTheLineEnters() {
        val outside = fix(3000.0, bisector, 0)
        val inside = fix(1000.0, bisector, 20)
        val t = TurnpointSector.firstEntry(outside, inside, course, "千代田")!!
        assertEquals(0.5, t, 0.03) // 2 km out = halfway from 3 km to 1 km
    }

    @Test
    fun lapCountsAClippedTurnpoint() {
        val st = CompetitionTaskCourseData.startPoint
        fun at(c: Coordinate, d: Double, b: Double, t: Long, alt: Double): TimedPoint {
            val p = destinationCoordinate(c.latitude, c.longitude, d, b)
            return TimedPoint(t * 1000, p.latitude, p.longitude, alt)
        }
        val goalMid = Coordinate(
            (CompetitionTaskCourseData.goalLinePointA.latitude + CompetitionTaskCourseData.goalLinePointB.latitude) / 2,
            (CompetitionTaskCourseData.goalLinePointA.longitude + CompetitionTaskCourseData.goalLinePointB.longitude) / 2,
        )
        val takabayashi = CompetitionTaskCourseData.turnpoints.getValue("高林給水塔")
        val points = listOf(at(st, 200.0, 135.0, 0, 30.0), at(st, 200.0, 315.0, 10, 150.0)) +
            listOf(fix(2300.0, bisector - 30, 600), fix(2300.0, bisector + 30, 615)) +
            at(takabayashi, 1000.0, CompetitionTaskCourseData.sectorBearing(course, "高林給水塔")!!, 1200, 700.0) +
            listOf(at(goalMid, 300.0, 315.0, 1760, 400.0), at(goalMid, 300.0, 135.0, 1780, 400.0))
        val s = TaskLapCalculator.evaluate(points, course) { 600.0 }
        assertEquals(listOf("千代田", "高林給水塔"), s.passedTurnpoints.map { it.first })
        assertEquals(607_500.0, s.passedTurnpoints[0].second.toDouble(), 1_500.0)
        assertNotNull(s.finishMillis)
    }
}
