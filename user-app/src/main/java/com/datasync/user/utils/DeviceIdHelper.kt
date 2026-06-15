package com.datasync.user.utils

import android.content.Context
import android.provider.Settings
import java.util.UUID

object DeviceIdHelper {
    private const val PREFS_NAME = "device_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)

        if (deviceId == null) {
            deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (deviceId.isNullOrEmpty()) {
                deviceId = UUID.randomUUID().toString()
            }
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }
}
