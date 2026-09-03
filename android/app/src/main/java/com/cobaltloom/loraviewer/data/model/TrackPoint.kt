package com.cobaltloom.loraviewer.data.model

import java.time.Instant

/** One point of a glider's flight history, as returned by query_position_log.php. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val source: PositionSource,
    val createDateTimeUtc: Instant?,
)

data class TrackLogDevice(val positionCount: Int, val positionLog: List<TrackPoint>)
