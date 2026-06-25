package com.boxx.datasync.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import com.boxx.datasync.utils.MediaHelper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaSyncEngine @Inject constructor(
    private val repository: DataRepository
) {
    suspend fun runMediaSync(context: Context): SyncResult {
        Log.d("MediaSyncEngine", "AUTO_MEDIA_SYNC_STARTED")
        val deviceId = DeviceIdHelper.getDeviceId(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        if (!prefs.getBoolean("auto_media_sync", false)) {
            Log.d("MediaSyncEngine", "AUTO_MEDIA_SYNC_DISABLED")
            return SyncResult.Success
        }

        val mediaImagesAllowed = hasPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) ||
                                 hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
                                 (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                                  hasPermission(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

        val mediaVideosAllowed = hasPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) ||
                                 hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ||
                                 (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                                  hasPermission(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

        Log.d("MediaSyncEngine", "AUTO_MEDIA_PERMISSION_STATUS images=$mediaImagesAllowed videos=$mediaVideosAllowed")

        if (!mediaImagesAllowed && !mediaVideosAllowed) {
            val error = "Media permission not granted. Images/videos cannot be synced."
            updateMediaError(deviceId, error)
            Log.e("MediaSyncEngine", "AUTO_MEDIA_SYNC_FAILED: $error")
            return SyncResult.PermissionMissing
        }

        val lastMediaSync = prefs.getLong("last_media_sync", 0L)
        Log.d("MediaSyncEngine", "MEDIASTORE_QUERY_STARTED")
        val newMedia = MediaHelper.fetchNewMedia(context, lastMediaSync)
        Log.d("MediaSyncEngine", "MEDIASTORE_QUERY_RESULT: ${newMedia.size} items")

        if (newMedia.isEmpty()) {
            Log.d("MediaSyncEngine", "AUTO_MEDIA_SYNC_COMPLETED: No new media")
            repository.updateDeviceInfoMap(deviceId, mapOf(
                "lastMediaSyncTime" to System.currentTimeMillis(),
                "lastMediaError" to null
            ))
            return SyncResult.Success
        }

        var failReason: String? = null

        newMedia.forEachIndexed { index, media ->
            val progress = "Syncing Media (${index + 1}/${newMedia.size})"
            Log.d("MediaSyncEngine", "MEDIA_UPLOAD_STARTED: ${media.fileName}")
            try {
                repository.updateDeviceInfoMap(deviceId, mapOf("syncStatus" to progress))
            } catch (_: Exception) {}

            val result = CloudinaryUploader.uploadMedia(context, media.uri, deviceId, media.id, media.mimeType)
            when (result) {
                is MediaUploadResult.Success -> {
                    Log.d("MediaSyncEngine", "MEDIA_UPLOAD_SUCCESS: ${media.fileName}")
                }
                is MediaUploadResult.Failed -> {
                    if (result.stage == "DUPLICATE_SKIPPED") {
                        Log.d("MediaSyncEngine", "MEDIA_DUPLICATE_SKIPPED: ${media.fileName}")
                    } else {
                        failReason = "${result.stage}: ${result.message}"
                        Log.e("MediaSyncEngine", "MEDIA_UPLOAD_FAILED: ${media.fileName} - $failReason")
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val updates = mutableMapOf<String, Any?>(
            "lastMediaSyncTime" to now,
            "lastMediaError" to failReason
        )

        if (failReason == null) {
            Log.d("MediaSyncEngine", "AUTO_MEDIA_SYNC_COMPLETED")
            prefs.edit().putLong("last_media_sync", now).apply()
            // We don't necessarily set status to "Synced" here because another sync might be running
            // but for a dedicated media sync worker, it makes sense.
            updates["syncStatus"] = "Synced"
        } else {
            Log.e("MediaSyncEngine", "AUTO_MEDIA_SYNC_FAILED: $failReason")
            updates["syncStatus"] = "Error"
        }

        repository.updateDeviceInfoMap(deviceId, updates)

        return if (failReason == null) SyncResult.Success else SyncResult.Error(failReason!!)
    }

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private suspend fun updateMediaError(deviceId: String, error: String) {
        repository.updateDeviceInfoMap(deviceId, mapOf(
            "lastMediaError" to error,
            "syncStatus" to "Error"
        ))
    }
}
