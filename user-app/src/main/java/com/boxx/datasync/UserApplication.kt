package com.boxx.datasync

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.boxx.datasync.utils.DeviceIdHelper
import com.boxx.datasync.utils.GlobalExceptionHandler
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.preference.PreferenceManager

@HiltAndroidApp
class UserApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private var dataContentObserver: com.boxx.datasync.sync.DataContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        GlobalExceptionHandler.initialize(this)
        FirebaseApp.initializeApp(this)

        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.setUserId(DeviceIdHelper.getDeviceId(this))

        setupContentObservers()
        observeSyncRequests()
    }

    private fun observeSyncRequests() {
        val deviceId = DeviceIdHelper.getDeviceId(this)
        if (deviceId.isBlank()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("UserApplication", "Error observing sync requests", error)
                    return@addSnapshotListener
                }

                snapshot?.let { doc ->
                    val requestedAt = doc.getLong("syncRequestedAt") ?: 0L
                    var lastHandledSyncRequest = prefs.getLong("last_handled_sync_request", 0L)

                    if (requestedAt > lastHandledSyncRequest) {
                        android.util.Log.d("Sync", "ADMIN_SYNC_REQUEST_RECEIVED")

                        prefs.edit().putLong("last_handled_sync_request", requestedAt).apply()

                        val intent = android.content.Intent(this, com.boxx.datasync.sync.SyncService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    }
                }
            }
    }

    fun setupContentObservers() {
        if (dataContentObserver == null) {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            dataContentObserver = com.boxx.datasync.sync.DataContentObserver(this, handler)
        }

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                contentResolver.registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true, dataContentObserver!!)
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                contentResolver.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, dataContentObserver!!)
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, dataContentObserver!!)
            }
        } catch (e: Exception) {
            android.util.Log.e("UserApplication", "Failed to register ContentObserver", e)
        }
    }
}
