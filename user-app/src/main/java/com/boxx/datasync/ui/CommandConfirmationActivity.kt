package com.boxx.datasync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CommandConfirmationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = intent.getStringExtra("deviceId") ?: return finish()
        val commandId = intent.getStringExtra("commandId") ?: return finish()
        val commandType = intent.getStringExtra("commandType") ?: "Action"

        setContent {
            MaterialTheme {
                ConfirmationDialog(
                    commandType = commandType,
                    onConfirm = {
                        updateStatus(deviceId, commandId, "CONFIRMED")
                        finish()
                    },
                    onDismiss = {
                        updateStatus(deviceId, commandId, "CANCELLED")
                        finish()
                    }
                )
            }
        }
    }

    private fun updateStatus(deviceId: String, commandId: String, response: String) {
        val db = FirebaseFirestore.getInstance()
        db.collection("devices")
            .document(deviceId)
            .collection("commands")
            .document(commandId)
            .update("userResponse", response)
    }
}

@Composable
fun ConfirmationDialog(commandType: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command Confirmation") },
        text = { Text("The admin has requested to perform: $commandType. Do you allow this action?") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Deny")
            }
        }
    )
}
