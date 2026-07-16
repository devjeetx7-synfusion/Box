package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncCoordinator @Inject constructor(
    private val syncEngine: SyncEngine
) {
    private val mutex = Mutex()
    private var isPendingSync = false
    private var pendingFullSync = false

    suspend fun performSync(context: Context, isFullSync: Boolean): SyncResult {
        // We always set pending flags even if we are the one who will acquire the lock,
        // it doesn't hurt as we clear them inside the loop.
        synchronized(this) {
            if (mutex.isLocked) {
                isPendingSync = true
                if (isFullSync) pendingFullSync = true
                Log.d("SyncCoordinator", "SYNC_ALREADY_RUNNING_PENDING_SET")
                return SyncResult.Success // Or return previous run's result, Success is safe for non-blocking trigger
            }
        }

        // Try to acquire the lock. If multiple threads reach here, only one gets it.
        mutex.withLock {
            Log.d("SyncCoordinator", "SYNC_COORDINATOR_LOCK_ACQUIRED")
            var currentFullSync = isFullSync
            var finalResult: SyncResult = SyncResult.Success

            while (true) {
                val fullSyncToRun = synchronized(this) {
                    val toRun = currentFullSync || pendingFullSync
                    isPendingSync = false
                    pendingFullSync = false
                    toRun
                }

                try {
                    finalResult = syncEngine.runSync(context, fullSyncToRun)
                } catch (e: CancellationException) {
                    Log.e("SyncCoordinator", "SYNC_CANCELLED_REASON: ${e.message}")
                    throw e
                } catch (e: Exception) {
                    Log.e("SyncCoordinator", "Sync failed in coordinator loop", e)
                    finalResult = SyncResult.Error(e.localizedMessage ?: "Unknown Coordinator Error")
                }

                synchronized(this) {
                    if (!isPendingSync) {
                        return finalResult
                    }
                    Log.d("SyncCoordinator", "PENDING_SYNC_STARTED")
                }
                currentFullSync = false
            }
        }
    }
}
