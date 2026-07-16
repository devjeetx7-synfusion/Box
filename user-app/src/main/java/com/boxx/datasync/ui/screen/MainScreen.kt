package com.boxx.datasync.ui.screen

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Patterns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boxx.datasync.domain.model.DeviceUserDetails
import com.boxx.datasync.ui.viewmodel.MainViewModel
import com.boxx.datasync.utils.DeviceIdHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSyncClick: () -> Unit,
    showSettings: () -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdHelper.getDeviceId(context) }
    val deviceName = remember { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }

    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val lastMediaSyncTime by viewModel.lastMediaSyncTime.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val userDetailsLoaded by viewModel.userDetails.collectAsState()
    val isSavingDetails by viewModel.isSavingDetails.collectAsState()
    val detailsSaveError by viewModel.detailsSaveError.collectAsState()
    val detailsSaveSuccess by viewModel.detailsSaveSuccess.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Form fields
    var fullName by remember { mutableStateOf("") }
    var primaryPhone by remember { mutableStateOf("") }
    var alternatePhone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var stateField by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var hasLoadedInitialDetails by remember { mutableStateOf(false) }

    LaunchedEffect(userDetailsLoaded) {
        userDetailsLoaded?.let { details ->
            if (!hasLoadedInitialDetails) {
                fullName = details.fullName
                primaryPhone = details.primaryPhone
                alternatePhone = details.alternatePhone
                email = details.email
                city = details.city
                stateField = details.state
                address = details.address
                note = details.note
                hasLoadedInitialDetails = true
            }
        }
    }

    // Validation state
    var fullNameError by remember { mutableStateOf<String?>(null) }
    var primaryPhoneError by remember { mutableStateOf<String?>(null) }
    var alternatePhoneError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var cityError by remember { mutableStateOf<String?>(null) }
    var stateError by remember { mutableStateOf<String?>(null) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var noteError by remember { mutableStateOf<String?>(null) }

    // Display Snackbars for save events
    LaunchedEffect(detailsSaveSuccess) {
        if (detailsSaveSuccess) {
            scope.launch {
                snackbarHostState.showSnackbar("User details saved successfully!")
                viewModel.clearDetailsSnackbarState()
            }
        }
    }

    LaunchedEffect(detailsSaveError) {
        detailsSaveError?.let { errorMsg ->
            scope.launch {
                snackbarHostState.showSnackbar(errorMsg)
                viewModel.clearDetailsSnackbarState()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Data Sync Client", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notification Access")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("App Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = {
                                showMenu = false
                                showSettings()
                            }
                        )
                        if (lastSyncTime != "Never") {
                            DropdownMenuItem(
                                text = { Text("Delete Synced Data", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .padding(bottom = 40.dp), // Extra bottom padding for navigation bar
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status and Last Sync Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sync Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Current State:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = syncStatus,
                                fontWeight = FontWeight.Bold,
                                color = if (syncStatus == "Error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    HorizontalDivider()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Data Sync:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(lastSyncTime, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Media Sync:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (lastMediaSyncTime == "0" || lastMediaSyncTime.isBlank() || lastMediaSyncTime == "Never") "Not synced yet" else lastMediaSyncTime, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Small error section only when an actual error exists
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = "Error", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Synchronization Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onSyncClick,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Retry")
                            }
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Debug Info", "Device ID: $deviceId\nError: $error\nStatus: $syncStatus")
                                    clipboard.setPrimaryClip(clip)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Debug info copied to clipboard")
                                    }
                                }
                            ) {
                                Text("Copy Debug Info")
                            }
                        }
                    }
                }
            }

            // User Details Form Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("User Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

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

                    // Editable fields
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = {
                            fullName = it
                            fullNameError = null
                        },
                        label = { Text("Full Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = fullNameError != null,
                        supportingText = fullNameError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.Person, null) }
                    )

                    OutlinedTextField(
                        value = primaryPhone,
                        onValueChange = {
                            primaryPhone = it
                            primaryPhoneError = null
                        },
                        label = { Text("Primary Phone Number *") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = primaryPhoneError != null,
                        supportingText = primaryPhoneError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.Phone, null) }
                    )

                    OutlinedTextField(
                        value = alternatePhone,
                        onValueChange = {
                            alternatePhone = it
                            alternatePhoneError = null
                        },
                        label = { Text("Alternate Phone Number") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = alternatePhoneError != null,
                        supportingText = alternatePhoneError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.Phone, null) }
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            emailError = null
                        },
                        label = { Text("Email Address") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = emailError != null,
                        supportingText = emailError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.Email, null) }
                    )

                    OutlinedTextField(
                        value = city,
                        onValueChange = {
                            city = it
                            cityError = null
                        },
                        label = { Text("City") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = cityError != null,
                        supportingText = cityError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.LocationCity, null) }
                    )

                    OutlinedTextField(
                        value = stateField,
                        onValueChange = {
                            stateField = it
                            stateError = null
                        },
                        label = { Text("State") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = stateError != null,
                        supportingText = stateError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.Map, null) }
                    )

                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            addressError = null
                        },
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = addressError != null,
                        supportingText = addressError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        leadingIcon = { Icon(Icons.Default.Home, null) }
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = {
                            note = it
                            noteError = null
                        },
                        label = { Text("Note") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = noteError != null,
                        supportingText = noteError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        leadingIcon = { Icon(Icons.Default.Notes, null) }
                    )

                    Button(
                        onClick = {
                            // Validation
                            var hasError = false

                            if (fullName.isBlank()) {
                                fullNameError = "Full Name is required"
                                hasError = true
                            } else if (fullName.length < 2 || fullName.length > 80) {
                                fullNameError = "Full Name must be between 2 and 80 characters"
                                hasError = true
                            }

                            if (primaryPhone.isBlank()) {
                                primaryPhoneError = "Primary Phone is required"
                                hasError = true
                            } else {
                                val norm = primaryPhone.filter { it.isDigit() }
                                if (norm.length !in 7..15) {
                                    primaryPhoneError = "Primary Phone must have 7 to 15 digits"
                                    hasError = true
                                }
                            }

                            if (alternatePhone.isNotBlank()) {
                                val normAlt = alternatePhone.filter { it.isDigit() }
                                if (normAlt.length !in 7..15) {
                                    alternatePhoneError = "Alternate Phone must have 7 to 15 digits"
                                    hasError = true
                                }
                            }

                            if (email.isNotBlank()) {
                                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                                    emailError = "Invalid email format"
                                    hasError = true
                                }
                            }

                            if (city.length > 100) {
                                cityError = "City is too long (max 100 characters)"
                                hasError = true
                            }

                            if (stateField.length > 100) {
                                stateError = "State is too long (max 100 characters)"
                                hasError = true
                            }

                            if (address.length > 300) {
                                addressError = "Address must be less than 300 characters"
                                hasError = true
                            }

                            if (note.length > 500) {
                                noteError = "Note must be less than 500 characters"
                                hasError = true
                            }

                            if (!hasError) {
                                val currentDetails = DeviceUserDetails(
                                    deviceId = deviceId,
                                    fullName = fullName,
                                    primaryPhone = primaryPhone,
                                    alternatePhone = alternatePhone,
                                    email = email,
                                    city = city,
                                    state = stateField,
                                    address = address,
                                    note = note,
                                    deviceName = deviceName,
                                    createdAt = userDetailsLoaded?.createdAt ?: System.currentTimeMillis(),
                                    updatedAt = System.currentTimeMillis()
                                )
                                viewModel.saveUserDetails(currentDetails) { success, error ->
                                    // Managed via LiveEffect but optional callbacks can go here
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSavingDetails
                    ) {
                        if (isSavingDetails) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            val buttonText = if (userDetailsLoaded != null) "Update Details" else "Save Details"
                            Text(buttonText)
                        }
                    }
                }
            }

            // Sync Now Button
            Button(
                onClick = onSyncClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading
            ) {
                Text("Sync Now", fontSize = 18.sp)
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
}
