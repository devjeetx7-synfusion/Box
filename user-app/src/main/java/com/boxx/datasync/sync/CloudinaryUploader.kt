package com.boxx.datasync.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.boxx.datasync.domain.model.MediaData
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class MediaFailureStage {
    PERMISSION,
    MEDIASTORE_QUERY,
    URI_OPEN,
    TEMP_FILE_COPY,
    MIME_DETECTION,
    NETWORK,
    CLOUDINARY_HTTP,
    CLOUDINARY_PARSE,
    FIRESTORE_METADATA,
    CANCELLED,
    UNKNOWN
}

sealed class MediaUploadResult {
    data class Success(
        val secureUrl: String,
        val publicId: String,
        val resourceType: String,
        val format: String?,
        val bytes: Long,
        val width: Int?,
        val height: Int?,
        val duration: Double?,
        val rawResponse: String
    ) : MediaUploadResult()

    data class Failure(
        val stage: MediaFailureStage,
        val message: String,
        val httpCode: Int? = null,
        val cloudinaryError: String? = null,
        val rawResponse: String? = null,
        val retryable: Boolean,
        val secureUrl: String? = null,
        val publicId: String? = null,
        val format: String? = null,
        val bytes: Long? = null
    ) : MediaUploadResult()
}

object CloudinaryUploader {
    private val client = OkHttpClient()

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        deviceId: String,
        mediaStoreId: String,
        mimeTypeHint: String? = null,
        commandId: String? = null,
        localMediaKey: String? = null,
        dateAdded: Long = 0,
        dateModified: Long = 0,
        uploadStatus: String = "METADATA_SAVED"
    ): MediaUploadResult {
        return withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            var response: Response? = null
            var responseBody: ResponseBody? = null
            var tempFile: File? = null

            try {
                Log.d("CloudinaryUploader", "CLOUDINARY_REQUEST_STARTED")
                Log.d("CloudinaryUploader", "MEDIA_URI_SELECTED: $uri")

                val mimeType = mimeTypeHint ?: context.contentResolver.getType(uri)
                Log.d("CloudinaryUploader", "MEDIA_MIME_DETECTED: $mimeType")

                val resourceType = when {
                    mimeType?.startsWith("video/") == true -> "video"
                    mimeType?.startsWith("image/") == true -> "image"
                    else -> {
                        val extension = getExtensionFromUri(context, uri)
                        if (extension in listOf("mp4", "mov", "avi", "mkv", "webm")) "video" else "image"
                    }
                }

                val mediaId = "${resourceType}_$mediaStoreId"

                // If the media was already uploaded to Cloudinary, but Firestore metadata write failed,
                // we should check if metadata has already been saved or check the cached/persisted status.
                // To support a metadata-only retry, if checkIfMediaExists returns true, we can check if it's already fully saved.
                if (checkIfMediaExists(deviceId, mediaId)) {
                    Log.d("CloudinaryUploader", "MEDIA_DUPLICATE_SKIPPED: $mediaId")
                    val existingData = getFirestoreMediaMetadata(deviceId, mediaId)
                    if (existingData != null) {
                        return@withContext MediaUploadResult.Success(
                            secureUrl = existingData.secureUrl,
                            publicId = existingData.publicId,
                            resourceType = existingData.type,
                            format = existingData.format,
                            bytes = existingData.sizeBytes,
                            width = existingData.width,
                            height = existingData.height,
                            duration = existingData.duration,
                            rawResponse = "{}"
                        )
                    }
                    return@withContext MediaUploadResult.Failure(
                        stage = MediaFailureStage.UNKNOWN,
                        message = "Media already exists in Firestore",
                        retryable = false
                    )
                }

                Log.d("CloudinaryUploader", "MEDIA_FILE_COPY_STARTED")
                tempFile = getFileFromUri(context, uri)
                if (tempFile == null) {
                    return@withContext MediaUploadResult.Failure(
                        stage = MediaFailureStage.TEMP_FILE_COPY,
                        message = "Failed to copy media file from URI",
                        retryable = true
                    )
                }
                Log.d("CloudinaryUploader", "MEDIA_FILE_COPY_SUCCESS: ${tempFile.name}")

                val url = "https://api.cloudinary.com/v1_1/${CloudinaryConfig.CLOUDINARY_CLOUD_NAME}/$resourceType/upload"
                Log.d("CloudinaryUploader", "CLOUDINARY_ENDPOINT_USED: $url")

                val folder = "data_sync/devices/$deviceId/${if (resourceType == "video") "videos" else "images"}"

                Log.d("CloudinaryUploader", "CLOUDINARY_UPLOAD_STARTED: $resourceType")
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", CloudinaryConfig.CLOUDINARY_UPLOAD_PRESET)
                    .addFormDataPart("folder", folder)
                    .addFormDataPart("file", tempFile.name, tempFile.asRequestBody(mimeType?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()))
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                response = client.newCall(request).execute()
                Log.d("CloudinaryUploader", "CLOUDINARY_HTTP_CODE: ${response.code}")
                responseBody = response.body
                val rawRespString = responseBody?.string() ?: ""

                if (response.isSuccessful && rawRespString.isNotEmpty()) {
                    Log.d("CloudinaryUploader", "CLOUDINARY_UPLOAD_SUCCESS")
                    val json = JSONObject(rawRespString)
                    Log.d("CloudinaryUploader", "FIRESTORE_METADATA_STARTED")
                    updateCommandStatus(deviceId, commandId ?: "", "SAVING_METADATA")

                    val secureUrl = json.optString("secure_url")
                    val publicId = json.optString("public_id")
                    val format = json.optString("format")
                    val bytes = json.optLong("bytes")
                    val width = if (json.has("width")) json.optInt("width") else null
                    val height = if (json.has("height")) json.optInt("height") else null
                    val duration = if (json.has("duration")) json.optDouble("duration") else null

                    try {
                        saveToFirestore(
                            deviceId = deviceId,
                            mediaId = mediaId,
                            commandId = commandId ?: "",
                            json = json,
                            type = resourceType,
                            localMediaKey = localMediaKey,
                            dateAdded = dateAdded,
                            dateModified = dateModified,
                            uploadStatus = uploadStatus,
                            detectedMime = mimeType ?: ""
                        )
                        Log.d("CloudinaryUploader", "FIRESTORE_METADATA_SUCCESS")

                        MediaUploadResult.Success(
                            secureUrl = secureUrl,
                            publicId = publicId,
                            resourceType = resourceType,
                            format = format,
                            bytes = bytes,
                            width = width,
                            height = height,
                            duration = duration,
                            rawResponse = rawRespString
                        )
                    } catch (e: Exception) {
                        Log.e("CloudinaryUploader", "FIRESTORE_METADATA_FAILURE", e)
                        MediaUploadResult.Failure(
                            stage = MediaFailureStage.FIRESTORE_METADATA,
                            message = e.localizedMessage ?: "Failed to save metadata to Firestore",
                            retryable = true,
                            secureUrl = secureUrl,
                            publicId = publicId,
                            format = format,
                            bytes = bytes
                        )
                    }
                } else {
                    Log.e("CloudinaryUploader", "CLOUDINARY_UPLOAD_FAILURE: $rawRespString")
                    val errorMsg = try {
                        val errorJson = JSONObject(rawRespString)
                        errorJson.optJSONObject("error")?.optString("message") ?: "Cloudinary upload failed"
                    } catch (e: Exception) {
                        "Cloudinary upload failed with code ${response.code}"
                    }
                    MediaUploadResult.Failure(
                        stage = MediaFailureStage.CLOUDINARY_HTTP,
                        message = errorMsg,
                        httpCode = response.code,
                        cloudinaryError = errorMsg,
                        rawResponse = rawRespString,
                        retryable = response.code >= 500 || response.code == 408
                    )
                }
            } catch (e: Exception) {
                Log.e("CloudinaryUploader", "Error uploading media", e)
                val stage = if (e is java.io.IOException) MediaFailureStage.NETWORK else MediaFailureStage.UNKNOWN
                MediaUploadResult.Failure(
                    stage = stage,
                    message = e.localizedMessage ?: "Unknown error occurred during upload",
                    retryable = true
                )
            } finally {
                try {
                    inputStream?.close()
                } catch (_: Exception) {}
                try {
                    outputStream?.close()
                } catch (_: Exception) {}
                try {
                    response?.close()
                } catch (_: Exception) {}
                try {
                    tempFile?.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun checkIfMediaExists(deviceId: String, mediaId: String): Boolean {
        return try {
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("devices")
                .document(deviceId)
                .collection("media")
                .document(mediaId)
                .get()
                .await()
            doc.exists()
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getFirestoreMediaMetadata(deviceId: String, mediaId: String): MediaData? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("devices")
                .document(deviceId)
                .collection("media")
                .document(mediaId)
                .get()
                .await()
            if (doc.exists()) {
                doc.toObject(MediaData::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveToFirestore(
        deviceId: String,
        mediaId: String,
        commandId: String,
        json: JSONObject,
        type: String,
        localMediaKey: String? = null,
        dateAdded: Long = 0,
        dateModified: Long = 0,
        uploadStatus: String = "METADATA_SAVED",
        detectedMime: String = ""
    ) {
        val db = FirebaseFirestore.getInstance()

        val secureUrl = json.optString("secure_url")
        val mediaData = MediaData(
            id = mediaId,
            deviceId = deviceId,
            localMediaKey = localMediaKey ?: mediaId,
            mediaStoreId = mediaId.substringAfter("_"),
            type = type,
            url = json.optString("url"),
            secureUrl = secureUrl,
            thumbnailUrl = if (type == "video") {
                if (secureUrl.endsWith(".mp4", ignoreCase = true)) {
                    secureUrl.substringBeforeLast(".mp4") + ".jpg"
                } else {
                    secureUrl + ".jpg"
                }
            } else secureUrl,
            publicId = json.optString("public_id"),
            resourceType = json.optString("resource_type"),
            fileName = json.optString("original_filename") + "." + json.optString("format"),
            mimeType = detectedMime.ifBlank { json.optString("mime_type") },
            sizeBytes = json.optLong("bytes"),
            format = json.optString("format"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            duration = if (json.has("duration")) json.optDouble("duration") else null,
            source = if (commandId.isEmpty()) "auto_sync" else "picker",
            dateAdded = dateAdded,
            dateModified = dateModified,
            uploadStatus = uploadStatus,
            createdAt = System.currentTimeMillis(),
            uploadedAt = System.currentTimeMillis(),
            commandId = commandId
        )

        db.collection("devices")
            .document(deviceId)
            .collection("media")
            .document(mediaId)
            .set(mediaData)
            .await()
    }

    suspend fun saveManualToFirestore(
        deviceId: String,
        mediaId: String,
        type: String,
        secureUrl: String,
        publicId: String,
        format: String?,
        bytes: Long,
        localMediaKey: String,
        dateAdded: Long,
        dateModified: Long,
        uploadStatus: String,
        detectedMime: String
    ) {
        val db = FirebaseFirestore.getInstance()
        val mediaData = MediaData(
            id = mediaId,
            deviceId = deviceId,
            localMediaKey = localMediaKey,
            mediaStoreId = mediaId.substringAfter("_"),
            type = type,
            url = secureUrl,
            secureUrl = secureUrl,
            thumbnailUrl = if (type == "video") {
                if (secureUrl.endsWith(".mp4", ignoreCase = true)) {
                    secureUrl.substringBeforeLast(".mp4") + ".jpg"
                } else {
                    secureUrl + ".jpg"
                }
            } else secureUrl,
            publicId = publicId,
            resourceType = type,
            fileName = mediaId.substringAfter("_") + "." + (format ?: "jpg"),
            mimeType = detectedMime,
            sizeBytes = bytes,
            format = format ?: "jpg",
            width = 0,
            height = 0,
            duration = null,
            source = "auto_sync",
            dateAdded = dateAdded,
            dateModified = dateModified,
            uploadStatus = uploadStatus,
            createdAt = System.currentTimeMillis(),
            uploadedAt = System.currentTimeMillis(),
            commandId = ""
        )

        db.collection("devices")
            .document(deviceId)
            .collection("media")
            .document(mediaId)
            .set(mediaData)
            .await()
    }

    private suspend fun updateCommandStatus(deviceId: String, commandId: String, status: String) {
        if (commandId.isBlank()) return
        val db = FirebaseFirestore.getInstance()
        try {
            db.collection("devices")
                .document(deviceId)
                .collection("commands")
                .document(commandId)
                .update("status", status)
                .await()
        } catch (e: Exception) {
            Log.e("CloudinaryUploader", "Failed to update status to $status", e)
        }
    }

    private fun getFileFromUri(context: Context, uri: Uri): File? {
        var cursor: android.database.Cursor? = null
        var fileName = "temp_media_file"
        try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor != null && cursor.moveToFirst() && nameIndex != null && nameIndex != -1) {
                fileName = cursor.getString(nameIndex)
            }
        } catch (e: Exception) {
            Log.e("CloudinaryUploader", "Error querying display name from Uri", e)
        } finally {
            cursor?.close()
        }

        val file = File(context.cacheDir, fileName)
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            return file
        } catch (e: Exception) {
            Log.e("CloudinaryUploader", "Error copying URI to file", e)
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
            try {
                outputStream?.close()
            } catch (_: Exception) {}
        }
    }

    private fun getExtensionFromUri(context: Context, uri: Uri): String {
        var cursor: android.database.Cursor? = null
        var fileName = ""
        try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor != null && cursor.moveToFirst() && nameIndex != null && nameIndex != -1) {
                fileName = cursor.getString(nameIndex)
            }
        } catch (e: Exception) {
            Log.e("CloudinaryUploader", "Error querying filename from Uri", e)
        } finally {
            cursor?.close()
        }
        return fileName.substringAfterLast(".", "").lowercase()
    }

    suspend fun testUpload(context: Context): Pair<Int, String> {
        return withContext(Dispatchers.IO) {
            var response: Response? = null
            var tempFile: File? = null
            try {
                tempFile = File(context.cacheDir, "cloudinary_test.png")
                val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.RED)
                val out = FileOutputStream(tempFile)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                out.close()

                val url = "https://api.cloudinary.com/v1_1/${CloudinaryConfig.CLOUDINARY_CLOUD_NAME}/image/upload"
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", CloudinaryConfig.CLOUDINARY_UPLOAD_PRESET)
                    .addFormDataPart("folder", "data_sync/test")
                    .addFormDataPart("file", tempFile.name, tempFile.asRequestBody("image/png".toMediaTypeOrNull()))
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                val code = response.code
                Pair(code, responseBody)
            } catch (e: Exception) {
                Pair(-1, e.localizedMessage ?: "Unknown Exception")
            } finally {
                try {
                    response?.close()
                } catch (_: Exception) {}
                try {
                    tempFile?.delete()
                } catch (_: Exception) {}
            }
        }
    }
}
