package com.cobaltloom.loraviewer.data.favorites

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/** IMEIs of gliders the pilot has marked as favorites, persisted locally on this device. */
class FavoritesRepository(private val context: Context) {
    private val key = stringSetPreferencesKey("favoriteGliderIMEIs")

    val favoriteImeis: Flow<Set<String>> = context.favoritesDataStore.data.map { it[key] ?: emptySet() }

    suspend fun toggle(imei: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (imei in current) current - imei else current + imei
        }
    }
}
