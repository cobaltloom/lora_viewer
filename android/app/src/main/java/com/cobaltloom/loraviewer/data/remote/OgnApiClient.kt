package com.cobaltloom.loraviewer.data.remote

import com.cobaltloom.loraviewer.BuildConfig
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.model.PositionSource
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Live positions straight from the Open Glider Network: a read-only APRS-IS connection to
 * aprs.glidernet.org, filtered to a radius around Menuma, kept open while the OGN source is in use.
 * Beacons are collected as they stream in and [fetchCurrentPositions] returns the latest per
 * aircraft, so the rest of the app can keep polling exactly as it does for LoRa.
 *
 * Follows OGN's data usage rules (https://www.glidernet.org/ogn-data-usage/): stealth/no-tracking/
 * random-address aircraft and DDB entries marked not tracked or not identified are never shown.
 */
class OgnApiClient(
    cacheDir: File,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val deviceDatabase = OgnDeviceDatabase(cacheDir, httpClient)
    private val latestBeacons = ConcurrentHashMap<String, OgnBeacon>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    @Volatile private var socket: Socket? = null

    /** A read-only APRS login still needs a callsign; OGN asks that it be unique per client. */
    private val callsign = "LV" + (1..7).map { CALLSIGN_CHARS[Random.nextInt(CALLSIGN_CHARS.length)] }.joinToString("")

    suspend fun fetchCurrentPositions(): List<GliderPosition> {
        val devices = deviceDatabase.get()
        ensureStreaming()
        val cutoff = Instant.now().minus(MAX_FIX_AGE)
        latestBeacons.values.removeIf { it.timestamp < cutoff }
        return latestBeacons.values.mapNotNull { beacon ->
            val device = devices[beacon.address]
            if (device != null && (!device.isTracked || !device.isIdentified)) return@mapNotNull null
            beacon.toGliderPosition(device)
        }
    }

    /** Closes the stream; the next [fetchCurrentPositions] reconnects. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        runCatching { socket?.close() }
        socket = null
        latestBeacons.clear()
    }

    @Synchronized
    private fun ensureStreaming() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            while (isActive) {
                runCatching { stream() }
                runCatching { socket?.close() }
                socket = null
                if (isActive) delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun stream() {
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(HOST, PORT), 15_000)
        s.soTimeout = 90_000 // the server sends a "#" comment every ~20s, so silence means a dead link
        val writer = s.getOutputStream().bufferedWriter()
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        writer.write("user $callsign pass -1 vers LoraViewer ${BuildConfig.VERSION_NAME} filter $FILTER\r\n")
        writer.flush()
        val keepalive = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                runCatching { writer.write("#keepalive\r\n"); writer.flush() }
            }
        }
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val now = Instant.now()
                val beacon = OgnBeaconParser.parse(line, now) ?: continue
                if (!OgnBeaconParser.isDisplayable(beacon)) continue
                // Some trackers' clocks are hours off; such a fix would linger past MAX_FIX_AGE or
                // vanish at once, so it's dropped rather than shown at a misleading time.
                if (Duration.between(beacon.timestamp, now).abs() > MAX_CLOCK_SKEW) continue
                val previous = latestBeacons[beacon.callsign]
                if (previous == null || previous.timestamp <= beacon.timestamp) latestBeacons[beacon.callsign] = beacon
            }
        } finally {
            keepalive.cancel()
        }
    }

    companion object {
        const val ATTRIBUTION = "データ提供: Open Glider Network (OGN)"

        /** How often the map reads the latest beacons while OGN is selected. Unlike LoRa this asks
         * no server anything (beacons are already streaming in), so it can be short; trails get a
         * point per refresh, so they cover a shorter span than with LoRa's default. */
        const val REFRESH_INTERVAL_SECONDS = 3.0
        val USER_AGENT = "LoraViewer/${BuildConfig.VERSION_NAME} (Android)"

        private const val HOST = "aprs.glidernet.org"
        private const val PORT = 14580

        /** 150 km around the Menuma OGN receiver - all of Kanto, without the whole country's traffic. */
        private const val FILTER = "r/36.2113/139.4188/150"
        private val MAX_FIX_AGE = Duration.ofSeconds(90)
        private val MAX_CLOCK_SKEW = Duration.ofMinutes(5)
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val KEEPALIVE_MS = 60_000L
        private const val CALLSIGN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun OgnBeacon.toGliderPosition(device: OgnDevice?): GliderPosition {
            val registration = device?.registration
            val competitionId = device?.competitionId
            // Marker label: the competition no. if set, else the registration without the "JA"
            // prefix (e.g. "JA21MA" -> "21MA"), else the tail of the device address.
            val markerLabel = competitionId
                ?: registration?.removePrefix("JA")
                ?: address.takeLast(4)
            val displayName = listOfNotNull(competitionId, registration).joinToString(" ").ifEmpty { callsign }
            return GliderPosition(
                imei = callsign,
                index = markerLabel,
                lat = lat,
                lon = lon,
                alt = altitudeM,
                source = PositionSource.OGN,
                isDisconnected = false,
                positionDateTimeUtc = timestamp,
                sourceName = displayName,
            )
        }
    }
}
