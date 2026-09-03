package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.model.GliderPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.alertSettingsDataStore by preferencesDataStore(name = "alert_settings")

/**
 * One step of the "steps" calculation mode: past [distanceKm] from the
 * reference point, at least [minimumAltitudeM] (MSL) is required.
 */
@Serializable
data class AltitudeStep(val distanceKm: Double, val minimumAltitudeM: Double)

/** How the custom rule turns distance-from-reference-point into a required altitude. */
@Serializable
enum class AltitudeCalculationMode {
    /** A list of discrete distance/altitude steps. Only ever produces WARNING-level alerts. */
    STEPS,

    /**
     * A continuous "final glide" calculation: [AlertSettings.arrivalAltitudeM] needed right at
     * the reference point, plus one more meter of altitude for every glide-ratio meters of
     * distance beyond it - checked at two glide ratios for a two-stage alert.
     */
    GLIDE_RATIO,
}

/**
 * Configurable "minimum altitude beyond a distance" safety rule: gliders have no engine, so past
 * a given distance from the field they need enough altitude (MSL, matching the site's own
 * altitude data) to glide back. [mode] picks which of two ways to compute that required altitude
 * is active; each mode keeps its own settings so switching between them doesn't lose either one's
 * configuration.
 *
 * This is an advisory aid only, not a certified instrument - the threshold values are whatever
 * the person configuring this decides are safe.
 */
@Serializable
data class AlertSettings(
    val isEnabled: Boolean = false,
    val mode: AltitudeCalculationMode = AltitudeCalculationMode.STEPS,
    val useCustomReference: Boolean = false,
    val customLatitude: Double = 0.0,
    val customLongitude: Double = 0.0,
    val steps: List<AltitudeStep> = listOf(AltitudeStep(3.0, 350.0)),
    val arrivalAltitudeM: Double = 300.0,
    val cautionGlideRatio: Double = 20.0,
    val warningGlideRatio: Double = 30.0,
    /** Altitude (MSL) at or below which a position is treated as on the ground, never alerted on. */
    val minimumFlyingAltitudeM: Double = 60.0,
) {
    /** The point distance is measured from: the custom point if set, otherwise [default]. */
    fun referenceCoordinate(default: Coordinate?): Coordinate? =
        if (useCustomReference) Coordinate(customLatitude, customLongitude) else default

    /**
     * The minimum MSL altitude (meters) required at this distance in STEPS mode, or the required
     * altitude at the given glide ratio in GLIDE_RATIO mode. Null only in STEPS mode, when closer
     * than every configured step (no restriction applies).
     */
    fun requiredAltitudeM(distanceKm: Double, glideRatio: Double? = null): Double? = when (mode) {
        AltitudeCalculationMode.STEPS ->
            steps.filter { it.distanceKm <= distanceKm }.maxByOrNull { it.distanceKm }?.minimumAltitudeM
        AltitudeCalculationMode.GLIDE_RATIO -> {
            val ratio = glideRatio ?: warningGlideRatio
            if (ratio > 0) arrivalAltitudeM + (distanceKm * 1000) / ratio else null
        }
    }

    /** This rule's alert severity for [glider], or null if it doesn't apply. */
    fun alertSeverity(glider: GliderPosition, defaultReference: Coordinate?): AlertSeverity? {
        if (!isEnabled) return null
        val alt = glider.alt ?: return null
        if (alt <= minimumFlyingAltitudeM) return null
        val reference = referenceCoordinate(defaultReference) ?: return null
        val distanceKm = distanceMeters(reference.latitude, reference.longitude, glider.lat, glider.lon) / 1000.0

        return when (mode) {
            AltitudeCalculationMode.STEPS -> {
                val required = requiredAltitudeM(distanceKm) ?: return null
                if (alt < required) AlertSeverity.WARNING else null
            }
            AltitudeCalculationMode.GLIDE_RATIO -> {
                val warningRequired = requiredAltitudeM(distanceKm, warningGlideRatio)
                if (warningRequired != null && alt < warningRequired) return AlertSeverity.WARNING
                val cautionRequired = requiredAltitudeM(distanceKm, cautionGlideRatio)
                if (cautionRequired != null && alt < cautionRequired) return AlertSeverity.CAUTION
                null
            }
        }
    }

    /** Appends a new step continuing the existing pattern, then keeps the list sorted by distance. */
    fun withAddedStep(): AlertSettings {
        val last = steps.maxByOrNull { it.distanceKm }
        val newStep = if (last == null) {
            AltitudeStep(distanceKm = 3.0, minimumAltitudeM = 350.0)
        } else {
            AltitudeStep(distanceKm = last.distanceKm + 1.0, minimumAltitudeM = last.minimumAltitudeM + 70.0)
        }
        return copy(steps = (steps + newStep).sortedBy { it.distanceKm })
    }
}

class AlertSettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("alertSettingsJson")
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AlertSettings> = context.alertSettingsDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<AlertSettings>(it) }.getOrNull() } ?: AlertSettings()
    }

    suspend fun save(settings: AlertSettings) {
        context.alertSettingsDataStore.edit { it[key] = json.encodeToString(settings) }
    }
}
