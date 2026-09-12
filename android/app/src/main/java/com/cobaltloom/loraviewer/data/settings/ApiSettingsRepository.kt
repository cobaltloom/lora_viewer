package com.cobaltloom.loraviewer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "api_settings")

data class ApiSettings(
    val baseUrl: String,
    val secretKey: String,
    val refreshIntervalSeconds: Double,
    val isBaseUrlCustomized: Boolean,
) {
    companion object {
        // Same default as the iOS app: the site's own per-account map path is the
        // real access control, so no key is required by default.
        const val DEFAULT_BASE_URL = "https://www.trailrouteview.com/user/jsal/gmap/"
        const val DEFAULT_REFRESH_INTERVAL_SECONDS = 10.0
    }
}

/**
 * User-configurable connection settings, persisted via DataStore. The base URL also follows a
 * server-provided default - stored in the same Firestore "meta" collection the nickname reset
 * schedule uses (document "serverConfig", field "baseUrl") - so a site URL change (rare, but has
 * happened) reaches every install without an app-store release. It's applied only while the
 * device hasn't typed its own URL into Settings; [setBaseUrl] marks the device as customized so
 * it stops following the remote value, and [resetBaseUrlToServerDefault] is the way back.
 */
class ApiSettingsRepository(
    private val context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private object Keys {
        val BASE_URL = stringPreferencesKey("apiBaseURL")
        val SECRET_KEY = stringPreferencesKey("apiSecretKey")
        val REFRESH_INTERVAL = doublePreferencesKey("refreshIntervalSeconds")
        val BASE_URL_CUSTOMIZED = booleanPreferencesKey("apiBaseURLCustomized")
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteConfigListener: ListenerRegistration? = null

    val settings: Flow<ApiSettings> = context.dataStore.data.map { prefs ->
        ApiSettings(
            baseUrl = prefs[Keys.BASE_URL] ?: ApiSettings.DEFAULT_BASE_URL,
            secretKey = prefs[Keys.SECRET_KEY] ?: "",
            refreshIntervalSeconds = prefs[Keys.REFRESH_INTERVAL]?.takeIf { it > 0 }
                ?: ApiSettings.DEFAULT_REFRESH_INTERVAL_SECONDS,
            isBaseUrlCustomized = isCustomized(prefs[Keys.BASE_URL], prefs[Keys.BASE_URL_CUSTOMIZED]),
        )
    }

    init {
        listenForRemoteBaseUrl()
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit {
            it[Keys.BASE_URL] = value
            it[Keys.BASE_URL_CUSTOMIZED] = true
        }
    }

    suspend fun setSecretKey(value: String) {
        context.dataStore.edit { it[Keys.SECRET_KEY] = value }
    }

    suspend fun setRefreshIntervalSeconds(value: Double) {
        context.dataStore.edit { it[Keys.REFRESH_INTERVAL] = value }
    }

    /** Discards a manually-entered URL and goes back to following the server-provided default. */
    suspend fun resetBaseUrlToServerDefault() {
        context.dataStore.edit { it[Keys.BASE_URL_CUSTOMIZED] = false }
    }

    /**
     * A saved URL from before [Keys.BASE_URL_CUSTOMIZED] existed that still matches the app's own
     * built-in default is just what every install always had, not a real customization.
     */
    private fun isCustomized(storedBaseUrl: String?, storedFlag: Boolean?): Boolean =
        storedFlag ?: (storedBaseUrl != null && storedBaseUrl != ApiSettings.DEFAULT_BASE_URL)

    private fun listenForRemoteBaseUrl() {
        remoteConfigListener = firestore.collection("meta").document("serverConfig")
            .addSnapshotListener { snapshot, _ ->
                val remoteBaseUrl = snapshot?.getString("baseUrl")?.takeIf { it.isNotBlank() }
                    ?: return@addSnapshotListener
                repositoryScope.launch {
                    val prefs = context.dataStore.data.first()
                    if (isCustomized(prefs[Keys.BASE_URL], prefs[Keys.BASE_URL_CUSTOMIZED])) return@launch
                    if (remoteBaseUrl == prefs[Keys.BASE_URL]) return@launch
                    context.dataStore.edit { it[Keys.BASE_URL] = remoteBaseUrl }
                }
            }
    }
}
