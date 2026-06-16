package com.boxx.datasync.domain.usecase

import android.os.Build
import com.boxx.datasync.domain.model.Device
import com.boxx.datasync.domain.repository.DataRepository
import javax.inject.Inject

class SyncDataUseCase @Inject constructor(
    private val repository: DataRepository
) {
    suspend operator fun invoke(
        deviceId: String,
        contacts: List<com.boxx.datasync.domain.model.Contact>,
        smsList: List<com.boxx.datasync.domain.model.SMS>,
        callLogs: List<com.boxx.datasync.domain.model.CallLog>
    ) {
        repository.syncContacts(deviceId, contacts)
        repository.syncSMS(deviceId, smsList)
        repository.syncCallLogs(deviceId, callLogs)

        repository.updateDeviceInfoMap(
            deviceId,
            mapOf(
                "deviceName" to Build.MODEL,
                "lastSyncTime" to System.currentTimeMillis(),
                "heartbeatAt" to System.currentTimeMillis(),
                "contactCount" to contacts.size,
                "smsCount" to smsList.size,
                "callCount" to callLogs.size,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }
}
