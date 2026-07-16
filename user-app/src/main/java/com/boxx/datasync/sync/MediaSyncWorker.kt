package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.boxx.datasync.data.local.MediaUploadStateDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MediaSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaSyncEngine: MediaSyncEngine,
    private val mediaUploadStateDao: MediaUploadStateDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("MediaSyncWorker", "AUTO_MEDIA_SYNC_STARTED")
        return try {
            val result = mediaSyncEngine.runMediaSync(applicationContext)

            val discovered = mediaUploadStateDao.getDiscoveredCount()
            val uploaded = mediaUploadStateDao.getUploadedCount()
            val failed = mediaUploadStateDao.getFailedCount()

            val outputData = workDataOf(
                "uploadedCount" to uploaded,
                "failedCount" to failed,
                "discoveredCount" to discovered
            )

            if (result is SyncResult.Success) {
                Log.d("MediaSyncWorker", "AUTO_MEDIA_SYNC_COMPLETED")
                Result.success(outputData)
            } else {
                Log.e("MediaSyncWorker", "AUTO_MEDIA_SYNC_FAILED")
                Result.failure(outputData)
            }
        } catch (e: Exception) {
            Log.e("MediaSyncWorker", "AUTO_MEDIA_SYNC_FAILED", e)
            Result.retry()
        }
    }
}
