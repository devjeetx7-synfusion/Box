package com.boxx.datasync.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log

class DataContentObserver(
    private val context: Context,
    private val debounceMs: Long = 2_000L
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable {
        Log.d("DataContentObserver", "CONTENT_OBSERVER_TRIGGERED")
        SyncScheduler.enqueueIncremental(context.applicationContext)
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, debounceMs)
    }
}
