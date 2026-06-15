package com.datasync.admin.domain.repository

import com.datasync.admin.model.*
import kotlinx.coroutines.flow.Flow

interface AdminRepository {
    fun getDevices(): Flow<List<Device>>
    fun getContacts(deviceId: String): Flow<List<Contact>>
    fun getSMS(deviceId: String): Flow<List<SMS>>
    fun getCallLogs(deviceId: String): Flow<List<CallLog>>
    fun getNotifications(deviceId: String): Flow<List<NotificationData>>
}
