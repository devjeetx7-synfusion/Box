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

        return when {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED -> {
                PermissionStatus.GRANTED
            }
            else -> PermissionStatus.DENIED
        }
    }

    fun isPermanentlyDenied(activity: android.app.Activity, info: PermissionInfo): Boolean {
        val perm = info.permission ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm) &&
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_DENIED
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
