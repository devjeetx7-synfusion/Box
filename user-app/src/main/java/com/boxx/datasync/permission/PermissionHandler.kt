package com.boxx.datasync.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class PermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
    NEEDS_SETTINGS
}

data class PermissionInfo(
    val id: String,
    val title: String,
    val description: String,
    val icon: String, // We'll use names to map to icons in the UI
    val permission: String? = null, // For runtime permissions
    val isOptional: Boolean = false,
    val isSpecial: Boolean = false
)

class PermissionHandler(private val context: Context) {

    private val prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)

    fun markRequested(permissions: Array<String>) {
        val editor = prefs.edit()
        permissions.forEach { perm ->
            editor.putBoolean("requested_$perm", true)
        }
        editor.apply()
    }

    private fun hasRequested(permission: String): Boolean {
        return prefs.getBoolean("requested_$permission", false)
    }

    fun getStatus(info: PermissionInfo): PermissionStatus {
        if (info.isSpecial) {
            return when (info.id) {
                "NOTIFICATION_LISTENER" -> {
                    if (isNotificationListenerEnabled()) PermissionStatus.GRANTED
                    else PermissionStatus.NEEDS_SETTINGS
                }
                "BATTERY_OPTIMIZATION" -> {
                    if (isBatteryOptimizationDisabled()) PermissionStatus.GRANTED
                    else PermissionStatus.NEEDS_SETTINGS
                }
                else -> PermissionStatus.DENIED
            }
        }

        val perm = info.permission ?: return PermissionStatus.GRANTED

        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionStatus.GRANTED
        }

        // Handle Android 14+ partial media access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (perm == Manifest.permission.READ_MEDIA_IMAGES || perm == Manifest.permission.READ_MEDIA_VIDEO)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
                return PermissionStatus.GRANTED
            }
        }

        return when {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED -> {
                PermissionStatus.GRANTED
            }
            else -> PermissionStatus.DENIED
        }
    }

    fun isPermanentlyDenied(activity: android.app.Activity, info: PermissionInfo): Boolean {
        val perm = info.permission ?: return false
        // A permission is only permanently denied if it has been requested before,
        // it is denied, and shouldShowRequestPermissionRationale returns false.
        val isDenied = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_DENIED
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        return isDenied && !shouldShowRationale && hasRequested(perm)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    fun getSettingsIntent(info: PermissionInfo): Intent {
        return when (info.id) {
            "NOTIFICATION_LISTENER" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "BATTERY_OPTIMIZATION" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            else -> getAppSettingsIntent()
        }
    }

    fun getAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
