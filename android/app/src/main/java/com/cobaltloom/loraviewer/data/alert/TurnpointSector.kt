package com.cobaltloom.loraviewer.data.alert

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Turnpoint sector entry between two fixes. LoRa fixes arrive ~15-30 s apart, so a glider that
 * only clips a sector's edge often has no fix inside it at all; checking the straight line between
 * consecutive fixes catches that, and times the entry where the line enters rather than at the next
 * fix. Kept in step with the iOS app's TurnpointSector (CompetitionTaskCourse.swift).
 */
object TurnpointSector {
    /** Fixes further apart than this aren't joined: LoRa drops a fix now and then (gaps of 1-2 min
     * happen), but much longer means lost reception, and a straight line across it could invent a
     * passage. Such a fix is checked on its own. */
    const val MAX_JOIN_GAP_MILLIS = 120_000L
    private const val SAMPLE_SPACING_M = 25.0
    private const val MAX_SAMPLES = 400

    fun isInSector(lat: Double, lon: Double, course: CompetitionTaskCourse, name: String): Boolean {
        val tp = CompetitionTaskCourseData.turnpoints[name] ?: return false
        val bisector = CompetitionTaskCourseData.sectorBearing(course, name) ?: return false
        return distanceMeters(tp.latitude, tp.longitude, lat, lon) <= CompetitionTaskCourseData.TURNPOINT_RADIUS_KM * 1000 &&
            CompetitionTaskCourseData.isBearing(bearingDegrees(tp.latitude, tp.longitude, lat, lon), bisector)
    }

    /**
     * The first point along the fix a → fix b, as a fraction 0..1, that is inside [name]'s sector
     * of [course] and for which [accept] also holds, or null. Without [a], or with the two more
     * than [MAX_JOIN_GAP_MILLIS] apart, only b itself is checked (fraction 1).
     */
    fun firstEntry(
        a: TimedPoint?,
        b: TimedPoint,
        course: CompetitionTaskCourse,
        name: String,
        accept: (Double) -> Boolean = { true },
    ): Double? {
        if (a == null || b.timeMillis - a.timeMillis > MAX_JOIN_GAP_MILLIS || b.timeMillis < a.timeMillis) {
            return if (isInSector(b.lat, b.lon, course, name) && accept(1.0)) 1.0 else null
        }
        val tp = CompetitionTaskCourseData.turnpoints[name] ?: return null
        // Flat projection around the turnpoint: skip the segment unless it comes within the radius.
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(tp.latitude))
        val ax = (a.lon - tp.longitude) * mPerDegLon
        val ay = (a.lat - tp.latitude) * 110_574.0
        val bx = (b.lon - tp.longitude) * mPerDegLon
        val by = (b.lat - tp.latitude) * 110_574.0
        val length = hypot(bx - ax, by - ay)
        val closest = if (length == 0.0) 0.0 else (-(ax * (bx - ax) + ay * (by - ay)) / (length * length)).coerceIn(0.0, 1.0)
        if (hypot(ax + (bx - ax) * closest, ay + (by - ay) * closest) > CompetitionTaskCourseData.TURNPOINT_RADIUS_KM * 1000 * 1.05) return null
        val samples = ceil(length / SAMPLE_SPACING_M).toInt().coerceIn(1, MAX_SAMPLES)
        for (i in 0..samples) {
            val t = i.toDouble() / samples
            if (isInSector(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, course, name) && accept(t)) return t
        }
        return null
    }

    /** The point a fraction [t] of the way from [a] to [b], time and altitude included. */
    fun interpolate(a: TimedPoint, b: TimedPoint, t: Double) = TimedPoint(
        timeMillis = a.timeMillis + ((b.timeMillis - a.timeMillis) * t).toLong(),
        lat = a.lat + (b.lat - a.lat) * t,
        lon = a.lon + (b.lon - a.lon) * t,
        altitudeM = a.altitudeM + (b.altitudeM - a.altitudeM) * t,
    )
}
