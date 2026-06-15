package com.datasync.admin.data

import com.datasync.admin.model.Contact
import com.datasync.admin.model.Device
import com.datasync.admin.model.SMS
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()

    fun getDevices(): Flow<List<Device>> = callbackFlow {
        val subscription = db.collection("devices")
            .orderBy("lastSyncTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val devices = snapshot.toObjects(Device::class.java)
                    trySend(devices)
                }
            }
        awaitClose { subscription.remove() }
    }

    fun getContacts(deviceId: String): Flow<List<Contact>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("contacts")
            .orderBy("name", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val contacts = snapshot.toObjects(Contact::class.java)
                    trySend(contacts)
                }
            }
        awaitClose { subscription.remove() }
    }

    fun getSMS(deviceId: String): Flow<List<SMS>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .orderBy("date", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val smsList = snapshot.toObjects(SMS::class.java)
                    trySend(smsList)
                }
            }
        awaitClose { subscription.remove() }
    }
}
