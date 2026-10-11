package com.cobaltloom.loraviewer.data.model

import java.time.Instant

/**
 * One glider's current position, as returned by mapapi.php (or built from OGN Japan data).
 */
data class GliderPosition(
    /** The tracker's unique id: the LoRa tracker's IMEI, or the OGN device id (e.g. "FLR3F1A2B"). */
    val imei: String,
    /** Short label drawn inside the map marker. */
    val index: String,
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val source: PositionSource,
    val isDisconnected: Boolean,
    val positionDateTimeUtc: Instant?,
    /** Name supplied by the source itself (OGN registration/competition no.). LoRa positions leave
     * this null and are named via the site config's name_master_displayed. */
    val sourceName: String? = null,
)
