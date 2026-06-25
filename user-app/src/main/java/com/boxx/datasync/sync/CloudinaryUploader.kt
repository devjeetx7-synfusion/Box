package com.boxx.datasync.sync

import android.content.Context
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed class MediaUploadResult {
    data class Success(
        val mediaId: String,
        val secureUrl: String,
        val publicId: String
    ) : MediaUploadResult()

    data class Failed(
        val stage: String,
        val errorMessage: String,
        val rawResponse: String? = null
    ) : MediaUploadResult()
}

object CloudinaryUploader {
    private val client = OkHttpClient()

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        deviceId: String,
        commandId: String? = null
    ): MediaUploadResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("CloudinaryUploader", "MEDIA_URI_SELECTED: $uri")

                val mimeType = context.contentResolver.getType(uri)
                Log.d("CloudinaryUploader", "MEDIA_MIME_DETECTED: $mimeType")

                val resourceType = when {
                    mimeType?.startsWith("video/") == true -> "video"
                    mimeType?.startsWith("image/") == true -> "image"
                    else -> {
                        // Fallback to extension
                        val extension = getExtensionFromUri(context, uri)
                        if (extension in listOf("mp4", "mov", "avi", "mkv", "webm")) "video" else "image"
                    }
                }

                Log.d("CloudinaryUploader", "MEDIA_FILE_COPY_STARTED")
                val file = getFileFromUri(context, uri)
                if (file == null) {
                    return@withContext MediaUploadResult.Failed("URI_COPY_FAILED", "Failed to copy media file from URI")
                }
                Log.d("CloudinaryUploader", "MEDIA_FILE_COPY_SUCCESS: ${file.name}")

                val url = "https://api.cloudinary.com/v1_1/${CloudinaryConfig.CLOUDINARY_CLOUD_NAME}/$resourceType/upload"
                val folder = "data_sync/devices/$deviceId/${if (resourceType == "video") "videos" else "images"}"

                Log.d("CloudinaryUploader", "CLOUDINARY_UPLOAD_STARTED: $resourceType")
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", CloudinaryConfig.CLOUDINARY_UPLOAD_PRESET)
                    .addFormDataPart("folder", folder)
                    .addFormDataPart("file", file.name, file.asRequestBody(mimeType?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()))
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                val result = if (response.isSuccessful && responseBody != null) {
                    Log.d("CloudinaryUploader", "CLOUDINARY_UPLOAD_SUCCESS")
                    val json = JSONObject(responseBody)
                    Log.d("CloudinaryUploader", "FIRESTORE_MEDIA_SAVE_STARTED")
                    updateCommandStatus(deviceId, commandId ?: "", "SAVING_METADATA")
                    try {
                        val mediaId = saveToFirestore(deviceId, commandId ?: "", json, resourceType)
                        Log.d("CloudinaryUploader", "FIRESTORE_MEDIA_SAVE_SUCCESS")
                        MediaUploadResult.Success(
                            mediaId = mediaId,
                            secureUrl = json.optString("secure_url"),
                            publicId = json.optString("public_id")
                        )
                    } catch (e: Exception) {
                        Log.e("CloudinaryUploader", "FIRESTORE_MEDIA_SAVE_FAILED", e)
                        MediaUploadResult.Failed("FIRESTORE_METADATA_FAILED", e.localizedMessage ?: "Failed to save metadata to Firestore")
                    }
                } else {
                    Log.e("CloudinaryUploader", "CLOUDINARY_UPLOAD_FAILED: $responseBody")
                    Log.d("CloudinaryUploader", "CLOUDINARY_RAW_ERROR: $responseBody")
                    val errorMsg = try {
                        val errorJson = JSONObject(responseBody ?: "{}")
                        errorJson.optJSONObject("error")?.optString("message") ?: "Cloudinary upload failed"
                    } catch (e: Exception) {
                        "Cloudinary upload failed with code ${response.code}"
                    }
                    MediaUploadResult.Failed("CLOUDINARY_UPLOAD_FAILED", errorMsg, responseBody)
                }

                file.delete()
                result
            } catch (e: Exception) {
                Log.e("CloudinaryUploader", "Error uploading media", e)
                MediaUploadResult.Failed("UNKNOWN_ERROR", e.localizedMessage ?: "Unknown error occurred during upload")
            }
        }
    }

    private suspend fun saveToFirestore(deviceId: String, commandId: String, json: JSONObject, type: String): String {
        val db = FirebaseFirestore.getInstance()
        val mediaId = UUID.randomUUID().toString()

        val mediaData = MediaData(
            id = mediaId,
            deviceId = deviceId,
            type = type,
            url = json.optString("url"),
            secureUrl = json.optString("secure_url"),
            thumbnailUrl = if (type == "video") json.optString("secure_url").replace(".mp4", ".jpg") else json.optString("secure_url"),
            publicId = json.optString("public_id"),
            resourceType = json.optString("resource_type"),
            fileName = json.optString("original_filename") + "." + json.optString("format"),
            sizeBytes = json.optLong("bytes"),
            format = json.optString("format"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            duration = if (json.has("duration")) json.optDouble("duration") else null,
            source = if (commandId.isEmpty()) "auto_sync" else "picker",
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

        return mediaId
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
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        var fileName = "temp_media_file"
        if (cursor != null && cursor.moveToFirst() && nameIndex != null) {
            fileName = cursor.getString(nameIndex)
        }
        cursor?.close()

        val file = File(context.cacheDir, fileName)
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            return file
        } catch (e: Exception) {
            Log.e("CloudinaryUploader", "Error copying URI to file", e)
            return null
        }
    }

    private fun getExtensionFromUri(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        var fileName = ""
        if (cursor != null && cursor.moveToFirst() && nameIndex != null) {
            fileName = cursor.getString(nameIndex)
        }
        cursor?.close()
        return fileName.substringAfterLast(".", "").lowercase()
    }
}
