package com.datasync.admin.domain.repository

import com.datasync.admin.model.*
import kotlinx.coroutines.flow.Flow

interface AdminRepository {
    fun getDevices(): Flow<List<Device>>
    fun getDevice(deviceId: String): Flow<Device?>
    fun getContacts(deviceId: String): Flow<List<Contact>>
    fun getSMS(deviceId: String): Flow<List<SMS>>
    fun getCallLogs(deviceId: String): Flow<List<CallLog>>
    fun getNotifications(deviceId: String): Flow<List<NotificationData>>
    fun getMedia(deviceId: String): Flow<List<MediaData>>
    suspend fun requestSync(deviceId: String)
    suspend fun sendCommand(deviceId: String, command: Command): String
    fun getCommand(deviceId: String, commandId: String): Flow<Command?>
    fun getSmsForwardingConfig(deviceId: String): Flow<SmsForwardingConfig?>
    suspend fun deleteItem(deviceId: String, collection: String, itemId: String)
    suspend fun deleteMedia(deviceId: String, mediaId: String)
    suspend fun deleteAllItems(deviceId: String, collection: String)
    suspend fun deleteDevice(deviceId: String)
}
