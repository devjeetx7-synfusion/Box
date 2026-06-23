package com.boxx.datasync.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val simInfo by viewModel.simInfo.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSimEditDialog by remember { mutableStateOf<Int?>(null) } // 0 for SIM 1, 1 for SIM 2

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
                .padding(24.dp),
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
                        "Educational demo — do not use for unauthorized monitoring.",
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

            // SIM Information Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SimCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SIM Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    SimInfoRow(
                        slotIndex = 0,
                        ready = simInfo["sim1Ready"] as? Boolean ?: false,
                        carrier = simInfo["sim1Carrier"] as? String ?: "No SIM available",
                        number = simInfo["sim1Number"] as? String ?: "Number not provided by carrier",
                        onEdit = { showSimEditDialog = 0 }
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                    SimInfoRow(
                        slotIndex = 1,
                        ready = simInfo["sim2Ready"] as? Boolean ?: false,
                        carrier = simInfo["sim2Carrier"] as? String ?: "No SIM available",
                        number = simInfo["sim2Number"] as? String ?: "Number not provided by carrier",
                        onEdit = { showSimEditDialog = 1 }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { viewModel.testFirebaseConnection() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Test Firebase Connection")
            }

            Button(
                onClick = onSyncClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isLoading
            ) {
                Text("Sync Now", fontSize = 18.sp)
            }

            TextButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Synced Data")
            }
        }

        if (showSimEditDialog != null) {
            var tempNumber by remember {
                mutableStateOf(
                    (if (showSimEditDialog == 0) simInfo["sim1Number"] else simInfo["sim2Number"]) as? String ?: ""
                )
            }
            if (tempNumber == "Number not provided by carrier") tempNumber = ""

            AlertDialog(
                onDismissRequest = { showSimEditDialog = null },
                title = { Text("Edit SIM ${showSimEditDialog!! + 1} Number") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Manually enter your phone number if the carrier does not provide it automatically.")
                        OutlinedTextField(
                            value = tempNumber,
                            onValueChange = { tempNumber = it },
                            label = { Text("Phone Number") },
                            placeholder = { Text("+1...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.saveManualNumber(showSimEditDialog!!, tempNumber)
                        showSimEditDialog = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showSimEditDialog = null }) { Text("Cancel") }
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

@Composable
fun SimInfoRow(slotIndex: Int, ready: Boolean, carrier: String, number: String, onEdit: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("SIM ${slotIndex + 1}: $carrier", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (ready) number else "Not Available",
                style = MaterialTheme.typography.bodySmall,
                color = if (number == "Number not provided by carrier") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (ready) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit SIM Number", modifier = Modifier.size(20.dp))
            }
        }
    }
}
