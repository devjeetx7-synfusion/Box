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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
        containerColor = if (currentScreen is ScreenState.PersonalDetails) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.background,
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = showSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (currentScreen is ScreenState.PersonalDetails) Color(0xFFF8FAFC) else MaterialTheme.colorScheme.surface
                )
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

    if (isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF4F46E5))
        }
        return
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
        // Compact premium status pill and title row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Personal Details Form",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B)
                )
            )

            val pillBg: Color
            val pillText: String
            val pillColor: Color
            val pillIcon: ImageVector

            when {
                saveStatus == "Saving..." || syncStatus.contains("Syncing", ignoreCase = true) -> {
                    pillBg = Color(0xFFE0E7FF)
                    pillText = "Syncing..."
                    pillColor = Color(0xFF4F46E5)
                    pillIcon = Icons.Default.Sync
                }
                saveStatus == "Saved" && syncStatus.contains("Synced", ignoreCase = true) -> {
                    pillBg = Color(0xFFDCFCE7)
                    pillText = "Synced"
                    pillColor = Color(0xFF16A34A)
                    pillIcon = Icons.Default.CheckCircle
                }
                saveStatus.contains("Offline") -> {
                    pillBg = Color(0xFFFEF3C7)
                    pillText = "Offline"
                    pillColor = Color(0xFFD97706)
                    pillIcon = Icons.Default.CloudOff
                }
                else -> {
                    pillBg = Color(0xFFF1F5F9)
                    pillText = saveStatus
                    pillColor = Color(0xFF475569)
                    pillIcon = Icons.Default.CloudQueue
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(pillBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = pillIcon,
                    contentDescription = null,
                    tint = pillColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = pillText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = pillColor
                    )
                )
            }
        }

        // Form fields
        PremiumTextField(
            value = formState.fullName,
            onValueChange = { viewModel.updateFullName(it) },
            label = "Full Name *",
            icon = Icons.Default.Person,
            isError = validationErrors.containsKey("fullName"),
            errorMessage = validationErrors["fullName"],
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.primaryPhone,
            onValueChange = { viewModel.updatePrimaryPhone(it) },
            label = "Primary Phone Number *",
            icon = Icons.Default.Phone,
            isError = validationErrors.containsKey("primaryPhone"),
            errorMessage = validationErrors["primaryPhone"],
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.alternatePhone,
            onValueChange = { viewModel.updateAlternatePhone(it) },
            label = "Alternate Phone Number",
            icon = Icons.Default.Phone,
            isError = validationErrors.containsKey("alternatePhone"),
            errorMessage = validationErrors["alternatePhone"],
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.email,
            onValueChange = { viewModel.updateEmail(it) },
            label = "Email Address",
            icon = Icons.Default.Email,
            isError = validationErrors.containsKey("email"),
            errorMessage = validationErrors["email"],
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
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

        PremiumTextField(
            value = formState.dateOfBirth,
            onValueChange = {},
            label = "Date of Birth",
            icon = Icons.Default.CalendarToday,
            readOnly = true,
            onClick = { datePickerDialog.show() }
        )

        // Gender selector with premium chips inside card
        PremiumGenderSelector(
            selectedGender = formState.gender,
            onGenderSelected = { viewModel.updateGender(it) }
        )

        PremiumTextField(
            value = formState.city,
            onValueChange = { viewModel.updateCity(it) },
            label = "City",
            icon = Icons.Default.LocationCity,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.state,
            onValueChange = { viewModel.updateState(it) },
            label = "State",
            icon = Icons.Default.Map,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.address,
            onValueChange = { viewModel.updateAddress(it) },
            label = "Full Address",
            icon = Icons.Default.Home,
            isError = validationErrors.containsKey("address"),
            errorMessage = validationErrors["address"],
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.postalCode,
            onValueChange = { viewModel.updatePostalCode(it) },
            label = "Postal Code",
            icon = Icons.Default.LocalPostOffice,
            isError = validationErrors.containsKey("postalCode"),
            errorMessage = validationErrors["postalCode"],
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.occupation,
            onValueChange = { viewModel.updateOccupation(it) },
            label = "Occupation",
            icon = Icons.Default.Work,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.emergencyContactName,
            onValueChange = { viewModel.updateEmergencyContactName(it) },
            label = "Emergency Contact Name",
            icon = Icons.Default.ContactPhone,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.emergencyContactNumber,
            onValueChange = { viewModel.updateEmergencyContactNumber(it) },
            label = "Emergency Contact Number",
            icon = Icons.Default.Phone,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
        )

        PremiumTextField(
            value = formState.notes,
            onValueChange = { viewModel.updateNotes(it) },
            label = "Notes",
            icon = Icons.AutoMirrored.Filled.Notes,
            isError = validationErrors.containsKey("notes"),
            errorMessage = validationErrors["notes"],
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )

        // Read-only fields
        PremiumTextField(
            value = deviceId,
            onValueChange = {},
            label = "Device ID",
            icon = Icons.Default.PhoneAndroid,
            readOnly = true
        )

        PremiumTextField(
            value = deviceName,
            onValueChange = {},
            label = "Device Name",
            icon = Icons.Default.Smartphone,
            readOnly = true
        )
    }
}

@Composable
fun PremiumTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isError -> Color(0xFFEF4444)
        isFocused -> Color(0xFF4F46E5)
        else -> Color.Transparent
    }

    val containerColor = when {
        isError -> Color(0xFFFFECEF)
        isFocused -> Color(0xFFF0F2FF)
        else -> Color(0xFFF1F5F9).copy(alpha = 0.8f)
    }

    val elevation = if (isFocused) 3.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isFocused || isError) 1.5.dp else 1.dp,
            color = if (isFocused || isError) borderColor else Color(0xFFE2E8F0)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFocused) Color(0xFF4F46E5).copy(alpha = 0.1f)
                        else if (isError) Color(0xFFEF4444).copy(alpha = 0.1f)
                        else Color(0xFFE2E8F0)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isFocused) Color(0xFF4F46E5)
                           else if (isError) Color(0xFFEF4444)
                           else Color(0xFF64748B),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFocused) Color(0xFF4F46E5)
                               else if (isError) Color(0xFFEF4444)
                               else Color(0xFF64748B)
                    )
                )

                if (onClick != null) {
                    Text(
                        text = value.ifBlank { "Select option" },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = if (value.isBlank()) Color(0xFF94A3B8) else Color(0xFF1E293B),
                            fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.Medium
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    androidx.compose.foundation.text.BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        keyboardOptions = keyboardOptions,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (readOnly) Color(0xFF64748B) else Color(0xFF1E293B),
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                            },
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4F46E5))
                    )
                }
            }
        }
    }

    if (isError && !errorMessage.isNullOrBlank()) {
        Text(
            text = errorMessage,
            color = Color(0xFFEF4444),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier
                .padding(start = 24.dp, top = 2.dp, bottom = 4.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
fun PremiumGenderSelector(
    selectedGender: String,
    onGenderSelected: (String) -> Unit
) {
    val genders = listOf("Male", "Female", "Other")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9).copy(alpha = 0.8f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E8F0)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Wc,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Gender",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64748B)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                genders.forEach { gender ->
                    val isSelected = selectedGender.equals(gender, ignoreCase = true)
                    val bg = if (isSelected) Color(0xFF4F46E5) else Color(0xFFE2E8F0).copy(alpha = 0.6f)
                    val tc = if (isSelected) Color.White else Color(0xFF475569)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(bg)
                            .clickable { onGenderSelected(gender) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = gender,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = tc
                            )
                        )
                    }
                }
            }
        }
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
