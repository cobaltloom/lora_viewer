package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.alert.TeamStandings
import com.cobaltloom.loraviewer.data.settings.DataSource
import com.cobaltloom.loraviewer.data.settings.TrackServerConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    suspend fun fetchFlights(server: TrackServerConfig, source: DataSource, groundAltitudeM: Double): Map<String, List<TrackServerPoint>> {
        val tracks = get("tracks", server, source, groundAltitudeM)["tracks"]?.jsonObject
            ?: throw TrailRouteApiException.InvalidResponse()
        return tracks.mapValues { (_, points) -> points(points.jsonArray) }
    }

    /** Every flight since midnight JST, landed ones included, keyed like [fetchFlights]: each
     * glider's flights oldest first, each flight's points oldest first. */
    suspend fun fetchTodaysFlights(server: TrackServerConfig, source: DataSource, groundAltitudeM: Double): Map<String, List<List<TrackServerPoint>>> {
        val flights = get("flights", server, source, groundAltitudeM)["flights"]?.jsonObject
            ?: throw TrailRouteApiException.InvalidResponse()
        return flights.mapValues { (_, list) -> list.jsonArray.map { points(it.jsonArray) } }
    }

    /** The team standings a representative last saved, or null if none yet. */
    suspend fun fetchStandings(server: TrackServerConfig): TeamStandings? = withContext(Dispatchers.IO) {
        val url = server.url.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("standings")?.build()
            ?: throw TrailRouteApiException.InvalidBaseUrl()
        val request = Request.Builder().url(url).header("X-Api-Key", server.key).get().build()
        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
            response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
        }
        val standings = json.parseToJsonElement(body).jsonObject["standings"]
        if (standings == null || standings is JsonNull) null else json.decodeFromJsonElement(TeamStandings.serializer(), standings)
    }

    /** Saves [standings] for every app to see; needs the representative's [editorKey]. */
    suspend fun saveStandings(server: TrackServerConfig, editorKey: String, standings: TeamStandings): TeamStandings =
        withContext(Dispatchers.IO) {
            val url = server.url.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("standings")?.build()
                ?: throw TrailRouteApiException.InvalidBaseUrl()
            val payload = json.encodeToString(TeamStandings.serializer(), standings.copy(updatedAt = null))
            val request = Request.Builder().url(url)
                .header("X-Api-Key", server.key)
                .header("X-Editor-Key", editorKey)
                .put(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val body = httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 403 -> throw IllegalStateException("合言葉が違います")
                    !response.isSuccessful -> throw IllegalStateException("保存できませんでした(${response.code})")
                }
                response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            }
            json.decodeFromJsonElement(TeamStandings.serializer(), json.parseToJsonElement(body).jsonObject.getValue("standings"))
        }

    private suspend fun get(path: String, server: TrackServerConfig, source: DataSource, groundAltitudeM: Double) =
        withContext(Dispatchers.IO) {
            val url = server.url.toHttpUrlOrNull()?.newBuilder()
                ?.addPathSegment(path)
                ?.addQueryParameter("source", source.raw)
                ?.addQueryParameter("groundAltM", groundAltitudeM.toInt().toString())
                ?.build()
                ?: throw TrailRouteApiException.InvalidBaseUrl()
            val request = Request.Builder().url(url).header("X-Api-Key", server.key).get().build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
                response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            }
            json.parseToJsonElement(body).jsonObject
        }

    /** Each point is [epochSeconds, lat, lon, altM]. */
    private fun points(raw: JsonArray): List<TrackServerPoint> = raw.mapNotNull { p ->
        val a = p as? JsonArray ?: return@mapNotNull null
        fun num(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
        val t = num(0) ?: return@mapNotNull null
        val lat = num(1) ?: return@mapNotNull null
        val lon = num(2) ?: return@mapNotNull null
        val alt = num(3) ?: return@mapNotNull null
        TrackServerPoint((t * 1000).toLong(), Coordinate(lat, lon), alt)
    }
}
