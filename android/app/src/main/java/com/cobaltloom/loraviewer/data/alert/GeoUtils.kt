package com.cobaltloom.loraviewer.data.alert

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** A plain lat/lon pair, independent of any map SDK, for domain-layer math. */
@Serializable
data class Coordinate(val latitude: Double, val longitude: Double)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance between two lat/lon points, in meters. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_M * c
}

/** Initial great-circle bearing (degrees, 0 = north, clockwise) from point 1 toward point 2. */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLon = Math.toRadians(lon2 - lon1)
    val y = sin(deltaLon) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
    val bearingRad = atan2(y, x)
    return Math.toDegrees(bearingRad) % 360.0
}

/** The point [distanceMeters] away from (lat, lon), along the great circle in direction [bearingDegrees] (0 = north, clockwise). */
fun destinationCoordinate(lat: Double, lon: Double, distanceMeters: Double, bearingDegrees: Double): Coordinate {
    val bearingRad = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lon)
    val angularDistance = distanceMeters / EARTH_RADIUS_M

    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad))
    val lon2 = lon1 + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )
    return Coordinate(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

/**
 * A point on the circle of [radiusMeters] around (lat, lon), on the side facing (targetLat,
 * targetLon). Used to keep a label attached to a map circle on-screen: passing the visible map's
 * current center as the target slides the label around the circle as the map is panned or zoomed,
 * instead of pinning it to one fixed compass point that can scroll out of view.
 */
fun pointOnCircle(lat: Double, lon: Double, radiusMeters: Double, targetLat: Double, targetLon: Double): Coordinate {
    val bearing = bearingDegrees(lat, lon, targetLat, targetLon)
    return destinationCoordinate(lat, lon, radiusMeters, bearing)
}
