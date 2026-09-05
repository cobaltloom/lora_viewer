package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.model.GliderPosition
import kotlin.math.floor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.competitionGuidelineDataStore by preferencesDataStore(name = "competition_guideline")

/**
 * [isEnabled] turns the published altitude guideline on/off. [showTaskCourse] draws the published
 * turnpoints and a selected task course on the map - a separate toggle from the guideline itself,
 * since someone might want one without the other. [selectedCourseIndex] indexes into
 * [CompetitionTaskCourseData.courses], or is null to show just the turnpoints without connecting
 * them into a course line.
 */
@Serializable
data class CompetitionGuidelineSettings(
    val isEnabled: Boolean = false,
    val showTaskCourse: Boolean = false,
    val selectedCourseIndex: Int? = null,
)

/**
 * The official JSAL 妻沼滑空場 competition altitude guideline (Ver. 2026-01-26): a minimum MSL
 * altitude required beyond a given distance from the field center, stepping up as distance grows.
 * Unlike [AlertSettings] (the user's own configurable rule), this is a fixed, published table -
 * it can only be turned on or off, not edited.
 */
object CompetitionAltitudeGuideline {
    /** 妻沼滑空場中心: N36°12'41", E139°25'08" per the guideline document. */
    val referenceCoordinate = Coordinate(
        latitude = 36 + 12.0 / 60 + 41.0 / 3600,
        longitude = 139 + 25.0 / 60 + 8.0 / 3600,
    )

    /** Below this distance the guideline sets no minimum altitude at all. */
    const val INNER_RADIUS_KM = 2.5

    /** Every distance the table's required altitude changes at, drawn as rings on the map. */
    val boundaryDistancesKm = listOf(2.5, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)

    /**
     * The minimum MSL altitude (meters) required at this distance, or null if this distance is
     * close enough that no minimum applies.
     *
     * 2.5-3km: 350m, then +70m per additional km, capped at 910m (10km+)
     */
    fun requiredAltitudeM(distanceKm: Double): Double? {
        if (distanceKm < INNER_RADIUS_KM) return null
        if (distanceKm < 3) return 350.0
        if (distanceKm >= 10) return 910.0
        val bracket = floor(distanceKm).toInt() // 3...9
        return 420.0 + 70.0 * (bracket - 3)
    }

    /**
     * [minimumFlyingAltitudeM] comes from [AlertSettings] - a position at or below it is treated
     * as on the ground, never alerted on regardless of distance.
     */
    fun isBelowGuideline(glider: GliderPosition, isEnabled: Boolean, minimumFlyingAltitudeM: Double): Boolean {
        if (!isEnabled) return false
        val alt = glider.alt ?: return false
        if (alt <= minimumFlyingAltitudeM) return false

        val distanceKm = distanceMeters(
            referenceCoordinate.latitude, referenceCoordinate.longitude, glider.lat, glider.lon,
        ) / 1000.0
        val required = requiredAltitudeM(distanceKm) ?: return false
        return alt < required
    }
}

class CompetitionGuidelineRepository(private val context: Context) {
    /** Pre-dates [settingsKey]; read as a fallback so upgrading doesn't reset an existing toggle. */
    private val legacyEnabledKey = booleanPreferencesKey("competitionGuidelineEnabled")
    private val settingsKey = stringPreferencesKey("competitionGuidelineSettingsJson")
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<CompetitionGuidelineSettings> = context.competitionGuidelineDataStore.data.map { prefs ->
        prefs[settingsKey]?.let { runCatching { json.decodeFromString<CompetitionGuidelineSettings>(it) }.getOrNull() }
            ?: CompetitionGuidelineSettings(isEnabled = prefs[legacyEnabledKey] ?: false)
    }

    suspend fun save(settings: CompetitionGuidelineSettings) {
        context.competitionGuidelineDataStore.edit { it[settingsKey] = json.encodeToString(settings) }
    }
}
