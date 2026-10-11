package com.cobaltloom.loraviewer.data.remote

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** One aircraft position beacon from the OGN APRS stream. */
data class OgnBeacon(
    /** The APRS source callsign, e.g. "FLRDDE626" - used as the glider's id. */
    val callsign: String,
    /** The 6-hex-digit device address, as keyed in the OGN device database. */
    val address: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val timestamp: Instant,
    val aircraftType: Int,
    val addressType: Int,
    val isStealth: Boolean,
    val isNoTracking: Boolean,
    /** The APRS destination ("OGFLR", "OGNTRK", "OGADSB", ...), i.e. which radio system relayed it. */
    val destination: String,
)

/**
 * Parses OGN-flavoured APRS aircraft beacons
 * (http://wiki.glidernet.org/wiki:ogn-flavoured-aprs), e.g.
 * `FLRDDE626>APRS,qAS,EGHL:/074548h5111.32N/00102.04W'086/007/A=000607 !W52! id0ADDE626 -019fpm ...`
 */
object OgnBeaconParser {
    private val beaconPattern = Regex(
        """^([A-Za-z0-9]{3,9})>([A-Z0-9]+)[^:]*:[/@](\d{2})(\d{2})(\d{2})h""" +
            """(\d{2})(\d{2}\.\d{2})([NS]).(\d{3})(\d{2}\.\d{2})([EW]).""" +
            """(?:\d{3}/\d{3})?/A=(-?\d{5,6})(.*)$""",
    )
    private val precisionPattern = Regex("""!W(\d)(\d)!""")
    private val idPattern = Regex("""\bid([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})\b""")

    /** Address type values from the id field's low 2 bits. */
    const val ADDRESS_TYPE_RANDOM = 0

    /** Aircraft types we never show: 9 = jet/airliner, 15 = static object. */
    private val excludedAircraftTypes = setOf(9, 15)

    /** Returns null for anything that isn't an aircraft position beacon (receiver beacons, comments). */
    fun parse(line: String, now: Instant): OgnBeacon? {
        if (line.startsWith("#")) return null
        val m = beaconPattern.find(line) ?: return null
        val g = m.groupValues
        val rest = g[13]
        val id = idPattern.find(rest) ?: return null // receiver beacons have no id field
        val flags = id.groupValues[1].toInt(16)

        val precision = precisionPattern.find(rest)
        val latExtra = precision?.groupValues?.get(1)?.toDouble()?.div(1000) ?: 0.0
        val lonExtra = precision?.groupValues?.get(2)?.toDouble()?.div(1000) ?: 0.0
        var lat = g[6].toDouble() + (g[7].toDouble() + latExtra) / 60
        if (g[8] == "S") lat = -lat
        var lon = g[9].toDouble() + (g[10].toDouble() + lonExtra) / 60
        if (g[11] == "W") lon = -lon

        return OgnBeacon(
            callsign = g[1],
            address = id.groupValues[2].uppercase(),
            lat = lat,
            lon = lon,
            altitudeM = g[12].toDouble() * FEET_TO_M,
            timestamp = timestamp(g[3].toInt(), g[4].toInt(), g[5].toInt(), now),
            aircraftType = (flags shr 2) and 0x0F,
            addressType = flags and 0x03,
            isStealth = flags and 0x80 != 0,
            isNoTracking = flags and 0x40 != 0,
            destination = g[2],
        )
    }

    /** Whether a beacon may be shown at all, before consulting the device database. */
    fun isDisplayable(beacon: OgnBeacon): Boolean =
        !beacon.isStealth &&
            !beacon.isNoTracking &&
            beacon.addressType != ADDRESS_TYPE_RANDOM &&
            beacon.aircraftType !in excludedAircraftTypes &&
            // ADS-B relayed by OGN receivers is airliner traffic, not what this app is for.
            !beacon.destination.startsWith("OGADSB")

    /** Beacons carry only hh:mm:ss UTC; pick the date that puts it nearest to [now]. */
    private fun timestamp(h: Int, m: Int, s: Int, now: Instant): Instant {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        var t = today.atTime(LocalTime.of(h % 24, m % 60, s % 60)).toInstant(ZoneOffset.UTC)
        if (Duration.between(now, t) > Duration.ofHours(12)) t = t.minus(Duration.ofDays(1))
        else if (Duration.between(t, now) > Duration.ofHours(12)) t = t.plus(Duration.ofDays(1))
        return t
    }

    private const val FEET_TO_M = 0.3048
}
