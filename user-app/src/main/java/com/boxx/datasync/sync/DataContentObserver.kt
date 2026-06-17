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
    private val handler: Handler
) : ContentObserver(handler) {

    private val workManager = WorkManager.getInstance(context)
    private var syncRunnable: Runnable? = null

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d("DataContentObserver", "CONTENT_OBSERVER_TRIGGERED for URI: $uri")

        // Debounce logic using Handler
        syncRunnable?.let { handler.removeCallbacks(it) }

        syncRunnable = Runnable {
            Log.d("DataContentObserver", "AUTO_SYNC_TRIGGERED")
            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
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

        handler.postDelayed(syncRunnable!!, 15000) // 15 seconds debounce
    }
}
