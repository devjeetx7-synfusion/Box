package com.datasync.admin.model

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class Command(
    val id: String = "",
    val type: String = "",
    val payload: Map<String, Any> = emptyMap(),
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
    val requestedBy: String = "Admin"
)

@IgnoreExtraProperties
data class SmsForwardingConfig(
    val enabled: Boolean = false,
    val destinationNumber: String = "",
    val simSlot: Int = 0,
    val updatedAt: Long = 0
)
