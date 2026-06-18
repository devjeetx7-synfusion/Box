package com.boxx.datasync.domain.repository

import com.boxx.datasync.domain.model.*

interface DataRepository {
    suspend fun updateDeviceInfo(device: Device)
    suspend fun updateDeviceInfoMap(deviceId: String, updates: Map<String, Any?>)
    suspend fun testDeviceWrite(deviceId: String)
    suspend fun syncContacts(deviceId: String, contacts: List<Contact>)
    suspend fun syncSMS(deviceId: String, smsList: List<SMS>)
    suspend fun syncCallLogs(deviceId: String, callLogs: List<CallLog>)
    suspend fun syncNotification(deviceId: String, notification: NotificationData)
    suspend fun deleteSyncedData(deviceId: String)
    suspend fun syncIncremental(deviceId: String, contacts: List<Contact>, smsList: List<SMS>, callLogs: List<CallLog>)
    fun observeSyncRequests(deviceId: String): kotlinx.coroutines.flow.Flow<Long>
    suspend fun incrementNotificationCount(deviceId: String)
    suspend fun updateHeartbeat(deviceId: String)
}
