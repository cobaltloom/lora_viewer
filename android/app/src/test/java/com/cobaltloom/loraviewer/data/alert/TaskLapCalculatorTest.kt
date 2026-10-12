package com.cobaltloom.loraviewer.data.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLapCalculatorTest {
    // ② 妻沼-千代田-高林給水塔-妻沼 (23.6 km)
    private val course = CompetitionTaskCourseData.courses[1]
    private val guideline: (TimedPoint) -> Double? = { 600.0 } // flat stand-in for the guideline table

    private fun at(c: Coordinate, distanceM: Double, bearing: Double, t: Long, alt: Double): TimedPoint {
        val p = destinationCoordinate(c.latitude, c.longitude, distanceM, bearing)
        return TimedPoint(t * 1000, p.latitude, p.longitude, alt)
    }

    private fun inSector(name: String, t: Long, alt: Double): TimedPoint =
        at(CompetitionTaskCourseData.turnpoints.getValue(name), 1000.0, CompetitionTaskCourseData.sectorBearing(course, name)!!, t, alt)

    private val goalMid = Coordinate(
        (CompetitionTaskCourseData.goalLinePointA.latitude + CompetitionTaskCourseData.goalLinePointB.latitude) / 2,
        (CompetitionTaskCourseData.goalLinePointA.longitude + CompetitionTaskCourseData.goalLinePointB.longitude) / 2,
    )
    private val st = CompetitionTaskCourseData.startPoint

    // Runway 14/32: the tow runs along ~315°, across the start line; returning from the
    // turnpoints (north-west of the line) the glider crosses the goal line heading ~135°.
    private fun startCrossing() = listOf(at(st, 200.0, 135.0, 0, 30.0), at(st, 200.0, 315.0, 10, 150.0))
    private fun goalCrossing(t: Long, alt: Double) = listOf(at(goalMid, 300.0, 315.0, t, alt), at(goalMid, 300.0, 135.0, t + 20, alt))

    @Test
    fun timesAFullLap() {
        val points = startCrossing() +
            inSector("千代田", 600, 700.0) +
            inSector("高林給水塔", 1200, 700.0) +
            goalCrossing(1760, 400.0)
        val s = TaskLapCalculator.evaluate(points, course, guideline)
        assertEquals(5_000.0, s.startMillis!!.toDouble(), 2.0) // halfway between the two tow points
        assertEquals(listOf("千代田", "高林給水塔"), s.passedTurnpoints.map { it.first })
        assertEquals(1_770_000.0, s.finishMillis!!.toDouble(), 2.0)
        assertEquals(1_765_000.0, s.lapMillis!!.toDouble(), 4.0)
        assertEquals(23.6 / (1765.0 / 3600), s.speedKmh(course.distanceKm)!!, 0.01)
        assertFalse(s.isGoalTooLow)
        assertEquals("29分25秒", formatLapDuration(s.lapMillis!!))
    }

    @Test
    fun turnpointBelowGuidelineOrOutOfOrderDoesNotCount() {
        val points = startCrossing() +
            inSector("高林給水塔", 300, 700.0) + // wrong order: doesn't count yet
            inSector("千代田", 600, 500.0) + // below guideline: 不通過
            goalCrossing(900, 400.0)
        val s = TaskLapCalculator.evaluate(points, course, guideline)
        assertEquals(0, s.passedTurnpoints.size)
        assertNull(s.finishMillis)
    }

    @Test
    fun goalCrossedBeforeAllTurnpointsIsIgnoredAndLowGoalIsFlagged() {
        val points = startCrossing() +
            goalCrossing(100, 400.0) + // crossing the goal line early doesn't finish
            inSector("千代田", 600, 700.0) +
            inSector("高林給水塔", 1200, 700.0) +
            goalCrossing(1800, 300.0)
        val s = TaskLapCalculator.evaluate(points, course, guideline)
        assertEquals(1_810_000.0, s.finishMillis!!.toDouble(), 2.0)
        assertTrue(s.isGoalTooLow)
    }

    @Test
    fun notStartedWithoutStartLineCrossing() {
        val s = TaskLapCalculator.evaluate(listOf(inSector("千代田", 600, 700.0)), course, guideline)
        assertNull(s.startMillis)
        assertEquals(2, s.totalTurnpoints)
    }
}
