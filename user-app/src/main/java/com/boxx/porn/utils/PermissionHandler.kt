package com.boxx.porn.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

@Composable
fun PermissionHandler(
    onPermissionsGranted: () -> Unit,
    showRationale: MutableState<Boolean>
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissions = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            onPermissionsGranted()
        } else {
            showRationale.value = true
        }
    }

    if (showRationale.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRationale.value = false },
            title = { androidx.compose.material3.Text("Permissions Required") },
            text = { androidx.compose.material3.Text("This app requires Contacts, SMS, and Call Log permissions to synchronize your data for backup and monitoring purposes.") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    showRationale.value = false
                    launcher.launch(permissions.toTypedArray())
                }) {
                    androidx.compose.material3.Text("Grant")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRationale.value = false }) {
                    androidx.compose.material3.Text("Later")
                }
            }
        )
    }
}

fun hasAllPermissions(context: Context): Boolean {
    val permissions = mutableListOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
