package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("SyncWorker", "WORKER_SYNC_STARTED")
        Log.d("SyncWorker", "WORKMANAGER_EVENT_SYNC_STARTED")
        return try {
            val isFullSync = inputData.getBoolean(SyncScheduler.KEY_FULL_SYNC, false)
            syncCoordinator.performSync(applicationContext, isFullSync)
            Log.d("SyncWorker", "WORKER_SYNC_SUCCESS")
            Log.d("SyncWorker", "WORKMANAGER_EVENT_SYNC_SUCCESS")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "WORKER_SYNC_FAILED", e)
            Result.retry()
        }
    }
}
