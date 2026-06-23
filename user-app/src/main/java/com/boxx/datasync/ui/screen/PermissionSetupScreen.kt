package com.boxx.datasync.ui.screen

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxx.datasync.permission.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSetupScreen(
    viewModel: PermissionViewModel,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val permissions by viewModel.permissions.collectAsState()
    val statuses by viewModel.statuses.collectAsState()
    val handler = remember { PermissionHandler(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshStatuses()
    }

    LaunchedEffect(statuses) {
        if (viewModel.areAllRequiredGranted()) {
            onAllGranted()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Setup Permissions", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Button(
                        onClick = {
                            val runtimePermissions = permissions
                                .filter { !it.isSpecial && it.permission != null }
                                .filter { statuses[it.id] != PermissionStatus.GRANTED }
                                .mapNotNull { it.permission }
                                .toTypedArray()

                            if (runtimePermissions.isNotEmpty()) {
                                launcher.launch(runtimePermissions)
                            } else {
                                // Find first special permission or permanently denied one
                                permissions.find { statuses[it.id] != PermissionStatus.GRANTED }?.let { info ->
                                    context.startActivity(handler.getSettingsIntent(info))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Allow All Permissions", fontSize = 18.sp)
                    }

                    if (viewModel.areAllRequiredGranted()) {
                        TextButton(
                            onClick = onAllGranted,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text("Continue to App")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp, top = 16.dp)
        ) {
            item {
                Text(
                    "To function correctly, this educational demo requires several permissions. Each is used solely for syncing data to your private dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(permissions) { info ->
                PermissionCard(
                    info = info,
                    status = statuses[info.id] ?: PermissionStatus.DENIED,
                    isPermanentlyDenied = handler.isPermanentlyDenied(activity, info),
                    onAction = {
                        context.startActivity(handler.getSettingsIntent(info))
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionCard(
    info: PermissionInfo,
    status: PermissionStatus,
    isPermanentlyDenied: Boolean,
    onAction: () -> Unit
) {
    val containerColor = when (status) {
        PermissionStatus.GRANTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        PermissionStatus.NEEDS_SETTINGS -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> if (isPermanentlyDenied) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getIconForName(info.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (info.isOptional) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "(Optional)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            when {
                status == PermissionStatus.GRANTED -> {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Granted", tint = MaterialTheme.colorScheme.primary)
                }
                status == PermissionStatus.NEEDS_SETTINGS || isPermanentlyDenied -> {
                    IconButton(onClick = onAction) {
                        Icon(Icons.Default.Settings, contentDescription = "Open Settings", tint = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    Icon(Icons.Default.Info, contentDescription = "Pending", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

fun getIconForName(name: String): ImageVector {
    return when (name) {
        "Contacts" -> Icons.Default.ContactPage
        "Sms" -> Icons.Default.Sms
        "SendSms" -> Icons.Default.Send
        "ReceiveSms" -> Icons.Default.MoveToInbox
        "Call" -> Icons.Default.Call
        "Phone" -> Icons.Default.Phone
        "SimCard" -> Icons.Default.SimCard
        "Notifications" -> Icons.Default.Notifications
        "NotificationSync" -> Icons.Default.NotificationsActive
        "Battery" -> Icons.Default.BatteryChargingFull
        else -> Icons.Default.Security
    }
}
