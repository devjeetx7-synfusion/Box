package com.datasync.user.model

data class Contact(
    val name: String = "",
    val phone: String = "",
    val lastUpdated: Long = 0
)

data class SMS(
    val address: String = "",
    val body: String = "",
    val date: Long = 0,
    val type: Int = 1 // 1=inbox, 2=sent
)

data class DeviceInfo(
    val deviceName: String = "",
    val deviceId: String = "",
    val lastSyncTime: Long = 0,
    val contactCount: Int = 0,
    val smsCount: Int = 0,
    val timestamp: Long = 0
)
