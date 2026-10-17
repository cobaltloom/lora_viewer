package com.cobaltloom.loraviewer.data.favorites

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.alert.LapScore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.handicapDataStore by preferencesDataStore(name = "glider_handicaps")

/**
 * Each glider's competition handicap (競技規則53), set per glider in the member list and used for the
 * reference scores of completed laps. Kept on this device, keyed by tracker id (LoRa IMEI or OGN
 * callsign - they never collide, so one table serves both sources); 1.00 when unset.
 */
class GliderHandicapRepository(private val context: Context) {
    val handicaps: Flow<Map<String, Double>> = context.handicapDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) -> (value as? Double)?.let { key.name to it } }.toMap()
    }

    suspend fun setHandicap(id: String, value: Double) {
        context.handicapDataStore.edit { prefs ->
            val key = doublePreferencesKey(id)
            if (value == LapScore.DEFAULT_HANDICAP) prefs.remove(key) else prefs[key] = value
        }
    }
}
