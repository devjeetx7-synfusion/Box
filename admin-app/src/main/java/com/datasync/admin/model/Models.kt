package com.datasync.admin.model

data class Device(
    val deviceId: String = "",
    val deviceName: String = "",
    val lastSyncTime: Long = 0,
    val contactCount: Int = 0,
    val smsCount: Int = 0
)

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
