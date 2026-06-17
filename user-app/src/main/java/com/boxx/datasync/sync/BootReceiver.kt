package com.boxx.datasync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.boxx.datasync.UserApplication
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "AUTO_SYNC_RESTORED_AFTER_REBOOT")

            // Reschedule Periodic Work
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "PeriodicSync",
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )

            // Re-initialize ContentObservers
            (context.applicationContext as? UserApplication)?.setupContentObservers()
        }
    }
}
