package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.settings.DataSource
import com.cobaltloom.loraviewer.data.settings.TrackServerConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One point of a track from the track server. */
data class TrackServerPoint(val timeMillis: Long, val coordinate: Coordinate, val altitudeM: Double)

/**
 * The club's own track server (see `server/` in the repo): it collects OGN and LoRa positions
 * around the clock, so it can hand back each flying glider's trail from takeoff - including what
 * was flown before this app was opened. Configured from Firestore (`meta/serverConfig`
 * `trackServerUrl` / `trackServerKey`); without them the app just draws trails live as before.
 */
class TrackServerClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Each currently flying glider's points since takeoff, keyed by the app's glider id, oldest first. */
    suspend fun fetchFlights(server: TrackServerConfig, source: DataSource, groundAltitudeM: Double): Map<String, List<TrackServerPoint>> =
        withContext(Dispatchers.IO) {
            val url = server.url.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment("tracks")
                ?.addQueryParameter("source", source.raw)
                ?.addQueryParameter("groundAltM", groundAltitudeM.toInt().toString())
                ?.build()
                ?: throw TrailRouteApiException.InvalidBaseUrl()
            val request = Request.Builder().url(url).header("X-Api-Key", server.key).get().build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
                response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            }
            val tracks = json.parseToJsonElement(body).jsonObject["tracks"]?.jsonObject
                ?: throw TrailRouteApiException.InvalidResponse()
            // Each point is [epochSeconds, lat, lon, altM].
            tracks.mapValues { (_, points) ->
                points.jsonArray.mapNotNull { p ->
                    val a = p as? JsonArray ?: return@mapNotNull null
                    fun num(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
                    val t = num(0) ?: return@mapNotNull null
                    val lat = num(1) ?: return@mapNotNull null
                    val lon = num(2) ?: return@mapNotNull null
                    val alt = num(3) ?: return@mapNotNull null
                    TrackServerPoint((t * 1000).toLong(), Coordinate(lat, lon), alt)
                }
            }
        }
}
