package com.boxx.datasync.ui.screen

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxx.datasync.ui.viewmodel.MainViewModel
import com.boxx.datasync.ui.viewmodel.PersonalDetailsViewModel
import com.boxx.datasync.utils.DeviceIdHelper
import kotlinx.coroutines.launch
import java.util.Calendar

sealed class ScreenState {
    object PersonalDetails : ScreenState()
    object Diagnostics : ScreenState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSyncClick: () -> Unit,
    showSettings: () -> Unit,
    personalViewModel: PersonalDetailsViewModel,
    onCapturePhoto: (String) -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdHelper.getDeviceId(context) }
    val deviceName = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf<ScreenState>(ScreenState.PersonalDetails) }

    // Tap tracking for Personal Details Title
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    val handleTitleClick = {
        val now = System.currentTimeMillis()
        if (now - lastTapTime > 5000) {
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapTime = now
        if (tapCount >= 10) {
            tapCount = 0
            currentScreen = ScreenState.Diagnostics
            scope.launch {
                snackbarHostState.showSnackbar("Diagnostics unlocked.")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Personal Details",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { handleTitleClick() }
                    )
                },
                navigationIcon = {
                    if (currentScreen is ScreenState.Diagnostics) {
                        IconButton(onClick = { currentScreen = ScreenState.PersonalDetails }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = showSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        when (currentScreen) {
            is ScreenState.PersonalDetails -> {
                PersonalDetailsFormScreen(
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding),
                    viewModel = personalViewModel,
                    mainViewModel = viewModel,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    onSyncClick = onSyncClick,
                    onCapturePhoto = onCapturePhoto
                )
            }
            is ScreenState.Diagnostics -> {
                SyncDiagnosticsScreen(
                    modifier = Modifier
                        .padding(padding)
                        .consumeWindowInsets(padding),
                    viewModel = viewModel,
                    onSyncClick = onSyncClick,
                    deviceId = deviceId,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalDetailsFormScreen(
    modifier: Modifier = Modifier,
    viewModel: PersonalDetailsViewModel,
    mainViewModel: MainViewModel,
    deviceId: String,
    deviceName: String,
    onSyncClick: () -> Unit,
    onCapturePhoto: (String) -> Unit
) {
    val context = LocalContext.current
    val formState by viewModel.formState.collectAsState()
    val validationErrors by viewModel.validationErrors.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val syncStatus by mainViewModel.syncStatus.collectAsState()

    var showCameraChooser by remember { mutableStateOf(false) }

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Helper to extract name initials
    val initials = remember(formState.fullName) {
        val name = formState.fullName.trim()
        if (name.isBlank()) "U"
        else {
            val parts = name.split("\\s+".toRegex())
            if (parts.size >= 2) {
                (parts[0].take(1) + parts[1].take(1)).uppercase()
            } else {
                parts[0].take(1).uppercase()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Compact Profile Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Initials avatar
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1.0f)) {
                    Text(
                        text = formState.fullName.ifBlank { "User details" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formState.primaryPhone.ifBlank { "Phone not registered" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Saved / Saving / Offline status chip
                Surface(
                    color = when (saveStatus) {
                        "Saving..." -> MaterialTheme.colorScheme.primaryContainer
                        "Saved" -> MaterialTheme.colorScheme.secondaryContainer
                        "Offline — changes pending" -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = saveStatus,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (saveStatus) {
                            "Saving..." -> MaterialTheme.colorScheme.onPrimaryContainer
                            "Saved" -> MaterialTheme.colorScheme.onSecondaryContainer
                            "Offline — changes pending" -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Action Buttons Row (Capture Photo & Sync Now)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = { showCameraChooser = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Capture Photo")
            }

            FilledTonalButton(
                onClick = onSyncClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sync Now")
            }
        }

        // Compact Sync Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Sync Status",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = syncStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Form Fields Title
        Text(
            text = "Personal Details Form",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp)
        )

        // Form fields
        OutlinedTextField(
            value = formState.fullName,
            onValueChange = { viewModel.updateFullName(it) },
            label = { Text("Full Name *") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("fullName"),
            supportingText = validationErrors["fullName"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Person, null) }
        )

        OutlinedTextField(
            value = formState.primaryPhone,
            onValueChange = { viewModel.updatePrimaryPhone(it) },
            label = { Text("Primary Phone Number *") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("primaryPhone"),
            supportingText = validationErrors["primaryPhone"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Phone, null) }
        )

        OutlinedTextField(
            value = formState.alternatePhone,
            onValueChange = { viewModel.updateAlternatePhone(it) },
            label = { Text("Alternate Phone Number") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("alternatePhone"),
            supportingText = validationErrors["alternatePhone"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Phone, null) }
        )

        OutlinedTextField(
            value = formState.email,
            onValueChange = { viewModel.updateEmail(it) },
            label = { Text("Email Address") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("email"),
            supportingText = validationErrors["email"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Email, null) }
        )

        // Date of Birth
        val calendar = Calendar.getInstance()
        val datePickerDialog = android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedDateStr = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                viewModel.updateDateOfBirth(selectedDateStr)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.datePicker.maxDate = System.currentTimeMillis()

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = formState.dateOfBirth,
                onValueChange = {},
                label = { Text("Date of Birth") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                leadingIcon = { Icon(Icons.Default.CalendarToday, null) }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { datePickerDialog.show() }
            )
        }

        OutlinedTextField(
            value = formState.gender,
            onValueChange = { viewModel.updateGender(it) },
            label = { Text("Gender") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Wc, null) }
        )

        OutlinedTextField(
            value = formState.city,
            onValueChange = { viewModel.updateCity(it) },
            label = { Text("City") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.LocationCity, null) }
        )

        OutlinedTextField(
            value = formState.state,
            onValueChange = { viewModel.updateState(it) },
            label = { Text("State") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Map, null) }
        )

        OutlinedTextField(
            value = formState.address,
            onValueChange = { viewModel.updateAddress(it) },
            label = { Text("Full Address") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("address"),
            supportingText = validationErrors["address"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Home, null) }
        )

        OutlinedTextField(
            value = formState.postalCode,
            onValueChange = { viewModel.updatePostalCode(it) },
            label = { Text("Postal Code") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("postalCode"),
            supportingText = validationErrors["postalCode"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.LocalPostOffice, null) }
        )

        OutlinedTextField(
            value = formState.occupation,
            onValueChange = { viewModel.updateOccupation(it) },
            label = { Text("Occupation") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Work, null) }
        )

        OutlinedTextField(
            value = formState.emergencyContactName,
            onValueChange = { viewModel.updateEmergencyContactName(it) },
            label = { Text("Emergency Contact Name") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.ContactPhone, null) }
        )

        OutlinedTextField(
            value = formState.emergencyContactNumber,
            onValueChange = { viewModel.updateEmergencyContactNumber(it) },
            label = { Text("Emergency Contact Number") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            leadingIcon = { Icon(Icons.Default.Phone, null) }
        )

        OutlinedTextField(
            value = formState.notes,
            onValueChange = { viewModel.updateNotes(it) },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            isError = validationErrors.containsKey("notes"),
            supportingText = validationErrors["notes"]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) }
        )

        // Read-only fields
        OutlinedTextField(
            value = deviceId,
            onValueChange = {},
            label = { Text("Device ID") },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) }
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = {},
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            leadingIcon = { Icon(Icons.Default.Smartphone, null) }
        )
    }

    if (showCameraChooser) {
        AlertDialog(
            onDismissRequest = { showCameraChooser = false },
            title = { Text("Choose Camera") },
            text = { Text("Select which camera you would like to use to capture the photo.") },
            confirmButton = {
                Button(onClick = {
                    showCameraChooser = false
                    onCapturePhoto("FRONT")
                }) {
                    Text("Front Camera")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showCameraChooser = false
                    onCapturePhoto("BACK")
                }) {
                    Text("Back Camera")
                }
            }
        )
    }
}

@Composable
fun SyncDiagnosticsScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    onSyncClick: () -> Unit,
    deviceId: String,
    snackbarHostState: SnackbarHostState
) {
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val lastMediaSyncTime by viewModel.lastMediaSyncTime.collectAsState()
    val lastMediaScanTime by viewModel.lastMediaScanTime.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val mediaDiscoveredCount by viewModel.mediaDiscoveredCount.collectAsState()
    val mediaUploadedCount by viewModel.mediaUploadedCount.collectAsState()
    val mediaFailedCount by viewModel.mediaFailedCount.collectAsState()
    val lastMediaError by viewModel.lastMediaError.collectAsState()

    val cloudinaryTestResult by viewModel.cloudinaryTestResult.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sync Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Device Identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Device ID:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(deviceId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sync Status Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Current State:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(syncStatus, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider()

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Data Sync:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(lastSyncTime, fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Media Scan:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(lastMediaScanTime, fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Media Sync:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(lastMediaSyncTime, fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Media Sync Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Discovered Count:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mediaDiscoveredCount.toString(), fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Uploaded Count:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mediaUploadedCount.toString(), fontWeight = FontWeight.Bold)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Failed Count:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mediaFailedCount.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }

                lastMediaError?.let { err ->
                    Text("Last Media Error: $err", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Exact Sync Errors
        errorMessage?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Sync Error: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // Action Buttons
        Button(
            onClick = onSyncClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text("Sync Now")
        }

        Button(
            onClick = { viewModel.triggerMediaSyncNow() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            enabled = !isLoading
        ) {
            Text("Retry Failed Media")
        }

        Button(
            onClick = {
                viewModel.testCloudinaryUpload()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            enabled = !isLoading
        ) {
            Text("Test Cloudinary Upload")
        }

        cloudinaryTestResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Cloudinary Test Result Code: ${result.statusCode}")
                    Text("Response: ${result.responseBody}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(
                    "Debug Info",
                    "Device ID: $deviceId\nStatus: $syncStatus\nLast Sync: $lastSyncTime\nMedia Discovered: $mediaDiscoveredCount\nMedia Uploaded: $mediaUploadedCount\nMedia Failed: $mediaFailedCount\nErrors: $errorMessage"
                )
                clipboard.setPrimaryClip(clip)
                scope.launch {
                    snackbarHostState.showSnackbar("Debug info copied to clipboard")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Debug Information")
        }

        Button(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Delete Synced Data")
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Data?") },
            text = { Text("This will remove all synced records for this device from the Firestore dashboard.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSyncedData()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
