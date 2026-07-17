package com.boxx.datasync.ui.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.boxx.datasync.sync.CloudinaryUploader
import com.boxx.datasync.sync.MediaUploadResult
import com.boxx.datasync.utils.DeviceIdHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraCaptureScreen(
    initialIsFront: Boolean = false,
    commandId: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var isFrontCamera by remember { mutableStateOf(initialIsFront) }
    var isFlashOn by remember { mutableStateOf(false) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCameraReady by remember { mutableStateOf(false) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var cameraInfo: CameraInfo? by remember { mutableStateOf(null) }

    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (capturedBitmap == null) {
            // Live Preview Mode
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProvider.unbindAll()

                            val preview = Preview.Builder().build().apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val cameraSelector = if (isFrontCamera) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }

                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()

                            imageCapture = capture

                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                capture
                            )

                            cameraControl = camera.cameraControl
                            cameraInfo = camera.cameraInfo
                            isCameraReady = true

                            // Apply initial flash status
                            cameraControl?.enableTorch(isFlashOn && !isFrontCamera)
                        } catch (e: Exception) {
                            Log.e("CameraCaptureScreen", "Camera binding failed", e)
                            errorMessage = "Camera failed to start: ${e.localizedMessage}"
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )

            // Live Preview Overlay controls
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top controls (Flash and Cancel)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                val deviceId = DeviceIdHelper.getDeviceId(context)
                                if (commandId.isNotBlank()) {
                                    CloudinaryUploader.cancelCameraCommand(deviceId, commandId, "User cancelled from live camera preview")
                                }
                                onDismiss()
                            }
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }

                    if (!isFrontCamera && isCameraReady) {
                        IconButton(
                            onClick = {
                                val newFlash = !isFlashOn
                                isFlashOn = newFlash
                                cameraControl?.enableTorch(newFlash)
                            },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Toggle Flash",
                                tint = if (isFlashOn) Color.Yellow else Color.White
                            )
                        }
                    }
                }

                // Error State inside Camera Preview
                errorMessage?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Bottom control actions (Flip and Shoot)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(48.dp)) // Equalizer spacing

                    // Shoot Button
                    Button(
                        onClick = {
                            val captureUseCase = imageCapture
                            if (captureUseCase != null && isCameraReady) {
                                val tempFile = File(context.cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                                captureUseCase.takePicture(
                                    outputOptions,
                                    executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                                            capturedFile = tempFile
                                            capturedBitmap = bitmap
                                            errorMessage = null
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("CameraCaptureScreen", "Photo capture failed", exception)
                                            errorMessage = "Capture failed: ${exception.localizedMessage}"
                                        }
                                    }
                                )
                            } else {
                                errorMessage = "Camera is not ready yet."
                            }
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        modifier = Modifier.size(72.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color.Red, CircleShape)
                        )
                    }

                    // Flip Camera Button
                    IconButton(
                        onClick = {
                            isFrontCamera = !isFrontCamera
                            isFlashOn = false // reset flash when switching
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Switch Camera", tint = Color.White)
                    }
                }
            }
        } else {
            // Captured Image Preview Mode
            val bitmap = capturedBitmap!!
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured Photo",
                modifier = Modifier.fillMaxSize()
            )

            // Preview Overlay controls
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top details & status / progress
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (isUploading) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                Text("Uploading photo...", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Text(
                                text = errorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Bottom actions: Cancel, Retake, Use Photo
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val deviceId = DeviceIdHelper.getDeviceId(context)
                                if (commandId.isNotBlank()) {
                                    CloudinaryUploader.cancelCameraCommand(deviceId, commandId, "User cancelled from photo preview screen")
                                }
                                capturedFile?.delete()
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel")
                    }

                    // Retake
                    Button(
                        onClick = {
                            capturedFile?.delete()
                            capturedFile = null
                            capturedBitmap = null
                            errorMessage = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        enabled = !isUploading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retake")
                    }

                    // Use Photo
                    Button(
                        onClick = {
                            val file = capturedFile
                            if (file != null) {
                                isUploading = true
                                errorMessage = null
                                coroutineScope.launch {
                                    val deviceId = DeviceIdHelper.getDeviceId(context)
                                    val result = CloudinaryUploader.uploadCameraPhoto(
                                        context = context,
                                        file = file,
                                        deviceId = deviceId,
                                        isFront = isFrontCamera,
                                        commandId = commandId
                                    )

                                    isUploading = false
                                    when (result) {
                                        is MediaUploadResult.Success -> {
                                            file.delete()
                                            onDismiss()
                                        }
                                        is MediaUploadResult.Failure -> {
                                            errorMessage = "Upload failed: ${result.message}"
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        enabled = !isUploading
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Use Photo")
                    }
                }
            }
        }
    }
}
