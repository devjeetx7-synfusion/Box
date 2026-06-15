package com.boxx.datasync.ui.component

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun PermissionRationaleDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions Needed") },
        text = { Text("We need Contacts, SMS, and Call Logs permissions to securely backup your data to your dashboard for educational purposes.") },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not Now") }
        }
    )
}

@Composable
fun PermissionExplanationScreen(
    onBack: () -> Unit,
    onGrant: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("How we use your data") },
        text = { Text("This app is a reference architecture demo. Your data is masked and redacted before synchronization. You can also enable 'Demo Mode' to sync mock data instead of real data.") },
        confirmButton = {
            Button(onClick = onGrant) { Text("I Understand") }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("Back") }
        }
    )
}
