package com.cobaltloom.loraviewer.data.alert

import kotlin.math.cos

/** One timed position of a glider's current flight, used to time its lap. */
data class TimedPoint(val timeMillis: Long, val lat: Double, val lon: Double, val altitudeM: Double)

/**
 * How far a glider has got around the selected course, per the 関東学生グライダー競技会 rules
 * (競技規則 Ver.3-2026-09-28 35-38, 52; 細則 Ver.2026-09-28 2-3). A reference only: official
 * timing uses each glider's 1-second IGC logger in SeeYou, while this works from LoRa/OGN fixes
 * a few seconds apart.
 */
data class TaskLapStatus(
    /** When the glider crossed the start line, or null if it hasn't yet. */
    val startMillis: Long?,
    val startAltitudeM: Double? = null,
    /** Turnpoints reached in course order, with the time each was reached. */
    val passedTurnpoints: List<Pair<String, Long>>,
    val totalTurnpoints: Int,
    /** When it crossed the goal line after every turnpoint, or null if it hasn't. */
    val finishMillis: Long?,
    val finishAltitudeM: Double?,
) {
    val lapMillis: Long? get() = if (startMillis != null && finishMillis != null) finishMillis - startMillis else null

    fun speedKmh(courseDistanceKm: Double): Double? = lapMillis?.takeIf { it > 0 }?.let { courseDistanceKm / (it / 3_600_000.0) }

    /** 細則/規則50(5): the goal line must be crossed at 330 m MSL or above. */
    val isGoalTooLow: Boolean get() = finishAltitudeM != null && finishAltitudeM < TaskLapCalculator.MIN_GOAL_ALTITUDE_M
}

object TaskLapCalculator {
    /** 規則50(5): goal line crossing altitude (MSL). */
    const val MIN_GOAL_ALTITUDE_M = 330.0

    /** 細則3(2): the start line is 300 m wide, centered on the ST point. */
    private const val START_LINE_HALF_WIDTH_M = 150.0

    /**
     * Times [points] (one flight, oldest first) around [course]: the start line crossing, then each
     * interior turnpoint in order - inside its 90° / 2 km sector and not below the safety guideline
     * altitude at that spot ([requiredAltitudeM], rule 37(1)②) - then the first goal line crossing
     * after the last turnpoint.
     */
    fun evaluate(
        points: List<TimedPoint>,
        course: CompetitionTaskCourse,
        requiredAltitudeM: (TimedPoint) -> Double?,
    ): TaskLapStatus {
        val turnpoints = course.turnpointNames.drop(1).dropLast(1)
        val start = firstCrossing(points, 0, startLineA, startLineB)
        if (start == null) {
            return TaskLapStatus(null, null, emptyList(), turnpoints.size, null, null)
        }
        val passed = mutableListOf<Pair<String, Long>>()
        var index = start.afterIndex
        while (index < points.size && passed.size < turnpoints.size) {
            val p = points[index]
            val name = turnpoints[passed.size]
            if (isInSector(p, course, name) && p.altitudeM >= (requiredAltitudeM(p) ?: Double.NEGATIVE_INFINITY)) {
                passed += name to p.timeMillis
            }
            index++
        }
        if (passed.size < turnpoints.size) {
            return TaskLapStatus(start.timeMillis, start.altitudeM, passed, turnpoints.size, null, null)
        }
        val finish = firstCrossing(points, index - 1, goalLineA, goalLineB)
        return TaskLapStatus(start.timeMillis, start.altitudeM, passed, turnpoints.size, finish?.timeMillis, finish?.altitudeM)
    }

    private fun isInSector(p: TimedPoint, course: CompetitionTaskCourse, name: String): Boolean {
        val tp = CompetitionTaskCourseData.turnpoints[name] ?: return false
        val bisector = CompetitionTaskCourseData.sectorBearing(course, name) ?: return false
        return distanceMeters(tp.latitude, tp.longitude, p.lat, p.lon) <= CompetitionTaskCourseData.TURNPOINT_RADIUS_KM * 1000 &&
            CompetitionTaskCourseData.isBearing(bearingDegrees(tp.latitude, tp.longitude, p.lat, p.lon), bisector)
    }

