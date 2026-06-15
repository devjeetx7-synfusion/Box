package com.boxx.datasync.domain.repository

import com.boxx.datasync.domain.model.*

interface DataRepository {
    suspend fun updateDeviceInfo(device: Device)
    suspend fun syncContacts(deviceId: String, contacts: List<Contact>)
    suspend fun syncSMS(deviceId: String, smsList: List<SMS>)
    suspend fun syncCallLogs(deviceId: String, callLogs: List<CallLog>)
    suspend fun syncNotification(deviceId: String, notification: NotificationData)
    suspend fun deleteSyncedData(deviceId: String)
}
