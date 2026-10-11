package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.data.model.PositionSource
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OgnApiClientTest {
    private fun parse(aircraft: String) =
        OgnApiClient.latestPositions(
            Json.parseToJsonElement("""{"aircraft":[$aircraft],"anon":[]}""")
                .jsonObject["aircraft"]!!.jsonArray.map { it.jsonObject },
        )

    @Test
    fun keepsOnlyNewestFixPerDevice() {
        val positions = parse(
            """
            {"device_id":"FLR111111","lat":36.20,"lon":139.30,"altitude_m":800,"ts":"2026-10-11T03:00:02.000Z","registration":"JA21MA","cn":"MA"},
            {"device_id":"FLR111111","lat":36.21,"lon":139.31,"altitude_m":820,"ts":"2026-10-11T03:00:04.500Z","registration":"JA21MA","cn":"MA"}
            """,
        )
        assertEquals(1, positions.size)
        val p = positions.single()
        assertEquals(36.21, p.lat, 1e-9)
        assertEquals(820.0, p.alt!!, 1e-9)
        assertEquals(Instant.parse("2026-10-11T03:00:04.500Z"), p.positionDateTimeUtc)
        assertEquals("MA", p.index)
        assertEquals("MA JA21MA", p.sourceName)
        assertEquals(PositionSource.OGN, p.source)
    }

    @Test
    fun labelsFallBackToRegistrationThenDeviceId() {
        val positions = parse(
            """
            {"device_id":"FLR222222","lat":36.2,"lon":139.3,"altitude_m":500,"ts":"2026-10-11T03:00:00Z","registration":"JA2345"},
            {"device_id":"OGN3F1A2B","lat":36.2,"lon":139.3,"altitude_m":null,"ts":"2026-10-11T03:00:00Z","registration":"","cn":""}
            """,
        ).associateBy { it.imei }
        assertEquals("2345", positions["FLR222222"]!!.index)
        assertEquals("JA2345", positions["FLR222222"]!!.sourceName)
        assertEquals("1A2B", positions["OGN3F1A2B"]!!.index)
        assertEquals("OGN3F1A2B", positions["OGN3F1A2B"]!!.sourceName)
        assertNull(positions["OGN3F1A2B"]!!.alt)
    }

    @Test
    fun dropsOptedOutAndPositionlessAircraft() {
        val positions = parse(
            """
            {"device_id":"FLR333333","lat":36.2,"lon":139.3,"ts":"2026-10-11T03:00:00Z","no_tracking":true},
            {"device_id":"FLR444444","lat":36.2,"lon":139.3,"ts":"2026-10-11T03:00:00Z","hidden":true},
            {"device_id":"FLR555555","lat":null,"lon":null,"ts":"2026-10-11T03:00:00Z"},
            {"device_id":"FLR666666","lat":36.2,"lon":139.3,"ts":"2026-10-11T03:00:00Z"}
            """,
        )
        assertEquals(listOf("FLR666666"), positions.map { it.imei })
    }
}
