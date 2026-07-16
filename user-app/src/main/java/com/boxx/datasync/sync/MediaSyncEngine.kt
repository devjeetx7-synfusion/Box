package com.boxx.datasync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.boxx.datasync.data.local.MediaUploadStateDao
import com.boxx.datasync.data.local.MediaUploadStateEntity
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import com.boxx.datasync.utils.MediaHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaSyncEngine @Inject constructor(
    private val repository: DataRepository,
    private val mediaUploadStateDao: MediaUploadStateDao
) {
    private val mediaMutex = Mutex()

    suspend fun runMediaSync(context: Context): SyncResult {
        return mediaMutex.withLock {
            runMediaSyncInternal(context)
        }
    }

    private suspend fun runMediaSyncInternal(context: Context): SyncResult {
        Log.d("MediaSyncEngine", "AUTO_MEDIA_SYNC_STARTED")
        val deviceId = DeviceIdHelper.getDeviceId(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val autoMediaSyncEnabled = prefs.getBoolean("auto_media_sync", false)
        if (!autoMediaSyncEnabled) {
            Log.d("MediaSyncEngine", "AUTO_MEDIA_SYNC_DISABLED")
            updateDeviceMediaStatus(deviceId, autoMediaSyncEnabled, "Idle")
            return SyncResult.Success
        }

        // Check Network
        if (!isNetworkAvailable(context)) {
            val error = "Network unavailable"
            Log.e("MediaSyncEngine", "AUTO_MEDIA_SYNC_FAILED: $error")
            updateDeviceMediaStatus(deviceId, autoMediaSyncEnabled, "Error", error, MediaFailureStage.NETWORK.name)
            return SyncResult.Error(error)
        }

        // Check Permissions
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
            Log.e("MediaSyncEngine", "AUTO_MEDIA_SYNC_FAILED: $error")
            updateDeviceMediaStatus(deviceId, autoMediaSyncEnabled, "Error", error, MediaFailureStage.PERMISSION.name)
            return SyncResult.PermissionMissing
        }

        val lastMediaSync = prefs.getLong("last_media_sync", 0L)
        Log.d("MediaSyncEngine", "MEDIASTORE_QUERY_STARTED")
        updateDeviceMediaStatus(deviceId, autoMediaSyncEnabled, "Scanning media")

        val newMedia = MediaHelper.fetchNewMedia(context, lastMediaSync)
        Log.d("MediaSyncEngine", "MEDIASTORE_QUERY_RESULT: ${newMedia.size} items")

        // 1. Discovery phase: Save newly found media to Room
        var newDiscoveredCount = 0
        newMedia.forEach { media ->
            val key = "${media.type}*external*${media.id}_${media.modifiedAt}"
            val existing = mediaUploadStateDao.getByKey(key)
            if (existing == null) {
                val state = MediaUploadStateEntity(
                    localMediaKey = key,
                    mediaStoreId = media.id,
                    dateAdded = media.createdAt,
                    dateModified = media.modifiedAt,
                    uploadStatus = "DISCOVERED",
                    attemptCount = 0,
                    lastAttemptAt = 0L,
                    lastError = null,
                    cloudinaryPublicId = null,
                    secureUrl = null,
                    format = null,
                    bytes = media.sizeBytes,
                    width = media.width,
                    height = media.height,
                    duration = media.duration
                )
                mediaUploadStateDao.insert(state)
                newDiscoveredCount++
            }
        }

        Log.d("MediaSyncEngine", "Discovered $newDiscoveredCount new media items.")

        if (newMedia.isNotEmpty()) {
            val maxDateAdded = newMedia.maxOf { it.createdAt }
            prefs.edit().putLong("last_media_sync", maxDateAdded).apply()
        }

        // 2. Query all pending/failed items from Room DB
        val pendingUploads = mediaUploadStateDao.getPendingUploads()
        val cloudinaryUploadedButPendingMetadata = queryCloudinaryUploadedPendingMetadata()

        val allItemsToProcess = (pendingUploads + cloudinaryUploadedButPendingMetadata).distinctBy { it.localMediaKey }
        if (allItemsToProcess.isEmpty()) {
            Log.d("MediaSyncEngine", "No pending uploads or unsaved metadata in DB.")
            updateDeviceMediaStatus(deviceId, autoMediaSyncEnabled, "Synced")
            return SyncResult.Success
        }

        // Process in a bounded batch of 15 items per execution to prevent extreme background usage
        val batchLimit = 15
        val batchToProcess = allItemsToProcess.take(batchLimit)
        Log.d("MediaSyncEngine", "Processing media batch: size=${batchToProcess.size}")

        var failReason: String? = null
        var failStage: String? = null

        batchToProcess.forEachIndexed { index, entity ->
            val progressStatus = "Uploading media (${index + 1}/${batchToProcess.size})"
            updateDeviceMediaStatus(deviceId, autoMediaSyncEnabled, progressStatus)

            val isVideo = entity.localMediaKey.startsWith("video")
            val mediaId = "${if (isVideo) "video" else "image"}_${entity.mediaStoreId}"

            // If it's already CLOUDINARY_UPLOADED, do metadata-only retry without re-uploading binary (Phase 8)!
            if (entity.uploadStatus == "CLOUDINARY_UPLOADED") {
                Log.d("MediaSyncEngine", "METADATA_ONLY_RETRY: key=${entity.localMediaKey}")
                try {
                    CloudinaryUploader.saveManualToFirestore(
                        deviceId = deviceId,
                        mediaId = mediaId,
                        type = if (isVideo) "video" else "image",
                        secureUrl = entity.secureUrl ?: "",
                        publicId = entity.cloudinaryPublicId ?: "",
                        format = entity.format,
                        bytes = entity.bytes,
                        localMediaKey = entity.localMediaKey,
                        dateAdded = entity.dateAdded,
                        dateModified = entity.dateModified,
                        uploadStatus = "METADATA_SAVED",
                        detectedMime = if (isVideo) "video/*" else "image/*"
                    )
                    mediaUploadStateDao.markCloudinaryUploaded(
                        key = entity.localMediaKey,
                        status = "METADATA_SAVED",
                        secureUrl = entity.secureUrl ?: "",
                        publicId = entity.cloudinaryPublicId ?: "",
                        format = entity.format,
                        bytes = entity.bytes,
                        width = entity.width,
                        height = entity.height,
                        duration = entity.duration
                    )
                    Log.d("MediaSyncEngine", "METADATA_ONLY_RETRY_SUCCESS: key=${entity.localMediaKey}")
                } catch (e: Exception) {
                    failReason = e.localizedMessage ?: "Firestore metadata save failed on retry"
                    failStage = MediaFailureStage.FIRESTORE_METADATA.name
                    Log.e("MediaSyncEngine", "METADATA_ONLY_RETRY_FAILED: key=${entity.localMediaKey} - $failReason")
                    mediaUploadStateDao.updateStatusAndError(entity.localMediaKey, "CLOUDINARY_UPLOADED", failReason, System.currentTimeMillis())
                }
                return@forEachIndexed
            }

            Log.d("MediaSyncEngine", "MEDIA_UPLOAD_STARTED: key=${entity.localMediaKey}")
            mediaUploadStateDao.updateStatusAndError(entity.localMediaKey, "UPLOADING", null, System.currentTimeMillis())

            // Safeguards: size limits (50MB image, 100MB video)
            val sizeLimit = if (isVideo) 100 * 1024 * 1024L else 50 * 1024 * 1024L
            if (entity.bytes > sizeLimit) {
                val limitErr = "File size ${entity.bytes} bytes exceeds allowed limit."
                mediaUploadStateDao.updateStatusAndError(entity.localMediaKey, "FAILED_PERMANENT", limitErr, System.currentTimeMillis())
                Log.e("MediaSyncEngine", "MEDIA_UPLOAD_FAILED: ${entity.localMediaKey} - $limitErr")
                return@forEachIndexed
            }

            // Perform upload
            val contentUri = android.content.ContentUris.withAppendedId(
                if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                entity.mediaStoreId.toLong()
            )

            val result = CloudinaryUploader.uploadMedia(
                context = context,
                uri = contentUri,
                deviceId = deviceId,
                mediaStoreId = entity.mediaStoreId,
                mimeTypeHint = if (isVideo) "video/*" else "image/*",
                commandId = null,
                localMediaKey = entity.localMediaKey,
                dateAdded = entity.dateAdded,
                dateModified = entity.dateModified,
                uploadStatus = "METADATA_SAVED"
            )

            when (result) {
                is MediaUploadResult.Success -> {
                    Log.d("MediaSyncEngine", "MEDIA_UPLOAD_SUCCESS: key=${entity.localMediaKey}")
                    mediaUploadStateDao.markCloudinaryUploaded(
                        key = entity.localMediaKey,
                        status = "METADATA_SAVED",
                        secureUrl = result.secureUrl,
                        publicId = result.publicId,
                        format = result.format,
                        bytes = result.bytes,
                        width = result.width,
                        height = entity.height,
                        duration = entity.duration
                    )
                }
                is MediaUploadResult.Failure -> {
                    failReason = result.message
                    failStage = result.stage.name
                    Log.e("MediaSyncEngine", "MEDIA_UPLOAD_FAILED: key=${entity.localMediaKey} - $failReason")

                    // Preserve locally if Cloudinary succeeded but Firestore failed (Phase 8)!
                    if (result.stage == MediaFailureStage.FIRESTORE_METADATA && result.secureUrl != null && result.publicId != null) {
                        Log.d("MediaSyncEngine", "PRESERVING_LOCAL_STATE: status=CLOUDINARY_UPLOADED")
                        mediaUploadStateDao.markCloudinaryUploaded(
                            key = entity.localMediaKey,
                            status = "CLOUDINARY_UPLOADED",
                            secureUrl = result.secureUrl!!,
                            publicId = result.publicId!!,
                            format = result.format,
                            bytes = result.bytes ?: entity.bytes,
                            width = entity.width,
                            height = entity.height,
                            duration = entity.duration
                        )
                    } else {
                        val nextStatus = if (!result.retryable || entity.attemptCount >= 4) {
                            "FAILED_PERMANENT"
                        } else {
                            "FAILED_RETRYABLE"
                        }
                        mediaUploadStateDao.updateStatusAndError(entity.localMediaKey, nextStatus, result.message, System.currentTimeMillis())
                    }
                }
            }
        }

        // Final stats upload to Firestore device document (Phase 13, 14, 15)
        updateDeviceMediaStatus(
            deviceId = deviceId,
            autoMediaSyncEnabled = autoMediaSyncEnabled,
            statusString = if (failReason == null) "Synced" else "Partial success",
            errorMsg = failReason,
            errorStage = failStage
        )

        return if (failReason == null) SyncResult.Success else SyncResult.Error(failReason!!)
    }

    private suspend fun queryCloudinaryUploadedPendingMetadata(): List<MediaUploadStateEntity> {
        return try {
            // Find records in our database where uploadStatus == "CLOUDINARY_UPLOADED"
            // We can query this by directly calling a query or utilizing pending list if we update it.
            // Let's add a clean DAO query for this.
            val all = mediaUploadStateDao.getPendingUploads()
            // Wait, getPendingUploads only queries 'DISCOVERED', 'QUEUED', 'FAILED_RETRYABLE'.
            // Let's check how we can get 'CLOUDINARY_UPLOADED'. We will add it to the Dao query or add a separate Dao method!
            // Let's check what's inside the DAO. Let's add a separate query getCloudinaryUploaded().
            // Wait, we can fetch all in a custom DAO query!
            mediaUploadStateDao.getCloudinaryUploaded()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private suspend fun updateDeviceMediaStatus(
        deviceId: String,
        autoMediaSyncEnabled: Boolean,
        statusString: String,
        errorMsg: String? = null,
        errorStage: String? = null
    ) {
        try {
            val discoveredCount = mediaUploadStateDao.getDiscoveredCount()
            val uploadedCount = mediaUploadStateDao.getUploadedCount()
            val failedCount = mediaUploadStateDao.getFailedCount()

            val updates = mapOf<String, Any?>(
                "mediaSyncStatus" to statusString,
                "lastMediaError" to errorMsg,
                "lastMediaErrorStage" to errorStage,
                "autoMediaSyncEnabled" to autoMediaSyncEnabled,
                "lastMediaScanAt" to System.currentTimeMillis(),
                "mediaDiscoveredCount" to discoveredCount,
                "mediaUploadedCount" to uploadedCount,
                "mediaFailedCount" to failedCount
            )
            repository.updateDeviceInfoMap(deviceId, updates)
        } catch (e: Exception) {
            Log.e("MediaSyncEngine", "Error updating device media status", e)
        }
    }
}
