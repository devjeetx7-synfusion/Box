package com.boxx.datasync.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log

class MediaContentObserver(
    private val context: Context,
    private val debounceMs: Long = 4_000L
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable {
        Log.d("MediaContentObserver", "MEDIA_OBSERVER_TRIGGERED")
        Log.d("MediaContentObserver", "MEDIA_WORK_ENQUEUED")
        SyncScheduler.enqueueMediaSync(context.applicationContext)
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, debounceMs)
    }
}
