package com.boxx.datasync.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.boxx.datasync.sync.CloudinaryUploader
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaPickerLauncherActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var commandId: String? = null

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val deviceId = DeviceIdHelper.getDeviceId(this)
            Toast.makeText(this, "Uploading media...", Toast.LENGTH_LONG).show()
            scope.launch {
                CloudinaryUploader.uploadMedia(this@MediaPickerLauncherActivity, uri, deviceId, commandId ?: "") { success ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this@MediaPickerLauncherActivity, "Upload complete", Toast.LENGTH_SHORT).show()
                            commandId?.let { updateCommandStatus(deviceId, it, "SUCCESS", null) }
                        } else {
                            Toast.makeText(this@MediaPickerLauncherActivity, "Upload failed", Toast.LENGTH_SHORT).show()
                            commandId?.let { updateCommandStatus(deviceId, it, "FAILED", "Upload to Cloudinary failed") }
                        }
                        finish()
                    }
                }
            }
        } else {
            commandId?.let {
                val deviceId = DeviceIdHelper.getDeviceId(this)
                updateCommandStatus(deviceId, it, "FAILED", "User cancelled picker")
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        commandId = intent.getStringExtra("commandId")
        val mimeType = intent.getStringExtra("mimeType") ?: "image/*"

        Log.d("MediaPickerLauncherActivity", "Launching picker for $mimeType")
        pickerLauncher.launch(mimeType)
    }

    private fun updateCommandStatus(deviceId: String, commandId: String, status: String, error: String?) {
        val db = FirebaseFirestore.getInstance()
        val updates = mutableMapOf<String, Any>("status" to status, "completedAt" to System.currentTimeMillis())
        error?.let { updates["error"] = it }
        db.collection("devices")
            .document(deviceId)
            .collection("commands")
            .document(commandId)
            .update(updates)
    }
}
