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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var deviceId: String

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
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val timestamp = sbn.postTime
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName
        }

        val notificationId = sbn.key.ifBlank { "$packageName-$timestamp" }
        val notificationData = NotificationData(
            id = notificationId,
            appName = appName,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = timestamp,
            groupKey = sbn.groupKey
        )

        Log.d("NotificationListener", "NOTIFICATION_RECEIVED")
        serviceScope.launch {
            repository.syncNotification(deviceId, notificationData)
            repository.incrementNotificationCount(deviceId)
            repository.updateHeartbeat(deviceId)
            Log.d("NotificationListener", "NOTIFICATION_UPLOAD_SUCCESS")
            Log.d("NotificationListener", "HEARTBEAT_UPDATED")
        }
    }
}
