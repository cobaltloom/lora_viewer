package com.cobaltloom.loraviewer.data.alert

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cobaltloom.loraviewer.data.settings.DataSource
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
    /** For goal rows: the glider's id, lap time and course distance, so the history can give each
     * completed lap a reference score (see [LapScore]). Absent in older records and other rows. */
    val gliderId: String? = null,
    val lapSeconds: Double? = null,
    val courseDistanceKm: Double? = null,
) {
    val localDate: LocalDate
        get() = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** A completed lap, logged as "ゴール 所要…・平均…" (see GliderTrackerViewModel.updateLapStatuses). */
    val isFinish: Boolean get() = turnpointName.startsWith(FINISH_PREFIX)

    companion object {
        const val FINISH_PREFIX = "ゴール "
    }
}

/**
 * Persists turnpoint-passage events to DataStore so they survive app relaunch and can be reviewed
 * later in the day even if a push notification was missed. This is a rolling log for same-day
 * review, not a full flight-log archive, so entries older than [retentionDays] are dropped.
 * LoRa and OGN (FLARM) keep separate logs, like favorites: the same glider has different names in
 * each, so a shared log would mix them and could list one passage twice.
 */
class TurnpointPassageLogRepository(private val context: Context) {
    /** The LoRa key predates OGN support and is kept as-is so existing records survive. */
    private fun key(source: DataSource) = stringPreferencesKey(
        when (source) {
            DataSource.LORA -> "turnpointPassageLogJson"
            DataSource.OGN -> "turnpointPassageLogOgnJson"
        },
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val retentionDays = 2L

    val recordsBySource: Flow<Map<DataSource, List<TurnpointPassageRecord>>> =
        context.turnpointPassageLogDataStore.data.map { prefs ->
            DataSource.entries.associateWith { prune(decode(prefs[key(it)])) }
        }

    /**
     * Records a passage found afterwards in the track server's history (flown while the app was
     * closed), at the time it actually happened - unless the same glider already has a record for
     * this turnpoint within 10 minutes (e.g. the live check caught it when the app opened). Also
     * used for live passages, timed by the fix. Returns whether it was recorded.
     */
    suspend fun recordPastPassage(
        source: DataSource,
        gliderName: String,
        turnpointName: String,
        altitudeM: Double?,
        timestampMillis: Long,
        gliderId: String? = null,
        lapSeconds: Double? = null,
        courseDistanceKm: Double? = null,
    ): Boolean {
        val duplicateWindowMillis = 10 * 60 * 1000L
        var recorded = false
        context.turnpointPassageLogDataStore.edit { prefs ->
            val existing = decode(prefs[key(source)])
            // Goal rows carry the lap time, which can differ by a few seconds between the live track
            // and the server's, so any two goal rows count as the same event.
            val isFinish = turnpointName.startsWith(TurnpointPassageRecord.FINISH_PREFIX)
            val duplicate = existing.indexOfFirst {
                it.gliderName == gliderName && (it.turnpointName == turnpointName || (isFinish && it.isFinish)) &&
                    kotlin.math.abs(it.timestampMillis - timestampMillis) < duplicateWindowMillis
            }
            if (duplicate >= 0) {
                // A goal row saved before lap scoring gets its lap time now, so it can be scored.
                if (existing[duplicate].lapSeconds == null && lapSeconds != null) {
                    val updated = existing.toMutableList()
                    updated[duplicate] = existing[duplicate].copy(gliderId = gliderId, lapSeconds = lapSeconds, courseDistanceKm = courseDistanceKm)
                    prefs[key(source)] = json.encodeToString(updated)
                }
                return@edit
            }
            val record = TurnpointPassageRecord(
                gliderName = gliderName,
                turnpointName = turnpointName,
                altitudeM = altitudeM,
                timestampMillis = timestampMillis,
                gliderId = gliderId,
                lapSeconds = lapSeconds,
                courseDistanceKm = courseDistanceKm,
            )
            prefs[key(source)] = json.encodeToString(prune(existing + record))
            recorded = true
        }
        return recorded
    }

    suspend fun clearToday(source: DataSource) {
        context.turnpointPassageLogDataStore.edit { prefs ->
            val today = LocalDate.now()
            prefs[key(source)] = json.encodeToString(decode(prefs[key(source)]).filterNot { it.localDate == today })
        }
    }

    private fun decode(raw: String?): List<TurnpointPassageRecord> =
        raw?.let { runCatching { json.decodeFromString<List<TurnpointPassageRecord>>(it) }.getOrNull() } ?: emptyList()

    private fun prune(records: List<TurnpointPassageRecord>): List<TurnpointPassageRecord> {
        val cutoff = System.currentTimeMillis() - retentionDays * 24 * 60 * 60 * 1000
        return records.filter { it.timestampMillis >= cutoff }
    }
}
