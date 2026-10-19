package com.cobaltloom.loraviewer.data.trail

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.model.GliderPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.gliderTrailsDataStore by preferencesDataStore(name = "glider_trails")

/**
 * Each glider's positions for its current flight, keyed by imei - drawn on the map as a trail.
 * Persisted so a trail survives the app being closed mid-flight, but cleared once that glider's
 * altitude drops to/below the ground threshold (the flight has landed), so the next takeoff
 * starts a fresh trail.
 *
 * Kept in memory and saved at most every [SAVE_INTERVAL_MILLIS] (plus on landing and [flush]):
 * reading, re-encoding and writing every trail on each 2 s refresh kept the CPU and storage busy.
 */
class GliderTrailRepository(private val context: Context) {
    private val key = stringPreferencesKey("gliderTrailsJson")
    private val json = Json { ignoreUnknownKeys = true }
    private val maxPointsPerGlider = 2000
    private val mutex = Mutex()
    private var loaded = false
    private var lastSaveMillis = 0L
    private var hasUnsavedPoints = false

    private val _trails = MutableStateFlow<Map<String, List<Coordinate>>>(emptyMap())
    /** Empty until the first [load], [recordPositions], [backfill] or [clear]. */
    val trails: StateFlow<Map<String, List<Coordinate>>> = _trails.asStateFlow()

    /** Reads the saved trails once; later calls do nothing. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    /** Saves any trail points not yet saved, e.g. when the app goes to the background. */
    suspend fun flush() = mutex.withLock {
        if (hasUnsavedPoints) save()
    }

    /**
     * Appends [positions] above [groundAltitudeThresholdM] to their glider's trail (deduping an
     * unchanged position), and drops a glider's trail entirely once it's back at/below that
     * altitude. Returns whether any glider started a new trail (first seen flying).
     */
    suspend fun recordPositions(positions: List<GliderPosition>, groundAltitudeThresholdM: Double): Boolean = mutex.withLock {
        ensureLoaded()
        var startedNewTrail = false
        var changed = false
        var landed = false
        val trails = _trails.value.toMutableMap()
        for (glider in positions) {
            val alt = glider.alt
            if (alt == null || alt <= groundAltitudeThresholdM) {
                if (trails.remove(glider.imei) != null) {
                    changed = true
                    landed = true
                }
                continue
            }
            if (glider.imei !in trails) startedNewTrail = true
            val points = trails[glider.imei].orEmpty().toMutableList()
            val last = points.lastOrNull()
            if (last != null && last.latitude == glider.lat && last.longitude == glider.lon) continue
            points.add(Coordinate(glider.lat, glider.lon))
            if (points.size > maxPointsPerGlider) points.subList(0, points.size - maxPointsPerGlider).clear()
            trails[glider.imei] = points
            changed = true
        }
        if (changed) {
            _trails.value = trails
            hasUnsavedPoints = true
            // A landing is saved at once, so a reopened app doesn't bring back a finished flight's trail.
            if (landed || System.currentTimeMillis() - lastSaveMillis >= SAVE_INTERVAL_MILLIS) save()
        }
        startedNewTrail
    }

    /**
     * Replaces trails with the track server's whole flight where it knows more than we drew live
     * (e.g. the app was opened mid-flight). Only gliders that still have a live trail - i.e. are
     * flying here - are touched, and the newest live point is kept if the server lags behind it.
     */
    suspend fun backfill(flights: Map<String, List<Coordinate>>) = mutex.withLock {
        ensureLoaded()
        val trails = _trails.value.toMutableMap()
        var changed = false
        for ((id, points) in flights) {
            val live = trails[id] ?: continue
            if (points.size <= live.size) continue
            val newest = live.lastOrNull()
            val merged = if (newest != null && newest != points.lastOrNull()) points + newest else points
            trails[id] = merged.takeLast(maxPointsPerGlider)
            changed = true
        }
        if (changed) {
            _trails.value = trails
            save()
        }
    }

    suspend fun clear() = mutex.withLock {
        loaded = true
        _trails.value = emptyMap()
        hasUnsavedPoints = false
        context.gliderTrailsDataStore.edit { it.remove(key) }
    }

    /** Callers hold [mutex]. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _trails.value = decode(context.gliderTrailsDataStore.data.first()[key])
    }

    /** Callers hold [mutex]. */
    private suspend fun save() {
        val encoded = json.encodeToString(_trails.value)
        context.gliderTrailsDataStore.edit { it[key] = encoded }
        lastSaveMillis = System.currentTimeMillis()
        hasUnsavedPoints = false
    }

    private fun decode(raw: String?): Map<String, List<Coordinate>> =
        raw?.let { runCatching { json.decodeFromString<Map<String, List<Coordinate>>>(it) }.getOrNull() } ?: emptyMap()

    private companion object {
        const val SAVE_INTERVAL_MILLIS = 30_000L
    }
}

/** Whether glider flight trails are drawn on the map - a free, device-local display preference. */
class MapDisplaySettingsRepository(private val context: Context) {
    private val showTrailsKey = booleanPreferencesKey("showGliderTrails")
    private val showSatelliteKey = booleanPreferencesKey("showSatelliteMap")
    private val showDistanceReferencePointsKey = booleanPreferencesKey("showDistanceReferencePoints")
    private val showKk43AreaKey = booleanPreferencesKey("showKK43Area")

    val showGliderTrails: Flow<Boolean> = context.gliderTrailsDataStore.data.map { it[showTrailsKey] ?: true }.distinctUntilChanged()
    val showSatelliteMap: Flow<Boolean> = context.gliderTrailsDataStore.data.map { it[showSatelliteKey] ?: false }.distinctUntilChanged()
    val showDistanceReferencePoints: Flow<Boolean> =
        context.gliderTrailsDataStore.data.map { it[showDistanceReferencePointsKey] ?: true }.distinctUntilChanged()
    val showKk43Area: Flow<Boolean> = context.gliderTrailsDataStore.data.map { it[showKk43AreaKey] ?: false }.distinctUntilChanged()

    suspend fun setShowGliderTrails(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showTrailsKey] = value }
    }

    suspend fun setShowSatelliteMap(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showSatelliteKey] = value }
    }

    suspend fun setShowDistanceReferencePoints(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showDistanceReferencePointsKey] = value }
    }

    suspend fun setShowKk43Area(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showKk43AreaKey] = value }
    }
}
