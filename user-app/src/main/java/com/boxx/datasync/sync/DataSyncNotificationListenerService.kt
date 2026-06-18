package com.boxx.datasync.sync

import android.service.notification.NotificationListenerService
import android.util.Log
import android.service.notification.StatusBarNotification
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.domain.model.NotificationData
import com.boxx.datasync.utils.DeviceIdHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DataSyncNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var repository: DataRepository

    @Inject
    lateinit var syncCoordinator: SyncCoordinator

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var deviceId: String
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncRunnable = Runnable {
        SyncScheduler.enqueueIncremental(this)
    }

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdHelper.getDeviceId(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationListener", "NOTIFICATION_LISTENER_CONNECTED")
        serviceScope.launch { repository.updateHeartbeat(deviceId) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d("NotificationListener", "NOTIFICATION_LISTENER_DISCONNECTED")
        serviceScope.launch { repository.updateHeartbeat(deviceId) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        if (packageName == "android" || packageName == "com.android.systemui" || packageName == "com.android.providers.downloads") {
            return
        }

        Log.d("NotificationListener", "NOTIFICATION_RECEIVED")

        val extras = sbn.notification.extras
        val title = extras.getString(android.app.Notification.EXTRA_TITLE_BIG) ?: extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        val timestamp = sbn.postTime
        var appName = packageName
        var iconBase64 = ""

        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appName = packageManager.getApplicationLabel(appInfo).toString()

            val iconDrawable = packageManager.getApplicationIcon(appInfo)
            val bitmap = android.graphics.Bitmap.createBitmap(
                iconDrawable.intrinsicWidth.coerceAtLeast(1),
                iconDrawable.intrinsicHeight.coerceAtLeast(1),
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            iconDrawable.setBounds(0, 0, canvas.width, canvas.height)
            iconDrawable.draw(canvas)

            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
            iconBase64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e("NotificationListener", "Error extracting app info/icon", e)
        }

        val notificationId = sbn.key.ifBlank { "$packageName-$timestamp" }
        val notificationData = NotificationData(
            id = notificationId,
            appName = appName,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = timestamp,
            groupKey = sbn.groupKey,
            iconBase64 = iconBase64
        )

        serviceScope.launch {
            try {
                repository.syncNotification(deviceId, notificationData)
                repository.incrementNotificationCount(deviceId)
                repository.updateHeartbeat(deviceId)
                Log.d("NotificationListener", "NOTIFICATION_UPLOAD_SUCCESS")
                Log.d("NotificationListener", "ADMIN_REALTIME_UPDATED")

                // Trigger an incremental sync via SyncCoordinator for other data if needed,
                // but at least ensure counts/heartbeat are updated in Firestore immediately.
                // Using the handler to debounce and enqueue a full sync check via WorkManager
                handler.removeCallbacks(syncRunnable)
                handler.postDelayed(syncRunnable, 10000)
            } catch (e: Exception) {
                Log.e("NotificationListener", "NOTIFICATION_UPLOAD_FAILED", e)
            }
        }
    }
}
