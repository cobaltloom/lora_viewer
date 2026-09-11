package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.proximityAlertSettingsDataStore by preferencesDataStore(name = "proximity_alert_settings")

/**
 * Configurable "gliders getting close to each other" safety aid. Two severities, mirroring
 * [AlertSettings]: CAUTION is a quiet map-only indicator ("nearby, watch out"), while WARNING -
 * which also pushes a notification - additionally requires the pair to be actively closing
 * distance, not just near each other, since gliders sharing a thermal are commonly close together
 * without being on a collision course. Alerting fatigue on the noisy channel (notifications) was
 * the specific concern this guards against; the quiet channel (the map ring) doesn't need that
 * same restraint.
 *
 * Near the field, in the landing pattern, gliders are routinely close together and converging by
 * design (following each other around the circuit) - [patternExclusionRadiusKm]/
 * [patternExclusionCeilingM] cap a pair at CAUTION there, never escalating to a notification,
 * since that would fire on essentially every landing.
 *
 * This is an advisory aid only, not a collision-avoidance system: position data comes from
 * periodic polling (several seconds apart at best), not continuous real-time GPS.
 */
@Serializable
data class ProximityAlertSettings(
    val isEnabled: Boolean = false,
    /** Horizontal distance (meters) at/below which two gliders are flagged as "nearby" on the
     * map - no closing-trend requirement. */
    val cautionDistanceM: Double = 500.0,
    /** Horizontal distance (meters) at/below which - if also closing and within
     * [maxAltitudeDifferenceM] of each other - a push notification fires. */
    val warningDistanceM: Double = 150.0,
    /** Vertical separation (meters) beyond which two gliders are never considered a proximity
     * risk, regardless of horizontal distance. */
    val maxAltitudeDifferenceM: Double = 150.0,
    /** Distance (km) from the alert reference point within which both gliders must be for the
     * pattern exclusion to apply. */
    val patternExclusionRadiusKm: Double = 1.5,
    /** Altitude (MSL, matching the site's own altitude data) at/below which both gliders must be
     * for the pattern exclusion to apply. */
    val patternExclusionCeilingM: Double = 280.0,
)

class ProximityAlertSettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("proximityAlertSettingsJson")
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<ProximityAlertSettings> = context.proximityAlertSettingsDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<ProximityAlertSettings>(it) }.getOrNull() }
            ?: ProximityAlertSettings()
    }

    suspend fun save(settings: ProximityAlertSettings) {
        context.proximityAlertSettingsDataStore.edit { it[key] = json.encodeToString(settings) }
    }
}
