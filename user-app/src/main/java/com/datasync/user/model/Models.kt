package com.datasync.user.model

data class Contact(
    val name: String,
    val phone: String,
    val lastUpdated: Long
)

data class SMS(
    val address: String,
    val body: String,
    val date: Long,
    val type: Int // 1=inbox, 2=sent
)

data class DeviceInfo(
    val deviceName: String,
    val deviceId: String,
    val lastSyncTime: Long,
    val contactCount: Int,
    val smsCount: Int,
    val timestamp: Long
)
