package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.model.GliderPosition
import kotlin.math.floor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.competitionGuidelineDataStore by preferencesDataStore(name = "competition_guideline")

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
    private val key = booleanPreferencesKey("competitionGuidelineEnabled")

    val isEnabled: Flow<Boolean> = context.competitionGuidelineDataStore.data.map { it[key] ?: false }

    suspend fun setEnabled(value: Boolean) {
        context.competitionGuidelineDataStore.edit { it[key] = value }
    }
}
