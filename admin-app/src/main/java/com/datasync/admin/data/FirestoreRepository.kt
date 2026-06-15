package com.datasync.admin.data

import android.util.Log
import com.datasync.admin.model.Contact
import com.datasync.admin.model.Device
import com.datasync.admin.model.SMS
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch

class FirestoreRepository {
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

    fun getContacts(deviceId: String): Flow<List<Contact>> = callbackFlow {
        if (deviceId.isBlank()) {
            trySend(emptyList<Contact>())
            close()
            return@callbackFlow
        }
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("contacts")
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreRepository", "Error fetching contacts", error)
                    crashlytics.recordException(error)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val contacts = snapshot.toObjects(Contact::class.java)
                    trySend(contacts)
                }
            }
        awaitClose { subscription.remove() }
    }.catch { emit(emptyList()) }

    fun getSMS(deviceId: String): Flow<List<SMS>> = callbackFlow {
        if (deviceId.isBlank()) {
            trySend(emptyList<SMS>())
            close()
            return@callbackFlow
        }
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FirestoreRepository", "Error fetching SMS", error)
                    crashlytics.recordException(error)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val smsList = snapshot.toObjects(SMS::class.java)
                    trySend(smsList)
                }
            }
        awaitClose { subscription.remove() }
    }.catch { emit(emptyList()) }
}
