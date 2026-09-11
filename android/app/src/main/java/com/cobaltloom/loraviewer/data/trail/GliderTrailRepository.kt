package com.cobaltloom.loraviewer.data.trail

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.alert.Coordinate
import com.cobaltloom.loraviewer.data.model.GliderPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.gliderTrailsDataStore by preferencesDataStore(name = "glider_trails")

/**
 * Each glider's positions for its current flight, keyed by imei - drawn on the map as a trail.
 * Persisted so a trail survives the app being closed mid-flight, but cleared once that glider's
 * altitude drops to/below the ground threshold (the flight has landed), so the next takeoff
 * starts a fresh trail.
 */
class GliderTrailRepository(private val context: Context) {
    private val key = stringPreferencesKey("gliderTrailsJson")
    private val json = Json { ignoreUnknownKeys = true }
    private val maxPointsPerGlider = 500

    val trails: Flow<Map<String, List<Coordinate>>> = context.gliderTrailsDataStore.data.map { prefs -> decode(prefs[key]) }

    /**
     * Appends [positions] above [groundAltitudeThresholdM] to their glider's trail (deduping an
     * unchanged position), and drops a glider's trail entirely once it's back at/below that
     * altitude.
     */
    suspend fun recordPositions(positions: List<GliderPosition>, groundAltitudeThresholdM: Double) {
        context.gliderTrailsDataStore.edit { prefs ->
            val trails = decode(prefs[key]).toMutableMap()
            for (glider in positions) {
                val alt = glider.alt
                if (alt == null || alt <= groundAltitudeThresholdM) {
                    trails.remove(glider.imei)
                    continue
                }
                val points = trails[glider.imei].orEmpty().toMutableList()
                val last = points.lastOrNull()
                if (last != null && last.latitude == glider.lat && last.longitude == glider.lon) continue
                points.add(Coordinate(glider.lat, glider.lon))
                if (points.size > maxPointsPerGlider) points.subList(0, points.size - maxPointsPerGlider).clear()
                trails[glider.imei] = points
            }
            prefs[key] = json.encodeToString(trails)
        }
    }

    private fun decode(raw: String?): Map<String, List<Coordinate>> =
        raw?.let { runCatching { json.decodeFromString<Map<String, List<Coordinate>>>(it) }.getOrNull() } ?: emptyMap()
}

/** Whether glider flight trails are drawn on the map - a free, device-local display preference. */
class MapDisplaySettingsRepository(private val context: Context) {
    private val showTrailsKey = booleanPreferencesKey("showGliderTrails")
    private val showSatelliteKey = booleanPreferencesKey("showSatelliteMap")
    private val showDistanceReferencePointsKey = booleanPreferencesKey("showDistanceReferencePoints")

    val showGliderTrails: Flow<Boolean> = context.gliderTrailsDataStore.data.map { it[showTrailsKey] ?: true }
    val showSatelliteMap: Flow<Boolean> = context.gliderTrailsDataStore.data.map { it[showSatelliteKey] ?: false }
    val showDistanceReferencePoints: Flow<Boolean> =
        context.gliderTrailsDataStore.data.map { it[showDistanceReferencePointsKey] ?: false }

    suspend fun setShowGliderTrails(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showTrailsKey] = value }
    }

    suspend fun setShowSatelliteMap(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showSatelliteKey] = value }
    }

    suspend fun setShowDistanceReferencePoints(value: Boolean) {
        context.gliderTrailsDataStore.edit { it[showDistanceReferencePointsKey] = value }
    }
}
