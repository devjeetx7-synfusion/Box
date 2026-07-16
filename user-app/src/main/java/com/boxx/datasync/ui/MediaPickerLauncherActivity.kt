package com.boxx.datasync.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxx.datasync.sync.CloudinaryUploader
import com.boxx.datasync.sync.MediaUploadResult
import com.boxx.datasync.utils.DataUtils.copyToClipboard
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaPickerLauncherActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var commandId: String? = null
    private var mimeType: String = "image/*"

    private var statusText by mutableStateOf("Ready")
    private var isUploading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var rawError by mutableStateOf<String?>(null)

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleUriSelected(uri)
        } else {
            Log.d("MediaPickerLauncherActivity", "MEDIA_SELECTION_CANCELLED")
            commandId?.let {
                val deviceId = DeviceIdHelper.getDeviceId(this)
                updateCommandStatus(deviceId, it, "CANCELLED", "User cancelled media selection")
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commandId = intent.getStringExtra("commandId")
        mimeType = intent.getStringExtra("mimeType") ?: "image/*"

        Log.d("MediaPickerLauncherActivity", "MEDIA_PICKER_OPENED: $mimeType")

        commandId?.let {
            updateCommandStatus(DeviceIdHelper.getDeviceId(this), it, "WAITING_FOR_USER_SELECTION", null)
        }

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    UploadStatusScreen()
                }
            }
        }

        pickerLauncher.launch(mimeType)
    }

    @Composable
    fun UploadStatusScreen() {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            } else if (errorMessage != null) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Upload Failed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickerLauncher.launch(mimeType) }) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Selection")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = {
                    val debugInfo = "Error: $errorMessage\nRaw: $rawError\nCommand: $commandId"
                    copyToClipboard(context, debugInfo)
                    Toast.makeText(context, "Debug info copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Debug Info")
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(onClick = { finish() }) {
                    Text("Close")
                }
            } else {
                Text("Preparing media picker...")
            }
        }
    }

    private fun handleUriSelected(uri: Uri) {
        val deviceId = DeviceIdHelper.getDeviceId(this)
        isUploading = true
        errorMessage = null
        statusText = "Uploading to Cloudinary..."

        commandId?.let { updateCommandStatus(deviceId, it, "UPLOADING_TO_CLOUDINARY", null) }

        scope.launch {
            val mediaStoreId = uri.lastPathSegment ?: System.currentTimeMillis().toString()
            val result = CloudinaryUploader.uploadMedia(this@MediaPickerLauncherActivity, uri, deviceId, mediaStoreId, commandId)

            when (result) {
                is MediaUploadResult.Success -> {
                    statusText = "Upload complete!"
                    isUploading = false
                    commandId?.let { updateCommandStatus(deviceId, it, "SUCCESS", null) }
                    Toast.makeText(this@MediaPickerLauncherActivity, "Upload complete", Toast.LENGTH_SHORT).show()
                    finish()
                }
                is MediaUploadResult.Failure -> {
                    isUploading = false
                    errorMessage = result.message
                    rawError = result.rawResponse

                    val stageText = when(result.stage) {
                        com.boxx.datasync.sync.MediaFailureStage.TEMP_FILE_COPY -> "Failed to process selected file"
                        com.boxx.datasync.sync.MediaFailureStage.CLOUDINARY_HTTP -> "Cloudinary upload failed"
                        com.boxx.datasync.sync.MediaFailureStage.FIRESTORE_METADATA -> "Metadata sync failed"
                        else -> "Upload failed"
                    }

                    statusText = stageText
                    commandId?.let {
                        updateCommandStatus(deviceId, it, "FAILED", "${result.stage}: ${result.message}")
                    }
                }
            }
        }
    }

    private fun updateCommandStatus(deviceId: String, commandId: String, status: String, error: String?) {
        val db = FirebaseFirestore.getInstance()
        val updates = mutableMapOf<String, Any>("status" to status)

        if (status == "SUCCESS" || status == "FAILED" || status == "CANCELLED") {
            updates["completedAt"] = System.currentTimeMillis()
        }

        error?.let { updates["error"] = it }

        db.collection("devices")
            .document(deviceId)
            .collection("commands")
            .document(commandId)
            .update(updates)
            .addOnFailureListener {
                Log.e("MediaPickerLauncher", "Failed to update command status", it)
            }
    }
}
