package com.datasync.admin.data

import com.datasync.admin.model.Contact
import com.datasync.admin.model.Device
import com.datasync.admin.model.SMS
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()

    fun getDevices(): Flow<List<Device>> = callbackFlow {
        val subscription = db.collection("devices")
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
            .document("all")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val list = snapshot.get("list") as? List<Map<String, Any>>
                    val contacts = list?.map {
                        Contact(
                            name = it["name"] as? String ?: "",
                            phone = it["phone"] as? String ?: "",
                            lastUpdated = it["lastUpdated"] as? Long ?: 0
                        )
                    } ?: emptyList()
                    trySend(contacts)
                }
            }
        awaitClose { subscription.remove() }
    }

    fun getSMS(deviceId: String): Flow<List<SMS>> = callbackFlow {
        val subscription = db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .document("all")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val list = snapshot.get("list") as? List<Map<String, Any>>
                    val smsList = list?.map {
                        SMS(
                            address = it["address"] as? String ?: "",
                            body = it["body"] as? String ?: "",
                            date = it["date"] as? Long ?: 0,
                            type = (it["type"] as? Long)?.toInt() ?: 1
                        )
                    } ?: emptyList()
                    trySend(smsList)
                }
            }
        awaitClose { subscription.remove() }
    }
}
