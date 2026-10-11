package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.BuildConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.model.PositionSource
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Talks to OGN Japan (https://ogn.ezoe.net), using the same /api/positions endpoint the site's own
 * app.js polls. Only used once ApiSettings.isOgnAvailable is on.
 */
class OgnApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchCurrentPositions(): List<GliderPosition> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BASE_URL}api/positions?since=$SINCE_SECONDS")
            // Identify ourselves so the operator can tell app traffic apart from browsers.
            .header("User-Agent", "LoraViewer/${BuildConfig.VERSION_NAME} (Android)")
            .get()
            .build()
        val root = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
            val body = response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw TrailRouteApiException.InvalidResponse()
            }
        }
        val fixes = root["aircraft"]?.jsonArray ?: throw TrailRouteApiException.InvalidResponse()
        latestPositions(fixes.mapNotNull { it as? JsonObject })
    }

    companion object {
        const val BASE_URL = "https://ogn.ezoe.net/"
        const val ATTRIBUTION = "データ提供: OGN Japan (ogn.ezoe.net)"

        /** How far back the server should look. The site itself asks for 90s; we only need each
         * aircraft's latest fix, but a shorter window would drop aircraft between sparse fixes. */
        private const val SINCE_SECONDS = 90

        /**
         * The response holds every fix in the window; keep each aircraft's newest one. Aircraft with
         * any privacy flag (hidden, no-tracking, stealth, or DDB-anonymous) are dropped, per OGN's rule
         * that redistributors follow DDB privacy choices (the server already leaves random-id aircraft
         * out of "aircraft" and lists them separately under "anon", which we never read).
         */
        fun latestPositions(fixes: List<JsonObject>): List<GliderPosition> =
            fixes.asSequence()
                .filter { it.lenientDouble("lat") != null && it.lenientDouble("lon") != null }
                .filter { !it.isTrackingRefused() }
                .groupBy { it.lenientString("device_id") }
                .map { (_, deviceFixes) -> deviceFixes.maxBy { it.timestamp() ?: Instant.EPOCH } }
                .map { it.toGliderPosition() }

        private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

        private fun JsonObject.isTrackingRefused(): Boolean =
            flag("hidden") || flag("no_tracking") || flag("stealth") || flag("anon") ||
                lenientString("device_id").isEmpty()

        private fun JsonObject.timestamp(): Instant? =
            lenientString("ts").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

        private fun JsonObject.toGliderPosition(): GliderPosition {
            val deviceId = lenientString("device_id")
            val registration = lenientString("registration").ifEmpty { null }
            val competitionId = lenientString("cn").ifEmpty { null }
            // Marker label: the competition no. if set, else the registration without the "JA"
            // prefix (e.g. "JA21MA" -> "21MA"), else the tail of the device id.
            val markerLabel = competitionId
                ?: registration?.removePrefix("JA")
                ?: deviceId.takeLast(4)
            val displayName = listOfNotNull(competitionId, registration).joinToString(" ").ifEmpty { deviceId }
            return GliderPosition(
                imei = deviceId,
                index = markerLabel,
                lat = lenientDouble("lat") ?: 0.0,
                lon = lenientDouble("lon") ?: 0.0,
                alt = lenientDouble("altitude_m"),
                source = PositionSource.OGN,
                isDisconnected = false,
                positionDateTimeUtc = timestamp(),
                sourceName = displayName,
            )
        }
    }
}
