package com.cobaltloom.loraviewer.data.nickname

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.nicknameDataStore by preferencesDataStore(name = "nicknames")

/**
 * Whether this device's nicknames follow the shared list, or are kept purely local. MANUAL is an
 * escape hatch: since the shared list has no authentication (anyone with the app can write to
 * it), a device can drop out of sync to protect its own names from being overwritten by mischief
 * elsewhere, without affecting what other devices see.
 */
enum class NicknameSyncMode { SYNCED, MANUAL }

/**
 * Pilot-assigned glider nicknames, keyed by IMEI. The site itself only labels members "1.", "2."
 * etc., so this lets pilots attach names they actually recognize - and, by default, shares them:
 * nicknames sync through the same "nicknames" Firestore collection the iOS app uses (document ID
 * is the IMEI, fields are `nickname` and `updatedAt`), the same way the club's paper/whiteboard
 * roster works (whoever fills it in, everyone sees it). A local DataStore copy keeps names
 * available instantly on launch and while offline, and is the only copy used in MANUAL mode.
 */
class NicknameRepository(
    context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val dataStore = context.nicknameDataStore
    private val nicknamesKey = stringPreferencesKey("gliderNicknamesJson")
    private val syncModeKey = stringPreferencesKey("gliderNicknameSyncMode")
    private val collection = firestore.collection("nicknames")
    private val scheduleDoc = firestore.collection("meta").document("nicknameClearSchedule")
    private val json = Json { ignoreUnknownKeys = true }
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var listener: ListenerRegistration? = null

    val syncMode: Flow<NicknameSyncMode> = dataStore.data.map { prefs ->
        prefs[syncModeKey]?.let { raw -> runCatching { NicknameSyncMode.valueOf(raw) }.getOrNull() }
            ?: NicknameSyncMode.SYNCED
    }

    val nicknames: Flow<Map<String, String>> = dataStore.data.map { prefs -> decodeNicknames(prefs[nicknamesKey]) }

    init {
        repositoryScope.launch {
            syncMode.distinctUntilChanged().collect { mode ->
                when (mode) {
                    NicknameSyncMode.SYNCED -> {
                        attachListener()
                        runCatching { checkAndPerformScheduledClearIfNeeded() }
                    }
                    NicknameSyncMode.MANUAL -> detachListener()
                }
            }
        }
    }

    suspend fun setSyncMode(mode: NicknameSyncMode) {
        dataStore.edit { it[syncModeKey] = mode.name }
    }

    fun setNickname(imei: String, name: String) {
        val trimmed = name.trim()
        repositoryScope.launch {
            dataStore.edit { prefs ->
                val updated = decodeNicknames(prefs[nicknamesKey]).toMutableMap()
                if (trimmed.isEmpty()) updated.remove(imei) else updated[imei] = trimmed
                prefs[nicknamesKey] = json.encodeToString(updated)
            }
            if (syncMode.first() == NicknameSyncMode.SYNCED) {
                if (trimmed.isEmpty()) {
                    collection.document(imei).delete()
                } else {
                    collection.document(imei).set(
                        mapOf("nickname" to trimmed, "updatedAt" to FieldValue.serverTimestamp()),
                    )
                }
            }
        }
    }

    /**
     * Keeps every synced device in near-real-time agreement: when anyone using the app renames a
     * glider, everyone else's local copy updates automatically.
     */
    private fun attachListener() {
        if (listener != null) return
        listener = collection.addSnapshotListener { snapshot, _ ->
            if (snapshot == null) return@addSnapshotListener
            repositoryScope.launch {
                dataStore.edit { prefs ->
                    val updated = decodeNicknames(prefs[nicknamesKey]).toMutableMap()
                    for (change in snapshot.documentChanges) {
                        val imei = change.document.id
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                                change.document.getString("nickname")?.let { updated[imei] = it }
                            }
                            DocumentChange.Type.REMOVED -> updated.remove(imei)
                        }
                    }
                    prefs[nicknamesKey] = json.encodeToString(updated)
                }
            }
        }
    }

    /** Switching to MANUAL just stops listening/writing - it leaves whatever names this device already had. */
    private fun detachListener() {
        listener?.remove()
        listener = null
    }

    /**
     * The shared list has no server to run a timer on its own, so instead every synced device
     * checks - on launch, and whenever it (re)joins sync - whether the most recent daily boundary
     * has already passed without being cleared, and if so clears it itself. This keeps the reset
     * entirely within Firestore's free tier (no scheduled Cloud Function), at the cost of not
     * firing at the exact minute: it takes effect the next time anyone opens the app after 22:00
     * JST. Any client can safely perform this - if two both do, the second is a harmless no-op
     * over an already-empty collection.
     */
    private suspend fun checkAndPerformScheduledClearIfNeeded() {
        val boundary = mostRecentClearBoundary()
        val snapshot = scheduleDoc.get().await()
        val lastCleared = snapshot.getTimestamp("lastClearedAt")?.toDate()?.toInstant()
        if (lastCleared != null && !lastCleared.isBefore(boundary)) return
        performScheduledClear(boundary)
    }

    private suspend fun performScheduledClear(boundary: Instant) {
        val snapshot = collection.get().await()
        val batch = firestore.batch()
        for (document in snapshot.documents) {
            batch.delete(document.reference)
        }
        batch.set(scheduleDoc, mapOf("lastClearedAt" to Timestamp(Date.from(boundary))))
        batch.commit().await()
    }

    /**
     * The most recent 22:00 JST that has already passed (or now, if it's exactly that moment) -
     * the shared list resets daily at this time so old names don't linger indefinitely; anyone
     * still using a name just re-enters it and it's back until the following day.
     */
    private fun mostRecentClearBoundary(now: Instant = Instant.now()): Instant {
        val zone = ZoneId.of("Asia/Tokyo")
        val zonedNow = now.atZone(zone)
        var candidate = zonedNow.toLocalDate().atTime(22, 0).atZone(zone)
        if (candidate.toInstant() > now) {
            candidate = candidate.minusDays(1)
        }
        return candidate.toInstant()
    }

    private fun decodeNicknames(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()
}
