package com.datasync.admin.data

import android.util.Log
import com.datasync.admin.model.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRepository @Inject constructor() {
    private val db = FirebaseFirestore.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()

    fun getDevices(): Flow<List<Device>> = callbackFlow {
        val subscription = db.collection("devices")
            .orderBy("lastSyncTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreRepository", "Error fetching devices", error)
                    crashlytics.recordException(error)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val devices = snapshot.toObjects(Device::class.java)
                    trySend(devices)
                }
            }
        awaitClose { subscription.remove() }
    }.catch { emit(emptyList()) }

    fun getContacts(deviceId: String): Flow<List<Contact>> = getCollectionFlow(deviceId, "contacts", "name", Query.Direction.ASCENDING, Contact::class.java)

    fun getSMS(deviceId: String): Flow<List<SMS>> = getCollectionFlow(deviceId, "sms", "date", Query.Direction.DESCENDING, SMS::class.java)

    fun getCallLogs(deviceId: String): Flow<List<CallLog>> = getCollectionFlow(deviceId, "calllogs", "date", Query.Direction.DESCENDING, CallLog::class.java)

    fun getNotifications(deviceId: String): Flow<List<NotificationData>> = getCollectionFlow(deviceId, "notifications", "timestamp", Query.Direction.DESCENDING, NotificationData::class.java)

    private fun <T> getCollectionFlow(
        deviceId: String,
        collectionName: String,
        orderBy: String,
        direction: Query.Direction,
        clazz: Class<T>
    ): Flow<List<T>> = callbackFlow {
        if (deviceId.isBlank()) {
            trySend(emptyList<T>())
            close()
            return@callbackFlow
        }
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection(collectionName)
            .orderBy(orderBy, direction)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreRepository", "Error fetching $collectionName", error)
                    crashlytics.recordException(error)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val items = snapshot.toObjects(clazz)
                    trySend(items)
                }
            }
        awaitClose { subscription.remove() }
    }.catch { emit(emptyList()) }
}
