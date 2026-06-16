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

        repository.updateDeviceInfo(
            Device(
                deviceName = Build.MODEL,
                deviceId = deviceId,
                lastSyncTime = System.currentTimeMillis(),
                contactCount = contacts.size,
                smsCount = smsList.size,
                callLogCount = callLogs.size,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
