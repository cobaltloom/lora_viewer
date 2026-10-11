package com.cobaltloom.loraviewer.data.model

import java.time.Instant

/**
 * One glider's current position, as returned by mapapi.php (or built from an OGN beacon).
 */
data class GliderPosition(
    /** The tracker's unique id: the LoRa tracker's IMEI, or the OGN APRS callsign (e.g. "FLRDDE626"). */
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
    /** An OGN aircraft whose owner asked not to be identified (DDB identified=N): shown by position
     * only, with no name, nickname or favorite that could single it out. */
    val isAnonymous: Boolean = false,
)
