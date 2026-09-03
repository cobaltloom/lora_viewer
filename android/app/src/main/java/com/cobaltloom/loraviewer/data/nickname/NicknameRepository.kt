package com.cobaltloom.loraviewer.data.nickname

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Pilot-assigned glider nicknames, keyed by IMEI, shared in real time across
 * every device (iOS and Android alike) through the same "nicknames"
 * Firestore collection: document ID is the IMEI, fields are `nickname` and
 * `updatedAt`. The collection has no authentication - any device using the
 * app can read and write it, the same way a paper/whiteboard roster works.
 */
class NicknameRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val collection = firestore.collection("nicknames")

    val nicknames: Flow<Map<String, String>> = callbackFlow {
        var current = mapOf<String, String>()
        val registration = collection.addSnapshotListener { snapshot, _ ->
            if (snapshot == null) return@addSnapshotListener
            val updated = current.toMutableMap()
            for (change in snapshot.documentChanges) {
                val imei = change.document.id
                when (change.type) {
                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                        change.document.getString("nickname")?.let { updated[imei] = it }
                    }
                    DocumentChange.Type.REMOVED -> updated.remove(imei)
                }
            }
            current = updated
            trySend(current)
        }
        awaitClose { registration.remove() }
    }

    fun setNickname(imei: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            collection.document(imei).delete()
        } else {
            collection.document(imei).set(
                mapOf(
                    "nickname" to trimmed,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }
    }
}
