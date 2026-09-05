package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.turnpointPassageLogDataStore by preferencesDataStore(name = "turnpoint_passage_log")

/**
 * One recorded instance of a glider entering a competition turnpoint's sector (see
 * [com.cobaltloom.loraviewer.data.notification.AlertNotifier.notifyTurnpointPassage]), kept so it
 * can be reviewed in-app even if the push notification was missed.
 */
@Serializable
data class TurnpointPassageRecord(
    val id: String = UUID.randomUUID().toString(),
    val gliderName: String,
    val turnpointName: String,
    val altitudeM: Double?,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val localDate: LocalDate
        get() = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Persists turnpoint-passage events to DataStore so they survive app relaunch and can be reviewed
 * later in the day even if a push notification was missed. This is a rolling log for same-day
 * review, not a full flight-log archive, so entries older than [retentionDays] are dropped.
 */
class TurnpointPassageLogRepository(private val context: Context) {
    private val key = stringPreferencesKey("turnpointPassageLogJson")
    private val json = Json { ignoreUnknownKeys = true }
    private val retentionDays = 2L

    val records: Flow<List<TurnpointPassageRecord>> = context.turnpointPassageLogDataStore.data.map { prefs ->
        prune(decode(prefs[key]))
    }

    suspend fun record(gliderName: String, turnpointName: String, altitudeM: Double?) {
        context.turnpointPassageLogDataStore.edit { prefs ->
            val updated = prune(decode(prefs[key]) + TurnpointPassageRecord(gliderName = gliderName, turnpointName = turnpointName, altitudeM = altitudeM))
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun clearToday() {
        context.turnpointPassageLogDataStore.edit { prefs ->
            val today = LocalDate.now()
            prefs[key] = json.encodeToString(decode(prefs[key]).filterNot { it.localDate == today })
        }
    }

    private fun decode(raw: String?): List<TurnpointPassageRecord> =
        raw?.let { runCatching { json.decodeFromString<List<TurnpointPassageRecord>>(it) }.getOrNull() } ?: emptyList()

    private fun prune(records: List<TurnpointPassageRecord>): List<TurnpointPassageRecord> {
        val cutoff = System.currentTimeMillis() - retentionDays * 24 * 60 * 60 * 1000
        return records.filter { it.timestampMillis >= cutoff }
    }
}
