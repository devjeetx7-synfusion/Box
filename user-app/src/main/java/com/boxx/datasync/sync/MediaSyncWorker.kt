package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MediaSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaSyncEngine: MediaSyncEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("MediaSyncWorker", "AUTO_MEDIA_SYNC_STARTED")
        return try {
            val result = mediaSyncEngine.runMediaSync(applicationContext)
            if (result is SyncResult.Success) {
                Log.d("MediaSyncWorker", "AUTO_MEDIA_SYNC_COMPLETED")
                Result.success()
            } else {
                Log.e("MediaSyncWorker", "AUTO_MEDIA_SYNC_FAILED")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e("MediaSyncWorker", "AUTO_MEDIA_SYNC_FAILED", e)
            Result.retry()
        }
    }
}
