package com.boxx.datasync.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxx.datasync.ui.viewmodel.MainViewModel
import com.boxx.datasync.utils.DeviceIdHelper
import com.boxx.datasync.utils.DataUtils.copyToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSyncClick: () -> Unit,
    showSettings: () -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdHelper.getDeviceId(context) }
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val autoMediaSyncEnabled by viewModel.autoMediaSyncEnabled.collectAsState()
    val lastMediaSyncTime by viewModel.lastMediaSyncTime.collectAsState()
    val lastMediaScanTime by viewModel.lastMediaScanTime.collectAsState()
    val mediaDiscoveredCount by viewModel.mediaDiscoveredCount.collectAsState()
    val mediaUploadedCount by viewModel.mediaUploadedCount.collectAsState()
    val mediaFailedCount by viewModel.mediaFailedCount.collectAsState()
    val lastMediaError by viewModel.lastMediaError.collectAsState()
    val lastMediaErrorStage by viewModel.lastMediaErrorStage.collectAsState()
    val cloudinaryTestResult by viewModel.cloudinaryTestResult.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Log.d("MainScreen", "CLIENT_UI_SIM_CARD_REMOVED")
        Log.d("MainScreen", "CLIENT_UI_SCROLL_ENABLED")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Data Sync", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notification Access")
                    }
                    IconButton(onClick = showSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(bottom = 32.dp), // Extra bottom padding for navigation bar
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Educational Note
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "This educational demo syncs data only after user-granted permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Sync Status
            Box(
                modifier = Modifier.size(100.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        imageVector = if (syncStatus == "Synced") Icons.Default.CheckCircle else Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        tint = if (syncStatus == "Synced") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Text(
                text = syncStatus,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (syncStatus == "Error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
            )

            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                if (error.contains("Media permission", ignoreCase = true)) {
                    Text(
                        "Media permission not granted. Images/videos cannot be synced.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Background Sync Help", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("If background sync fails or is delayed, your device's battery optimization might be restricting it. You can optionally set it to 'Unrestricted' for better reliability.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer, contentColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text("Open Battery Settings")
                        }
                    }
                }
                OutlinedButton(onClick = {
                    copyToClipboard(context, "Device ID: $deviceId\nStatus: $syncStatus\nLast Synced: $lastSyncTime\nLast Media Sync: $lastMediaSyncTime\nError: $error\nMedia Error: $lastMediaError")
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Debug Info")
                }
            }

            lastMediaError?.let { mError ->
                if (errorMessage == null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Media Sync Error (${lastMediaErrorStage ?: "Unknown"})", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(mError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.triggerMediaSyncNow() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry Failed Media")
                        }
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, "Device ID: $deviceId\nLast Media Sync: $lastMediaSyncTime\nStage: $lastMediaErrorStage\nMedia Error: $mError")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Debug Info")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoRow(label = "Device ID", value = deviceId)
                    InfoRow(label = "Last Synced", value = lastSyncTime)
                    InfoRow(label = "Last Media Scan", value = lastMediaScanTime)
                    InfoRow(label = "Last Media Sync", value = lastMediaSyncTime)
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(label = "Media Discovered", value = mediaDiscoveredCount.toString())
                    InfoRow(label = "Media Uploaded", value = mediaUploadedCount.toString())
                    InfoRow(label = "Media Failed", value = mediaFailedCount.toString())
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Auto Media Sync", fontWeight = FontWeight.Bold)
                        }
                        Switch(
                            checked = autoMediaSyncEnabled,
                            onCheckedChange = { viewModel.setAutoMediaSync(it) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "When enabled, selected/new images and videos will upload to Cloudinary and appear in Admin Media grid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.testCloudinaryUpload() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.CloudQueue, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Cloudinary Upload")
                    }
                }
            }

            Button(
                onClick = onSyncClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isLoading
            ) {
                Text("Sync Now", fontSize = 18.sp)
            }

            if (lastSyncTime != "Never") {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Synced Data")
                }
            }
        }

        if (cloudinaryTestResult != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearCloudinaryTestResult() },
                title = { Text("Cloudinary Test Result") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("Status Code: ${cloudinaryTestResult?.statusCode}", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Parsed Error: ${cloudinaryTestResult?.parsedErrorMessage ?: "None"}", color = if (cloudinaryTestResult?.parsedErrorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Raw Response:", fontWeight = FontWeight.Bold)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = cloudinaryTestResult?.responseBody ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.clearCloudinaryTestResult() }) {
                        Text("Close")
                    }
                }
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Data?") },
                text = { Text("This will remove all synced records for this device from the Firestore dashboard.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            viewModel.deleteSyncedData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}
