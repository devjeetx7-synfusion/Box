package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxx.datasync.data.local.ProfileDraftDao
import com.boxx.datasync.data.local.ProfileDraftEntity
import com.boxx.datasync.domain.model.DeviceUserDetails
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ProfileDetailsSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val profileDraftDao: ProfileDraftDao,
    private val repository: DataRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("ProfileDetailsSyncW", "PROFILE_DETAILS_SYNC_WORKER_STARTED")
        val deviceId = DeviceIdHelper.getDeviceId(applicationContext)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        return try {
            val draft = profileDraftDao.getDraft()
            if (draft != null && draft.isPendingSync) {
                val details = DeviceUserDetails(
                    deviceId = deviceId,
                    fullName = draft.fullName,
                    primaryPhone = draft.primaryPhone,
                    alternatePhone = draft.alternatePhone,
                    email = draft.email,
                    dateOfBirth = draft.dateOfBirth,
                    gender = draft.gender,
                    city = draft.city,
                    state = draft.state,
                    address = draft.address,
                    postalCode = draft.postalCode,
                    occupation = draft.occupation,
                    emergencyContactName = draft.emergencyContactName,
                    emergencyContactNumber = draft.emergencyContactNumber,
                    notes = draft.notes,
                    deviceName = deviceName,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    clientRevision = draft.localRevision
                )

                // Write to Firestore
                repository.saveUserDetails(deviceId, details)
                Log.d("ProfileDetailsSyncW", "PROFILE_DETAILS_SYNC_WORKER_SUCCESS")

                // Update draft in database
                profileDraftDao.insert(
                    draft.copy(isPendingSync = false, lastSyncedRevision = draft.localRevision)
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("ProfileDetailsSyncW", "PROFILE_DETAILS_SYNC_WORKER_FAILED", e)
            Result.retry()
        }
    }
}
