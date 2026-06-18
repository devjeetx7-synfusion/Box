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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val syncRunnable = Runnable {
        Log.d("NotificationListener", "SYNC_TRIGGER_RECEIVED")
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

        Log.d("NotificationListener", "NOTIFICATION_RECEIVED: $packageName")

        val extras = sbn.notification.extras
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

        // Try to extract messages for MessagingStyle (WhatsApp, Telegram, etc.)
        val messages = mutableListOf<NotificationData>()
        val messagingStyleMessages = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)

        val conversationId = sbn.notification.shortcutId ?: sbn.groupKey ?: packageName
        val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""

        if (messagingStyleMessages != null && messagingStyleMessages.isNotEmpty()) {
            for (msgParcelable in messagingStyleMessages) {
                if (msgParcelable is android.os.Bundle) {
                    val msgText = msgParcelable.getCharSequence("text")?.toString() ?: ""
                    val msgTimestamp = msgParcelable.getLong("time")
                    val personBundle = msgParcelable.getParcelable<android.os.Bundle>("sender_person")
                    val msgSender = personBundle?.getCharSequence("name")?.toString() ?: extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""

                    if (msgText.isNotBlank()) {
                        val messageId = hashString("$packageName-$conversationId-$msgTimestamp-$msgText")
                        messages.add(NotificationData(
                            id = messageId,
                            appName = appName,
                            packageName = packageName,
                            title = title,
                            text = msgText,
                            timestamp = if (msgTimestamp > 0) msgTimestamp else timestamp,
                            groupKey = sbn.groupKey,
                            iconBase64 = iconBase64,
                            sender = msgSender,
                            conversationId = conversationId
                        ))
                    }
                }
            }
        }

        // If no messages extracted from MessagingStyle, use the main notification content
        if (messages.isEmpty()) {
            val text = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            val sender = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""

            if (text.isNotBlank()) {
                val notificationId = sbn.key.ifBlank { "$packageName-$timestamp" }
                messages.add(NotificationData(
                    id = notificationId,
                    appName = appName,
                    packageName = packageName,
                    title = title,
                    text = text,
                    timestamp = timestamp,
                    groupKey = sbn.groupKey,
                    iconBase64 = iconBase64,
                    sender = sender,
                    conversationId = conversationId
                ))
            }
        }

        serviceScope.launch {
            try {
                for (notificationData in messages) {
                    repository.syncNotification(deviceId, notificationData)
                }
                if (messages.isNotEmpty()) {
                    repository.incrementNotificationCount(deviceId)
                    repository.updateHeartbeat(deviceId)
                    Log.d("NotificationListener", "NOTIFICATION_UPLOAD_SUCCESS: count=${messages.size}")
                    Log.d("NotificationListener", "ADMIN_REALTIME_UPDATED")

                    handler.removeCallbacks(syncRunnable)
                    handler.postDelayed(syncRunnable, 10000)
                }
            } catch (e: Exception) {
                Log.e("NotificationListener", "NOTIFICATION_UPLOAD_FAILED", e)
            }
        }
    }

    private fun hashString(input: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
