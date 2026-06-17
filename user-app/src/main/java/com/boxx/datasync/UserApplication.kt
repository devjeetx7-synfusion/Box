package com.boxx.datasync

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.boxx.datasync.utils.DeviceIdHelper
import com.boxx.datasync.utils.GlobalExceptionHandler
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

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

        // buildConfig is true in build.gradle.kts
        // crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)

        setupContentObservers()
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
