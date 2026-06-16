package com.datasync.admin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datasync.admin.formatDate
import com.datasync.admin.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onDeviceClick: (String) -> Unit
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Data Sync Admin", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                // Realtime listeners in Repository handle updates automatically via Firestore.
                // Pull-to-refresh here serves as a visual confirmation/manual sync trigger.
                isRefreshing = true
                // We don't have a manual fetch function in repository yet, so we just reset refreshing state
                isRefreshing = false
            },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (devices.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Searching for devices...")
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    itemsIndexed(devices, key = { _, device -> device.deviceId }) { index, device ->
                        Text("#${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable { onDeviceClick(device.deviceId) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val statusColor = when {
                                            System.currentTimeMillis() - device.syncRequestedAt < 30000 && device.syncRequestedAt > device.lastSyncTime -> Color(0xFFFFA500) // Orange
                                            device.isOnline -> Color.Green
                                            else -> Color.Red
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(device.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    if (device.isDemoMode) {
                                        SuggestionChip(onClick = {}, label = { Text("Demo") }, modifier = Modifier.height(24.dp))
                                    }
                                }
                                Text("${device.manufacturer} ${device.model} • ${device.deviceId.takeLast(6)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    MetricItem("Contacts", device.contactCount.toString())
                                    MetricItem("SMS", device.smsCount.toString())
                                    MetricItem("Calls", device.callLogCount.toString())
                                    MetricItem("Notifications", device.notificationCount.toString())
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Sync: ${formatDate(device.lastSyncTime)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
