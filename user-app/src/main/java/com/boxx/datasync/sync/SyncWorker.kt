package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
            val result = syncCoordinator.performSync(applicationContext, isFullSync)

            when (result) {
                is SyncResult.Success -> {
                    Log.d("SyncWorker", "WORKER_SYNC_SUCCESS")
                    Log.d("SyncWorker", "WORKMANAGER_EVENT_SYNC_SUCCESS")
                    Result.success()
                }
                is SyncResult.PartialSuccess -> {
                    Log.d("SyncWorker", "WORKER_SYNC_PARTIAL_SUCCESS")
                    Result.success(workDataOf("message" to result.message))
                }
                is SyncResult.NetworkUnavailable -> {
                    Log.w("SyncWorker", "WORKER_RESULT_RETRY - Network Unavailable")
                    Result.retry()
                }
                is SyncResult.PermissionMissing -> {
                    Log.e("SyncWorker", "WORKER_RESULT_FAILURE - Permissions Missing")
                    Result.failure(workDataOf("message" to "Missing required runtime permissions"))
                }
                is SyncResult.Error -> {
                    Log.e("SyncWorker", "WORKER_RESULT_RETRY - General Sync Error")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "WORKER_SYNC_FAILED", e)
            Result.retry()
        }
    }
}
