package com.boxx.datasync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val PERIODIC_SYNC_NAME = "PeriodicSync"
    const val EVENT_SYNC_NAME = "EventTriggeredIncrementalSync"
    const val KEY_FULL_SYNC = "full_sync"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueIncremental(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EVENT_SYNC_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
