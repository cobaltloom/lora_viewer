package com.cobaltloom.loraviewer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "api_settings")

data class ApiSettings(
    val baseUrl: String,
    val secretKey: String,
    val refreshIntervalSeconds: Double,
) {
    companion object {
        // Same default as the iOS app: the site's own per-account map path is the
        // real access control, so no key is required by default.
        const val DEFAULT_BASE_URL = "https://www.trailrouteview.com/user/jsal/gmap/"
        const val DEFAULT_REFRESH_INTERVAL_SECONDS = 10.0
    }
}

/** User-configurable connection settings, persisted via DataStore. */
class ApiSettingsRepository(private val context: Context) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("apiBaseURL")
        val SECRET_KEY = stringPreferencesKey("apiSecretKey")
        val REFRESH_INTERVAL = doublePreferencesKey("refreshIntervalSeconds")
    }

    val settings: Flow<ApiSettings> = context.dataStore.data.map { prefs ->
        ApiSettings(
            baseUrl = prefs[Keys.BASE_URL] ?: ApiSettings.DEFAULT_BASE_URL,
            secretKey = prefs[Keys.SECRET_KEY] ?: "",
            refreshIntervalSeconds = prefs[Keys.REFRESH_INTERVAL]?.takeIf { it > 0 }
                ?: ApiSettings.DEFAULT_REFRESH_INTERVAL_SECONDS,
        )
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[Keys.BASE_URL] = value }
    }

    suspend fun setSecretKey(value: String) {
        context.dataStore.edit { it[Keys.SECRET_KEY] = value }
    }

    suspend fun setRefreshIntervalSeconds(value: Double) {
        context.dataStore.edit { it[Keys.REFRESH_INTERVAL] = value }
    }
}
