package com.boxx.datasync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.boxx.datasync.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRequests = AtomicInteger(0)

    @Inject lateinit var syncCoordinator: SyncCoordinator

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(createNotification())
        Log.d("SyncService", "FOREGROUND_SYNC_STARTED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SyncService", "SYNC_TRIGGER_RECEIVED")
        activeRequests.incrementAndGet()
        val isFullSync = intent?.getBooleanExtra(SyncScheduler.KEY_FULL_SYNC, false) == true
        serviceScope.launch {
            try {
                syncCoordinator.performSync(this@SyncService, isFullSync)
            } finally {
                if (activeRequests.decrementAndGet() == 0) {
                    Log.d("SyncService", "FOREGROUND_SYNC_STOPPED")
                    stopSelf()
                } else {
                    Log.d("SyncService", "FOREGROUND_SYNC_CONTINUING - pending requests exist")
                    // If we use stopSelf(startId), it might stop even if other tasks are running in the same coordinator loop.
                    // But here we use activeRequests to only stop when all started intents are "done".
                    // Actually stopSelf(startId) is the correct Android way if we want to be precise about intents.
                    // But since our coordinator handles multiple triggers in one loop, we just need to ensure the service
                    // stays alive as long as ANY thread is still in performSync.
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel("sync_channel", "Data Sync Service", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "sync_channel")
            .setContentTitle("Data Sync")
            .setContentText("Syncing educational data")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }
}
