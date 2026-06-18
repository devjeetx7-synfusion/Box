package com.boxx.datasync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SyncScheduler.schedulePeriodic(context)
            SyncScheduler.enqueueIncremental(context)
        }
    }
}
