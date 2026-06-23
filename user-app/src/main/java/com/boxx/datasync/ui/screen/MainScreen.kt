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
                        imageVector = if (syncStatus == "Up to date") Icons.Default.CheckCircle else Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        tint = if (syncStatus == "Up to date") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
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
                OutlinedButton(onClick = {
                    copyToClipboard(context, "Device ID: $deviceId\nStatus: $syncStatus\nLast Synced: $lastSyncTime\nError: $error")
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Debug Info")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoRow(label = "Device ID", value = deviceId)
                    InfoRow(label = "Last Synced", value = lastSyncTime)
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

