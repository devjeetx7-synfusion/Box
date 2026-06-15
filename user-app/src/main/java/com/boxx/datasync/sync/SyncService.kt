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
import androidx.preference.PreferenceManager
import com.boxx.datasync.MainActivity
import com.boxx.datasync.data.MockDataGenerator
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

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debouncedSync()
        }
    }

    private var remoteSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdHelper.getDeviceId(this)
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

        try {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            Log.e("SyncService", "Permission denied for ContentObserver", e)
            crashlytics.recordException(e)
        }

        startRemoteSyncListener()
    }

    private fun startRemoteSyncListener() {
        var lastHandledSyncRequest = PreferenceManager.getDefaultSharedPreferences(this).getLong("last_handled_sync_request", 0L)
        remoteSyncJob?.cancel()
        remoteSyncJob = serviceScope.launch {
            repository.observeSyncRequests(deviceId).collect { requestedAt ->
                if (requestedAt > lastHandledSyncRequest) {
                    Log.d("SyncService", "Remote sync requested at: $requestedAt")
                    lastHandledSyncRequest = requestedAt
                    PreferenceManager.getDefaultSharedPreferences(this@SyncService).edit()
                        .putLong("last_handled_sync_request", lastHandledSyncRequest)
                        .apply()
                    performSync(isFullSync = true)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debouncedSync()
        return START_STICKY
    }

    private fun debouncedSync() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            delay(2000) // 2 seconds debounce
            performSync()
        }
    }

    private suspend fun performSync(isFullSync: Boolean = false) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDemoMode = prefs.getBoolean("demo_mode", false)

        try {
            val lastSmsSync = if (isFullSync) 0L else prefs.getLong("last_sms_sync", 0L)
            val lastCallSync = if (isFullSync) 0L else prefs.getLong("last_call_sync", 0L)
            val lastContactSync = if (isFullSync) 0L else prefs.getLong("last_contact_sync", 0L)

            val contacts = if (isDemoMode) {
                MockDataGenerator.generateMockContacts()
            } else {
                if (hasRequiredPermissions()) DataHelper.fetchContacts(this@SyncService, sinceTimestamp = lastContactSync) else emptyList()
            }

            val smsList = if (isDemoMode) {
                MockDataGenerator.generateMockSMS()
            } else {
                if (hasRequiredPermissions()) DataHelper.fetchSMS(this@SyncService, sinceTimestamp = lastSmsSync) else emptyList()
            }

            val callLogs = if (isDemoMode) {
                MockDataGenerator.generateMockCallLogs()
            } else {
                if (hasRequiredPermissions()) DataHelper.fetchCallLogs(this@SyncService, sinceTimestamp = lastCallSync) else emptyList()
            }

            repository.syncIncremental(deviceId, contacts, smsList, callLogs)

            val currentTime = System.currentTimeMillis()
            prefs.edit().apply {
                if (smsList.isNotEmpty()) putLong("last_sms_sync", smsList.maxOf { it.date })
                if (callLogs.isNotEmpty()) putLong("last_call_sync", callLogs.maxOf { it.date })
                if (contacts.isNotEmpty()) putLong("last_contact_sync", currentTime) // Contacts use currentTime as lastUpdated is not always reliable
            }.apply()

            repository.updateDeviceInfo(Device(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
                deviceId = deviceId,
                lastSyncTime = currentTime,
                contactCount = if (isDemoMode) contacts.size else DataHelper.fetchContacts(this).size,
                smsCount = if (isDemoMode) smsList.size else DataHelper.fetchSMS(this).size,
                callLogCount = if (isDemoMode) callLogs.size else DataHelper.fetchCallLogs(this).size,
                timestamp = currentTime,
                isDemoMode = isDemoMode,
                syncRequestedAt = prefs.getLong("last_handled_sync_request", 0L)
            ))
            Log.d("SyncService", "Sync completed successfully (Demo: $isDemoMode, Full: $isFullSync)")
        } catch (e: Exception) {
            Log.e("SyncService", "Error during sync", e)
            crashlytics.recordException(e)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CALL_LOG
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
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
        contentResolver.unregisterContentObserver(observer)
    }
}
