package com.boxx.datasync.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class DataContentObserver(
    private val context: Context,
    handler: Handler
) : ContentObserver(handler) {

    private val workManager = WorkManager.getInstance(context)

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d("DataContentObserver", "CONTENT_OBSERVER_TRIGGERED for URI: $uri")
        Log.d("DataContentObserver", "AUTO_SYNC_TRIGGERED")

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(15, TimeUnit.SECONDS) // Increased debounce for stability
            .addTag("IncrementalSync")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            "IncrementalSync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }
}
