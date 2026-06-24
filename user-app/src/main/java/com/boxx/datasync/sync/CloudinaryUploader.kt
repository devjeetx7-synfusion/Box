package com.boxx.datasync.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.boxx.datasync.domain.model.MediaData
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object CloudinaryUploader {
    private val client = OkHttpClient()

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        deviceId: String,
        commandId: String? = null,
        onComplete: (Boolean) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            try {
                val file = getFileFromUri(context, uri)
                if (file == null) {
                    onComplete(false)
                    return@withContext
                }

                val isVideo = file.extension.lowercase() in listOf("mp4", "mov", "avi", "mkv", "webm")
                val resourceType = if (isVideo) "video" else "image"

                val url = "https://api.cloudinary.com/v1_1/${CloudinaryConfig.CLOUDINARY_CLOUD_NAME}/$resourceType/upload"
                val folder = "data_sync/devices/$deviceId/${if (resourceType == "video") "videos" else "images"}"

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", CloudinaryConfig.CLOUDINARY_UPLOAD_PRESET)
                    .addFormDataPart("folder", folder)
                    .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    saveToFirestore(deviceId, commandId ?: "", json, resourceType)
                    onComplete(true)
                } else {
                    Log.e("CloudinaryUploader", "Upload failed: $responseBody")
                    onComplete(false)
                }

                file.delete()
            } catch (e: Exception) {
                Log.e("CloudinaryUploader", "Error uploading media", e)
                onComplete(false)
            }
        }
    }

    private suspend fun saveToFirestore(deviceId: String, commandId: String, json: JSONObject, type: String) {
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
            .addOnFailureListener { e ->
                Log.e("CloudinaryUploader", "Error saving metadata to Firestore", e)
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
}
