package com.cobaltloom.loraviewer.data.model

import java.time.Instant

/**
 * One glider's current position, as returned by mapapi.php.
 */
data class GliderPosition(
    val imei: String,
    val index: String,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val source: PositionSource,
    val isDisconnected: Boolean,
    val positionDateTimeUtc: Instant?,
)
