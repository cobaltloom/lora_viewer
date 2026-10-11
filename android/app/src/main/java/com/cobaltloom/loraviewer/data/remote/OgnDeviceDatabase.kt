package com.cobaltloom.loraviewer.data.remote

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** One OGN device database (DDB) entry: the owner's registration and privacy choices. */
data class OgnDevice(
    val registration: String?,
    val competitionId: String?,
    /** false = the owner asked not to be shown at all. */
    val isTracked: Boolean,
    /** false = the owner asked not to be identified; we don't show these either. */
    val isIdentified: Boolean,
)

/**
 * The OGN device database (http://ddb.glidernet.org), cached on disk and refreshed daily. OGN's data
 * usage rules require anyone redistributing OGN data to follow these privacy choices, so positions
 * are not shown until it has been loaded at least once.
 */
class OgnDeviceDatabase(
    private val cacheDir: File,
    private val httpClient: OkHttpClient,
) {
    private val mutex = Mutex()
    private var devices: Map<String, OgnDevice>? = null
    private var loadedAt: Instant? = null

    private val cacheFile get() = File(cacheDir, "ogn_ddb.json")

    /** Returns the database, downloading it if the cache is missing or over a day old. Falls back
     * to a stale cache if the download fails; throws only when there is nothing at all. */
    suspend fun get(): Map<String, OgnDevice> = mutex.withLock {
        val loaded = devices
        if (loaded != null && loadedAt?.let { Duration.between(it, Instant.now()) < MAX_AGE } == true) {
            return loaded
        }
        withContext(Dispatchers.IO) {
            val file = cacheFile
            val fresh = file.exists() && Duration.between(Instant.ofEpochMilli(file.lastModified()), Instant.now()) < MAX_AGE
            if (!fresh) {
                runCatching { download(file) }
            }
            if (!file.exists()) throw TrailRouteApiException.ServerError("OGNの機体データベースを取得できませんでした")
            val parsed = parse(file.readText())
            devices = parsed
            loadedAt = Instant.now()
            parsed
        }
    }

    private fun download(target: File) {
        val request = Request.Builder().url(URL).header("User-Agent", OgnApiClient.USER_AGENT).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
            val body = response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            parse(body) // don't replace a good cache with something unparseable
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(body)
            tmp.renameTo(target)
        }
    }

    companion object {
        private const val URL = "https://ddb.glidernet.org/download/?j=1&t=1"
        private val MAX_AGE = Duration.ofDays(1)
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): Map<String, OgnDevice> {
            val devices = json.parseToJsonElement(body).jsonObject["devices"]?.jsonArray
                ?: throw TrailRouteApiException.InvalidResponse()
            return devices.mapNotNull { it as? JsonObject }.associate { d ->
                d.lenientString("device_id").uppercase() to OgnDevice(
                    registration = d.lenientString("registration").ifBlank { null },
                    competitionId = d.lenientString("cn").ifBlank { null },
                    isTracked = d.lenientString("tracked") != "N",
                    isIdentified = d.lenientString("identified") != "N",
                )
            }
        }
    }
}
