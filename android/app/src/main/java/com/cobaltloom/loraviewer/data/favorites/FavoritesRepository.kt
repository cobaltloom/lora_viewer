package com.cobaltloom.loraviewer.data.favorites

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.settings.DataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/**
 * IDs of gliders the pilot has marked as favorites, persisted locally on this device. LoRa (IMEI) and
 * OGN (FLARM etc.) ids differ for the same glider, so each source keeps its own list.
 */
class FavoritesRepository(private val context: Context) {
    /** The LoRa key predates OGN support and is kept as-is so existing favorites survive. */
    private fun key(source: DataSource) = stringSetPreferencesKey(
        when (source) {
            DataSource.LORA -> "favoriteGliderIMEIs"
            DataSource.OGN -> "favoriteOGNGliders"
        },
    )

    val favoritesBySource: Flow<Map<DataSource, Set<String>>> = context.favoritesDataStore.data.map { prefs ->
        DataSource.entries.associateWith { prefs[key(it)] ?: emptySet() }
    }

    suspend fun toggle(source: DataSource, id: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[key(source)] ?: emptySet()
            prefs[key(source)] = if (id in current) current - id else current + id
        }
    }
}
