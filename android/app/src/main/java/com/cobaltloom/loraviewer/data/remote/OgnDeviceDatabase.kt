package com.cobaltloom.loraviewer.data.remote

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    /** false = the owner asked not to be identified; shown by position only, without any name. */
    val isIdentified: Boolean,
)

/**
 * A device database in the OGN DDB JSON format, cached on disk and refreshed daily: the OGN DDB
 * itself ([ogn]) or FlarmNet's export of the same format ([flarmNet]). OGN's data usage rules
 * require anyone redistributing OGN data to follow the OGN DDB's privacy choices, so positions are
 * not shown until it has been loaded at least once.
 */
class OgnDeviceDatabase private constructor(
    private val cacheDir: File,
    private val httpClient: OkHttpClient,
    private val url: String,
    private val cacheFileName: String,
    /** Only entries whose registration starts with this are kept; null keeps everything. */
    private val registrationPrefix: String?,
) {
    private val mutex = Mutex()
    private var devices: Map<String, OgnDevice>? = null
    private var loadedAt: Instant? = null
    private var lastDownloadAttempt: Instant? = null

    private val cacheFile get() = File(cacheDir, cacheFileName)

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
            // Without any cache a failed download would otherwise be retried on every refresh.
            val retryDue = lastDownloadAttempt?.let { Duration.between(it, Instant.now()) >= RETRY_INTERVAL } ?: true
            if (!fresh && retryDue) {
                lastDownloadAttempt = Instant.now()
                runCatching { download(file) }
            }
            if (!file.exists()) throw TrailRouteApiException.ServerError("機体データベースを取得できませんでした")
            val parsed = parse(file.readText(), registrationPrefix)
            devices = parsed
            loadedAt = Instant.now()
            parsed
        }
    }

    private fun download(target: File) {
        val request = Request.Builder().url(url).header("User-Agent", OgnApiClient.USER_AGENT).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw TrailRouteApiException.InvalidResponse()
            val body = response.body?.string() ?: throw TrailRouteApiException.InvalidResponse()
            parse(body, registrationPrefix) // don't replace a good cache with something unparseable
            // Cache only the entries we use (FlarmNet: a few KB instead of ~2 MB).
            val stored = registrationPrefix?.let { keepRegistrations(body, it) } ?: body
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(stored)
            tmp.renameTo(target)
        }
    }

    companion object {
        private val MAX_AGE = Duration.ofDays(1)
        private val RETRY_INTERVAL = Duration.ofMinutes(10)
        private val json = Json { ignoreUnknownKeys = true }

        /** The OGN DDB (http://ddb.glidernet.org): names and the owners' privacy choices. */
        fun ogn(cacheDir: File, httpClient: OkHttpClient) = OgnDeviceDatabase(
            cacheDir, httpClient, "https://ddb.glidernet.org/download/?j=1&t=1", "ogn_ddb.json", null,
        )

        /** FlarmNet (https://www.flarmnet.org), Japanese registrations only: names for aircraft that
         * are not in the OGN DDB. */
        fun flarmNet(cacheDir: File, httpClient: OkHttpClient) = OgnDeviceDatabase(
            cacheDir, httpClient, "https://www.flarmnet.org/files/ddb.json", "flarmnet_ddb.json", "JA",
        )

        /** The same DDB JSON with only the entries whose registration starts with [prefix]. */
        fun keepRegistrations(body: String, prefix: String): String {
            val devices = json.parseToJsonElement(body).jsonObject["devices"]?.jsonArray
                ?: throw TrailRouteApiException.InvalidResponse()
            val kept = devices.filter { (it as? JsonObject)?.lenientString("registration")?.trim()?.startsWith(prefix) == true }
            return JsonObject(mapOf("devices" to JsonArray(kept))).toString()
        }

        fun parse(body: String, registrationPrefix: String? = null): Map<String, OgnDevice> {
            val devices = json.parseToJsonElement(body).jsonObject["devices"]?.jsonArray
                ?: throw TrailRouteApiException.InvalidResponse()
            return devices.mapNotNull { it as? JsonObject }.mapNotNull { d ->
                val registration = d.lenientString("registration").trim()
                if (registrationPrefix != null && !registration.startsWith(registrationPrefix)) return@mapNotNull null
                d.lenientString("device_id").uppercase() to OgnDevice(
                    registration = registration.ifBlank { null },
                    competitionId = d.lenientString("cn").trim().ifBlank { null },
                    isTracked = d.lenientString("tracked") != "N",
                    isIdentified = d.lenientString("identified") != "N",
                )
            }.toMap()
        }
    }
}
