package com.boxx.porn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import com.boxx.porn.sync.SyncService
import com.boxx.porn.sync.SyncWorker
import com.boxx.porn.utils.DeviceIdHelper
import com.boxx.porn.utils.PermissionHandler
import com.boxx.porn.utils.hasAllPermissions
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SyncScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deviceId = remember { DeviceIdHelper.getDeviceId(context) }
    var lastSyncTime by remember { mutableStateOf("Never") }
    var syncStatus by remember { mutableStateOf("Idle") }
    val showRationale = remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    PermissionHandler(
        onPermissionsGranted = {
            startSyncService(context)
            if (!isNotificationServiceEnabled(context)) {
                openNotificationListenerSettings(context)
            }
        },
        showRationale = showRationale
    )

    LaunchedEffect(Unit) {
        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.getLong("lastSyncTime")?.let {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    lastSyncTime = sdf.format(Date(it))
                    syncStatus = "Idle"
                    isLoading = false
                }
            }

        if (hasAllPermissions(context)) {
            startSyncService(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data Sync", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Device Metadata", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    InfoRow(label = "Device ID", value = deviceId)
                    InfoRow(label = "Last Synced", value = lastSyncTime)
                    InfoRow(label = "Status", value = syncStatus)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        if (hasAllPermissions(context)) {
                            syncStatus = "Syncing..."
                            isLoading = true
                            startSyncService(context)
                            scope.launch {
                                snackbarHostState.showSnackbar("Manual Sync Initiated")
                            }
                        } else {
                            showRationale.value = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("Sync Now", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun startSyncService(context: Context) {
    val intent = Intent(context, SyncService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (flat != null) {
        val names = flat.split(":")
        for (name in names) {
            val cn = ComponentName.unflattenFromString(name)
            if (cn != null && cn.packageName == pkgName) {
                return true
            }
        }
    }
    return false
}

private fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    context.startActivity(intent)
}
