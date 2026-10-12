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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "api_settings")

/** Where glider positions come from. */
enum class DataSource(val raw: String, val displayName: String) {
    /** The club's LoRa trackers, via trailrouteview.com. */
    LORA("lora", "LoRa"),
    /** The Open Glider Network - FLARM/OGN-tracker equipped aircraft, received live from OGN. */
    OGN("ogn", "OGN");

    companion object {
        fun fromRaw(raw: String?): DataSource = entries.firstOrNull { it.raw == raw } ?: LORA
    }
}

/** The club's track server, from Firestore `meta/serverConfig` (`trackServerUrl` / `trackServerKey`). */
data class TrackServerConfig(val url: String, val key: String)

data class ApiSettings(
    val baseUrl: String,
    val secretKey: String,
    val refreshIntervalSeconds: Double,
    val isBaseUrlCustomized: Boolean,
    val dataSource: DataSource = DataSource.LORA,
    /** Whether OGN may be offered at all, from `meta/serverConfig.ognEnabled`. Off until the
     * feature has been checked against live traffic, and doubles as a remote kill switch - so it's
     * controlled from Firestore rather than baked into a release. */
    val isOgnAvailable: Boolean = false,
    /** The refresh interval while OGN is selected, separate from LoRa's. A refresh only reads
     * beacons already streamed in over the open connection - it asks no server anything - so it can
     * be much shorter; trails get a point per refresh, so they cover a shorter span than with LoRa. */
    val ognRefreshIntervalSeconds: Double = DEFAULT_OGN_REFRESH_INTERVAL_SECONDS,
    /** Whether the launch-time LoRa/OGN chooser was turned off ("次回から表示しない"). */
    val isDataSourcePromptSuppressed: Boolean = false,
    /** null until both Firestore fields are set, which keeps trail backfill off. */
    val trackServer: TrackServerConfig? = null,
) {
    /** The source actually in use: a saved OGN choice falls back to LoRa while OGN is switched off. */
    val effectiveDataSource: DataSource
        get() = if (dataSource == DataSource.OGN && !isOgnAvailable) DataSource.LORA else dataSource

    /** The polling interval for the source actually in use. */
    val currentRefreshIntervalSeconds: Double
        get() = when (effectiveDataSource) {
            DataSource.LORA -> refreshIntervalSeconds.coerceAtLeast(3.0)
            DataSource.OGN -> ognRefreshIntervalSeconds.coerceAtLeast(1.0)
        }

    companion object {
        // Same default as the iOS app: the site's own per-account map path is the
        // real access control, so no key is required by default.
        const val DEFAULT_BASE_URL = "https://www.trailrouteview.com/user/jsal/gmap/"
        const val DEFAULT_REFRESH_INTERVAL_SECONDS = 10.0
        const val DEFAULT_OGN_REFRESH_INTERVAL_SECONDS = 2.0
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
        val DATA_SOURCE = stringPreferencesKey("dataSource")
        val OGN_REFRESH_INTERVAL = doublePreferencesKey("ognRefreshIntervalSeconds")
        val DATA_SOURCE_PROMPT_SUPPRESSED = booleanPreferencesKey("dataSourcePromptSuppressed")
        /** Last `ognEnabled` seen from Firestore, so launch doesn't briefly poll LoRa before the
         * snapshot arrives. */
        val OGN_ENABLED_CACHED = booleanPreferencesKey("ognEnabledCached")
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteConfigListener: ListenerRegistration? = null
    /** Null until the first Firestore snapshot; until then the cached value applies. */
    private val ognAvailable = MutableStateFlow<Boolean?>(null)
    private val trackServer = MutableStateFlow<TrackServerConfig?>(null)

    val settings: Flow<ApiSettings> = combine(context.dataStore.data, ognAvailable, trackServer) { prefs, remoteOgnAvailable, server ->
        val isOgnAvailable = remoteOgnAvailable ?: prefs[Keys.OGN_ENABLED_CACHED] ?: false
        ApiSettings(
            baseUrl = prefs[Keys.BASE_URL] ?: ApiSettings.DEFAULT_BASE_URL,
            secretKey = prefs[Keys.SECRET_KEY] ?: "",
            refreshIntervalSeconds = prefs[Keys.REFRESH_INTERVAL]?.takeIf { it > 0 }
                ?: ApiSettings.DEFAULT_REFRESH_INTERVAL_SECONDS,
            isBaseUrlCustomized = isCustomized(prefs[Keys.BASE_URL], prefs[Keys.BASE_URL_CUSTOMIZED]),
            dataSource = DataSource.fromRaw(prefs[Keys.DATA_SOURCE]),
            isOgnAvailable = isOgnAvailable,
            ognRefreshIntervalSeconds = prefs[Keys.OGN_REFRESH_INTERVAL]?.takeIf { it > 0 }
                ?: ApiSettings.DEFAULT_OGN_REFRESH_INTERVAL_SECONDS,
            trackServer = server,
            isDataSourcePromptSuppressed = prefs[Keys.DATA_SOURCE_PROMPT_SUPPRESSED] ?: false,
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

    suspend fun setDataSource(value: DataSource) {
        context.dataStore.edit { it[Keys.DATA_SOURCE] = value.raw }
    }

    suspend fun setRefreshIntervalSeconds(value: Double) {
        context.dataStore.edit { it[Keys.REFRESH_INTERVAL] = value }
    }

    suspend fun setDataSourcePromptSuppressed(value: Boolean) {
        context.dataStore.edit { it[Keys.DATA_SOURCE_PROMPT_SUPPRESSED] = value }
    }

    suspend fun setOgnRefreshIntervalSeconds(value: Double) {
        context.dataStore.edit { it[Keys.OGN_REFRESH_INTERVAL] = value }
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
                if (snapshot != null) {
                    val url = snapshot.getString("trackServerUrl")?.takeIf { it.isNotBlank() }
                    val key = snapshot.getString("trackServerKey")?.takeIf { it.isNotBlank() }
                    trackServer.value = if (url != null && key != null) TrackServerConfig(url, key) else null
                    val ognEnabled = snapshot.getBoolean("ognEnabled") ?: false
                    ognAvailable.value = ognEnabled
                    repositoryScope.launch { context.dataStore.edit { it[Keys.OGN_ENABLED_CACHED] = ognEnabled } }
                }
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
