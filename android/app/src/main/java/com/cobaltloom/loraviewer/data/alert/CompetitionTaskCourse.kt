package com.cobaltloom.loraviewer.data.alert

/**
 * One of JSAL's published turnpoint courses for 妻沼滑空場 competitions (Ver.2026-01-26): an
 * ordered loop of named turnpoints, starting and ending at the airfield itself.
 */
data class CompetitionTaskCourse(val name: String, val distanceKm: Double, val turnpointNames: List<String>)

/**
 * The named turnpoints, reference points, and published task courses from JSAL's own document.
 * Like [CompetitionAltitudeGuideline], this is a fixed, published reference - shown on the map as
 * a visual aid only, not as an authoritative task file (start/goal line orientation in particular
 * is set per-task by the organizers and isn't reproduced here).
 */
object CompetitionTaskCourseData {
    private fun dms(latD: Double, latM: Double, latS: Double, lonD: Double, lonM: Double, lonS: Double) =
        Coordinate(
            latitude = latD + latM / 60 + latS / 3600,
            longitude = lonD + lonM / 60 + lonS / 3600,
        )

    /** Named turnpoints, keyed by name. "妻沼" is the airfield itself - every course starts and ends there. */
    val turnpoints: Map<String, Coordinate> = mapOf(
        "妻沼" to CompetitionAltitudeGuideline.referenceCoordinate,
        "高林給水塔" to dms(36.0, 14.0, 51.0, 139.0, 22.0, 7.0),
        "千代田" to dms(36.0, 12.0, 26.0, 139.0, 29.0, 13.0),
        "邑楽タワー" to dms(36.0, 15.0, 11.0, 139.0, 27.0, 45.0),
        "管理ポイント" to dms(36.0, 12.0, 29.0, 139.0, 25.0, 21.0),
    )

    /** Order to draw turnpoint markers in (excludes "妻沼", already shown as the airfield/reference marker elsewhere). */
    val turnpointDisplayOrder = listOf("高林給水塔", "千代田", "邑楽タワー", "管理ポイント")

    /**
     * Turnpoints eligible for passage notifications/history - excludes "管理ポイント", which is a
     * transit checkpoint rather than an actual scored turn, so passing through it isn't worth
     * notifying about.
     */
    val notifiableTurnpointNames = turnpointDisplayOrder.filter { it != "管理ポイント" }

    /**
     * Radius of a turnpoint's sector, per JSAL rule 43 (and the same value for 管理ポイント's own
     * transit sector): a real sector is a directional 90° wedge, simplified here to a full circle
     * for both map visualization and turnpoint-passage detection.
     */
    const val TURNPOINT_RADIUS_KM = 2.0
    const val MANAGEMENT_POINT_RADIUS_KM = TURNPOINT_RADIUS_KM

    val courses: List<CompetitionTaskCourse> = listOf(
        CompetitionTaskCourse("① 妻沼-高林給水塔-千代田-(管理ポイント)-妻沼", 24.0, listOf("妻沼", "高林給水塔", "千代田", "管理ポイント", "妻沼")),
        CompetitionTaskCourse("② 妻沼-千代田-高林給水塔-妻沼", 23.6, listOf("妻沼", "千代田", "高林給水塔", "妻沼")),
        CompetitionTaskCourse("③ 妻沼-高林給水塔-邑楽タワー-千代田-(管理ポイント)-妻沼", 26.4, listOf("妻沼", "高林給水塔", "邑楽タワー", "千代田", "管理ポイント", "妻沼")),
        CompetitionTaskCourse("④ 妻沼-千代田-邑楽タワー-高林給水塔-妻沼", 26.1, listOf("妻沼", "千代田", "邑楽タワー", "高林給水塔", "妻沼")),
    )

    /** The goal line: a fixed segment between two published points. */
    val goalLinePointA = dms(36.0, 12.0, 47.0, 139.0, 24.0, 57.0)
    val goalLinePointB = dms(36.0, 12.0, 24.0, 139.0, 24.0, 29.0)

    /**
     * The start line's center point. Its actual orientation (~perpendicular to the winch tow
     * path, 300m wide) is set per-task by the organizers, so only the center point is shown, not
     * a drawn line.
     */
    val startPoint = dms(36.0, 12.0, 48.0, 139.0, 24.0, 59.0)

    fun coordinates(course: CompetitionTaskCourse): List<Coordinate> = course.turnpointNames.mapNotNull { turnpoints[it] }

    /**
     * The bisector heading (degrees, 0 = north, clockwise) of the 90° turnpoint sector at
     * [turnpointName] within [course], per JSAL rule 43: the bisector of the incoming leg's
     * direction (from the previous point to the turnpoint) and the outgoing leg's direction (from
     * the turnpoint to the next point), each as a ray from the turnpoint. Null if [turnpointName]
     * isn't an interior point of [course] (not part of it, or it's the course's start/finish
     * point), in which case no course-specific sector orientation is defined.
     */
    fun sectorBearing(course: CompetitionTaskCourse, turnpointName: String): Double? {
        val index = course.turnpointNames.indexOf(turnpointName)
        if (index <= 0 || index >= course.turnpointNames.size - 1) return null
        val previous = turnpoints[course.turnpointNames[index - 1]] ?: return null
        val current = turnpoints[turnpointName] ?: return null
        val next = turnpoints[course.turnpointNames[index + 1]] ?: return null
        val bearingIn = bearingDegrees(previous.latitude, previous.longitude, current.latitude, current.longitude)
        val bearingOut = bearingDegrees(current.latitude, current.longitude, next.latitude, next.longitude)
        return normalizedDegrees(bearingIn + shortestAngleDifference(bearingIn, bearingOut) / 2)
    }

    /** Whether [bearingDegrees] (measured from the turnpoint) falls within the 90° sector centered on [bisectorDegrees] (45° either side). */
    fun isBearing(bearingDegrees: Double, bisectorDegrees: Double): Boolean =
        kotlin.math.abs(shortestAngleDifference(bisectorDegrees, bearingDegrees)) <= 45
}

private fun normalizedDegrees(degrees: Double): Double {
    val mod = degrees % 360
    return if (mod < 0) mod + 360 else mod
}

private fun shortestAngleDifference(from: Double, to: Double): Double {
    val diff = normalizedDegrees(to - from)
    return if (diff > 180) diff - 360 else diff
}
