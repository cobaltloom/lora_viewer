package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.data.model.PositionSource
import com.cobaltloom.loraviewer.data.remote.OgnApiClient.Companion.toGliderPosition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OgnApiClientTest {
    private val now = Instant.parse("2026-10-12T03:00:30Z")

    @Test
    fun parsesWikiExampleBeacon() {
        val b = OgnBeaconParser.parse(
            "FLRDDE626>APRS,qAS,EGHL:/074548h5111.32N/00102.04W'086/007/A=000607 id0ADDE626 -019fpm +0.0rot 5.5dB 3e -4.3kHz",
            Instant.parse("2026-10-12T07:46:00Z"),
        )!!
        assertEquals("FLRDDE626", b.callsign)
        assertEquals("DDE626", b.address)
        assertEquals(51 + 11.32 / 60, b.lat, 1e-9)
        assertEquals(-(1 + 2.04 / 60), b.lon, 1e-9)
        assertEquals(607 * 0.3048, b.altitudeM, 1e-9)
        assertEquals(Instant.parse("2026-10-12T07:45:48Z"), b.timestamp)
        // 0x0A = 0b00001010: not stealth, tracking allowed, aircraft type 2 (tow plane), FLARM address.
        assertEquals(2, b.aircraftType)
        assertEquals(2, b.addressType)
        assertFalse(b.isStealth)
        assertFalse(b.isNoTracking)
        assertTrue(OgnBeaconParser.isDisplayable(b))
    }

    @Test
    fun appliesPrecisionEnhancementAndSouthEast() {
        val b = OgnBeaconParser.parse(
            "OGN123456>OGNTRK,qAS,Menuma:/030020h3612.68S/13925.13E'000/000/A=-00012 !W52! id07123456 +000fpm",
            now,
        )!!
        assertEquals(-(36 + 12.685 / 60), b.lat, 1e-9)
        assertEquals(139 + 25.132 / 60, b.lon, 1e-9)
        assertEquals(-12 * 0.3048, b.altitudeM, 1e-9)
        assertEquals(1, b.aircraftType) // glider
        assertEquals(3, b.addressType) // OGN tracker
    }

    @Test
    fun ignoresReceiverBeaconsAndComments() {
        assertNull(OgnBeaconParser.parse("Menuma>OGNSDR,TCPIP*,qAC,GLIDERN2:/141709h3612.68NI13925.13E&/A=000075", now))
        assertNull(OgnBeaconParser.parse("# aprsc 2.1.20-gf.5-g0178a1b", now))
    }

    @Test
    fun excludesPrivacyFlaggedRandomAndAirlinerTraffic() {
        fun beacon(idFlags: String, dest: String = "OGFLR") = OgnBeaconParser.parse(
            "FLR111111>$dest,qAS,Menuma:/030020h3612.68N/13925.13E'000/000/A=001000 id${idFlags}111111", now,
        )!!
        assertTrue(OgnBeaconParser.isDisplayable(beacon("06"))) // glider, FLARM
        assertFalse(OgnBeaconParser.isDisplayable(beacon("86"))) // stealth
        assertFalse(OgnBeaconParser.isDisplayable(beacon("46"))) // no-tracking
        assertFalse(OgnBeaconParser.isDisplayable(beacon("04"))) // random address
        assertFalse(OgnBeaconParser.isDisplayable(beacon("25"))) // type 9 = jet
        assertFalse(OgnBeaconParser.isDisplayable(beacon("06", dest = "OGADSB")))
    }

    @Test
    fun timestampJustBeforeMidnightBelongsToPreviousDay() {
        val b = OgnBeaconParser.parse(
            "FLR111111>OGFLR,qAS,Menuma:/235959h3612.68N/13925.13E'000/000/A=001000 id06111111",
            Instant.parse("2026-10-12T00:00:05Z"),
        )!!
        assertEquals(Instant.parse("2026-10-11T23:59:59Z"), b.timestamp)
    }

    @Test
    fun namesFromDeviceDatabase() {
        val db = OgnDeviceDatabase.parse(
            """{"devices":[
              {"device_type":"F","device_id":"111111","registration":"JA21MA","cn":"MA","tracked":"Y","identified":"Y","aircraft_type":1},
              {"device_type":"F","device_id":"222222","registration":"JA2345","cn":"","tracked":"Y","identified":"Y","aircraft_type":1},
              {"device_type":"F","device_id":"333333","registration":"JA9999","cn":"","tracked":"N","identified":"Y","aircraft_type":1},
              {"device_type":"F","device_id":"444444","registration":"JA8888","cn":"ZZ","tracked":"Y","identified":"N","aircraft_type":1}
            ]}""",
        )
        assertFalse(db.getValue("333333").isTracked)
        val b = OgnBeaconParser.parse(
            "FLR111111>OGFLR,qAS,Menuma:/030020h3612.68N/13925.13E'000/000/A=001000 id06111111", now,
        )!!
        val p = b.toGliderPosition(db["111111"])
        assertEquals("MA", p.index)
        assertEquals("MA JA21MA", p.sourceName)
        assertEquals("FLR111111", p.imei)
        assertEquals(PositionSource.OGN, p.source)
        assertEquals("2345", b.toGliderPosition(db["222222"]).index)
        // Not identified: position only, no registration/competition no. anywhere.
        val anonymous = b.toGliderPosition(db["444444"])
        assertTrue(anonymous.isAnonymous)
        assertEquals("?", anonymous.index)
        assertEquals(OgnApiClient.ANONYMOUS_NAME, anonymous.sourceName)
        assertEquals(36 + 12.68 / 60, anonymous.lat, 1e-9)
        assertFalse(p.isAnonymous)
        val unknown = b.toGliderPosition(null)
        assertEquals("1111", unknown.index)
        assertEquals("FLR111111", unknown.sourceName)
    }

    @Test
    fun flarmNetKeepsOnlyJapaneseRegistrations() {
        val body = """{"devices":[
              {"device_type":"I","device_id":"84bf1d","aircraft_model":"ASK21","registration":"JA2365","cn":"WM","tracked":"Y","identified":"Y"},
              {"device_type":"F","device_id":"000256","aircraft_model":"ASK 16","registration":"D-KGOR","cn":"OR","tracked":"Y","identified":"Y"},
              {"device_type":"F","device_id":"000999","aircraft_model":"","registration":"","cn":"","tracked":"Y","identified":"Y"}
            ]}"""
        val db = OgnDeviceDatabase.parse(body, registrationPrefix = "JA")
        assertEquals(setOf("84BF1D"), db.keys)
        assertEquals("WM", db.getValue("84BF1D").competitionId)
        // What gets cached: the same format, JA entries only.
        val stored = OgnDeviceDatabase.keepRegistrations(body, "JA")
        assertFalse("D-KGOR" in stored)
        assertEquals(db, OgnDeviceDatabase.parse(stored))
    }
}