    // ---- line crossings, on a local flat projection around the field (fine at this scale)

    private val origin = CompetitionTaskCourseData.startPoint
    private val metersPerDegLat = 110_574.0
    private val metersPerDegLon = 111_320.0 * cos(Math.toRadians(origin.latitude))

    private fun x(lon: Double) = (lon - origin.longitude) * metersPerDegLon
    private fun y(lat: Double) = (lat - origin.latitude) * metersPerDegLat

    private val goalLineA = Pair(x(CompetitionTaskCourseData.goalLinePointA.longitude), y(CompetitionTaskCourseData.goalLinePointA.latitude))
    private val goalLineB = Pair(x(CompetitionTaskCourseData.goalLinePointB.longitude), y(CompetitionTaskCourseData.goalLinePointB.latitude))

    /** The start line crosses the runway at right angles, as the goal line does (細則3), so it runs
     * parallel to the goal line through the ST point. */
    private val startLineA: Pair<Double, Double>
    private val startLineB: Pair<Double, Double>

    init {
        val dx = goalLineB.first - goalLineA.first
        val dy = goalLineB.second - goalLineA.second
        val len = kotlin.math.hypot(dx, dy)
        val ux = dx / len * START_LINE_HALF_WIDTH_M
        val uy = dy / len * START_LINE_HALF_WIDTH_M
        startLineA = Pair(-ux, -uy)
        startLineB = Pair(ux, uy)
    }

    private class Crossing(val timeMillis: Long, val altitudeM: Double, val afterIndex: Int)

    /** The first track segment from [fromIndex] on that crosses segment a-b, with time and altitude
     * interpolated to the crossing point. */
    private fun firstCrossing(points: List<TimedPoint>, fromIndex: Int, a: Pair<Double, Double>, b: Pair<Double, Double>): Crossing? {
        for (i in maxOf(fromIndex, 0) until points.size - 1) {
            val p = points[i]
            val q = points[i + 1]
            val t = segmentIntersection(x(p.lon), y(p.lat), x(q.lon), y(q.lat), a, b) ?: continue
            return Crossing(
                timeMillis = p.timeMillis + ((q.timeMillis - p.timeMillis) * t).toLong(),
                altitudeM = p.altitudeM + (q.altitudeM - p.altitudeM) * t,
                afterIndex = i + 1,
            )
        }
        return null
    }

    /** Where along p→q (0..1) it crosses a-b, or null if it doesn't. */
    private fun segmentIntersection(px: Double, py: Double, qx: Double, qy: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double? {
        val rx = qx - px
        val ry = qy - py
        val sx = b.first - a.first
        val sy = b.second - a.second
        val denom = rx * sy - ry * sx
        if (denom == 0.0) return null
        val t = ((a.first - px) * sy - (a.second - py) * sx) / denom
        val u = ((a.first - px) * ry - (a.second - py) * rx) / denom
        return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
    }
}

/** One line for the detail card, e.g. "所要 29分25秒・平均 48.1km/h(参考)". */
fun lapSummary(status: TaskLapStatus, courseDistanceKm: Double, nowMillis: Long, formatTime: (Long) -> String): String {
    val start = status.startMillis ?: return "周回: スタート前"
    val lap = status.lapMillis
    if (lap != null) {
        val speed = status.speedKmh(courseDistanceKm)?.let { "・平均 %.1fkm/h".format(it) }.orEmpty()
        val low = if (status.isGoalTooLow) "・ゴール高度 ${status.finishAltitudeM!!.toInt()}m(330m未満)" else ""
        return "周回: 所要 ${formatLapDuration(lap)}$speed$low(参考)"
    }
    return "周回: スタート ${formatTime(start)}・旋回点 ${status.passedTurnpoints.size}/${status.totalTurnpoints}・経過 ${formatLapDuration(nowMillis - start).substringBefore("分")}分(参考)"
}

/** "29分27秒" */
fun formatLapDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return "${totalSeconds / 60}分${totalSeconds % 60}秒"
}
