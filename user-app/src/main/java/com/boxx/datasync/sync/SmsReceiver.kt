package com.boxx.datasync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import com.boxx.datasync.domain.model.SmsForwardingConfig
import com.boxx.datasync.utils.DeviceIdHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        Log.d("SmsReceiver", "SMS_RECEIVER_TRIGGERED")
        Log.d("SmsReceiver", "SMS_FORWARDING_RECEIVER_TRIGGERED")
        SyncScheduler.enqueueIncremental(context)
        Log.d("SmsReceiver", "SMS_WORK_ENQUEUED")

        val pendingResult = goAsync()
        scope.launch {
            try {
                // Synchronize with Firestore in background if online/available
                val deviceId = DeviceIdHelper.getDeviceId(context)
                if (deviceId.isNotBlank()) {
                    try {
                        val db = FirebaseFirestore.getInstance()
                        val snapshot = db.collection("devices")
                            .document(deviceId)
                            .collection("settings")
                            .document("smsForwarding")
                            .get()
                            .await()
                        val firestoreConfig = snapshot.toObject(SmsForwardingConfig::class.java)
                        if (firestoreConfig != null) {
                            val subManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                            val subInfo = try {
                                subManager?.activeSubscriptionInfoList?.find { it.simSlotIndex == (firestoreConfig.simSlot - 1) }
                            } catch (e: SecurityException) {
                                null
                            }
                            val resolvedSubId = subInfo?.subscriptionId ?: -1

                            val localPrefs = context.getSharedPreferences("sms_forwarding_prefs", Context.MODE_PRIVATE)
                            localPrefs.edit().apply {
                                putBoolean("enabled", firestoreConfig.enabled)
                                putString("destinationNumber", firestoreConfig.destinationNumber)
                                putInt("simSlot", firestoreConfig.simSlot)
                                putInt("subscriptionId", resolvedSubId)
                                putLong("updatedAt", firestoreConfig.updatedAt)
                                apply()
                            }
                            Log.d("SmsReceiver", "SMS_FORWARDING_FIRESTORE_CONFIG_SAVED: synced from Firestore")
                        }
                    } catch (e: Exception) {
                        Log.e("SmsReceiver", "Error syncing SMS forwarding config from Firestore", e)
                    }
                }

                // Query the local cached configuration first, bypassing Firestore reads on every broadcast
                val prefs = context.getSharedPreferences("sms_forwarding_prefs", Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("enabled", false)
                val destinationNumber = prefs.getString("destinationNumber", "") ?: ""
                val simSlot = prefs.getInt("simSlot", 1)

                Log.d("SmsReceiver", "SMS_FORWARDING_CONFIG_RECEIVED: localEnabled=$enabled localDestination=$destinationNumber simSlot=$simSlot")

                if (enabled && destinationNumber.isNotBlank()) {
                    val bundle = intent.extras
                    val pdus = bundle?.get("pdus") as? Array<*>
                    if (pdus != null) {
                        val format = bundle.getString("format")
                        val messages = pdus.map { pdu ->
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                SmsMessage.createFromPdu(pdu as ByteArray, format)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsMessage.createFromPdu(pdu as ByteArray)
                            }
                        }
                        if (messages.isNotEmpty()) {
                            val sender = messages[0].originatingAddress ?: "Unknown"
                            val combinedBody = messages.joinToString("") { it.messageBody ?: "" }
                            Log.d("SmsReceiver", "SMS_FORWARDING_MULTIPART_COMBINED: From=$sender parts=${messages.size} length=${combinedBody.length}")

                            // Prevent forwarding loops:
                            // 1. Do not re-forward messages generated by this application
                            if (combinedBody.contains("Fwd from") || combinedBody.contains("[Fwd]")) {
                                Log.d("SmsReceiver", "SMS_FORWARDING_LOOP_PREVENTED: Message contains forwarding marker")
                                return@launch
                            }
                            // 2. Do not forward messages whose originating address is our forwarding destination
                            if (sender.equals(destinationNumber, ignoreCase = true)) {
                                Log.d("SmsReceiver", "SMS_FORWARDING_LOOP_PREVENTED: Sender is the destination number")
                                return@launch
                            }

                            processForwardSms(context, destinationNumber, sender, combinedBody, simSlot)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error in SMS forwarding", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun processForwardSms(
        context: Context,
        destinationNumber: String,
        sender: String,
        combinedBody: String,
        simSlot: Int
    ) {
        val hasReceive = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasSend = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasPhoneState = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val missingPermissions = mutableListOf<String>()
        if (!hasReceive) missingPermissions.add("RECEIVE_SMS")
        if (!hasSend) missingPermissions.add("SEND_SMS")
        if (!hasPhoneState) missingPermissions.add("READ_PHONE_STATE")

        if (missingPermissions.isNotEmpty()) {
            val errMsg = "Missing permissions: ${missingPermissions.joinToString(", ")}"
            Log.e("SmsReceiver", "SMS_FORWARDING_FAILED: $errMsg")
            updateSmsForwardingStatusInFirestore(context, enabled = true, destinationNumber = destinationNumber, simSlot = simSlot, status = "FAILED", error = errMsg, sender = sender)
            return
        }

        val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
        if (subscriptionManager == null) {
            val errMsg = "SubscriptionManager is unavailable"
            Log.e("SmsReceiver", "SMS_FORWARDING_FAILED: $errMsg")
            updateSmsForwardingStatusInFirestore(context, enabled = true, destinationNumber = destinationNumber, simSlot = simSlot, status = "FAILED", error = errMsg, sender = sender)
            return
        }

        val activeList = try {
            subscriptionManager.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            val errMsg = "SecurityException accessing SubscriptionManager: ${e.localizedMessage}"
            Log.e("SmsReceiver", "SMS_FORWARDING_FAILED: $errMsg")
            updateSmsForwardingStatusInFirestore(context, enabled = true, destinationNumber = destinationNumber, simSlot = simSlot, status = "FAILED", error = errMsg, sender = sender)
            return
        }

        val info = activeList?.find { it.simSlotIndex == (simSlot - 1) }
        if (info == null) {
            val errMsg = "Configured SIM $simSlot is not available"
            Log.e("SmsReceiver", "SMS_FORWARDING_FAILED: $errMsg")
            updateSmsForwardingStatusInFirestore(context, enabled = true, destinationNumber = destinationNumber, simSlot = simSlot, status = "FAILED", error = errMsg, sender = sender)
            return
        }

        val resolvedSubId = info.subscriptionId
        Log.d("SmsReceiver", "SMS_FORWARDING_SIM_RESOLVED: SIM $simSlot resolved, subId=$resolvedSubId")

        val smsManager = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java).createForSubscriptionId(resolvedSubId)
            } else {
                @Suppress("DEPRECATION")
                android.telephony.SmsManager.getSmsManagerForSubscriptionId(resolvedSubId)
            }
        } catch (e: Exception) {
            val errMsg = "Failed to obtain SmsManager: ${e.localizedMessage}"
            Log.e("SmsReceiver", "SMS_FORWARDING_FAILED: $errMsg")
            updateSmsForwardingStatusInFirestore(context, enabled = true, destinationNumber = destinationNumber, simSlot = simSlot, status = "FAILED", error = errMsg, sender = sender)
            return
        }

        val messageText = "Fwd from $sender: $combinedBody"
        val parts = smsManager.divideMessage(messageText)

        Log.d("SmsReceiver", "SMS_FORWARDING_SEND_STARTED: parts=${parts.size} destination=$destinationNumber")
        updateSmsForwardingStatusInFirestore(context, enabled = true, destinationNumber = destinationNumber, simSlot = simSlot, status = "SENDING", error = null, sender = sender)

        val sentAction = "com.boxx.datasync.SMS_SENT_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val deliveredAction = "com.boxx.datasync.SMS_DELIVERED_${System.currentTimeMillis()}_${(1000..9999).random()}"

        val sentIntent = android.app.PendingIntent.getBroadcast(
            context,
            0,
            android.content.Intent(sentAction).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val deliveredIntent = android.app.PendingIntent.getBroadcast(
            context,
            0,
            android.content.Intent(deliveredAction).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val appContext = context.applicationContext
        val receiverFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else 0

        val sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                appContext.unregisterReceiver(this)
                val resultCode = resultCode
                val errorMsg = when (resultCode) {
                    android.app.Activity.RESULT_OK -> null
                    android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                    android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                    android.telephony.SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                    android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                    else -> "Unknown error ($resultCode)"
                }

                Log.d("SmsReceiver", "SMS_FORWARDING_SENT_RESULT: resultCode=$resultCode, error=$errorMsg")
                if (resultCode == android.app.Activity.RESULT_OK) {
                    Log.d("SmsReceiver", "SMS_FORWARDING_SENT_SUCCESS")
                    updateSmsForwardingResult(
                        context = appContext,
                        status = "SUCCESS",
                        error = null,
                        resultCode = resultCode,
                        success = true,
                        destination = destinationNumber,
                        sender = sender
                    )
                } else {
                    Log.e("SmsReceiver", "SMS_FORWARDING_FAILED: code=$resultCode error=$errorMsg")
                    updateSmsForwardingResult(
                        context = appContext,
                        status = "FAILED",
                        error = errorMsg ?: "SMS transmission failed",
                        resultCode = resultCode,
                        success = false,
                        destination = destinationNumber,
                        sender = sender
                    )
                }
            }
        }

        val deliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                appContext.unregisterReceiver(this)
                Log.d("SmsReceiver", "SMS_FORWARDING_DELIVERY_SUCCESS")
                // Track delivery success in preferences or log
            }
        }

        appContext.registerReceiver(sentReceiver, android.content.IntentFilter(sentAction), receiverFlags)
        appContext.registerReceiver(deliveredReceiver, android.content.IntentFilter(deliveredAction), receiverFlags)

        try {
            if (parts.size > 1) {
                val sentIntents = ArrayList<android.app.PendingIntent>().apply {
                    repeat(parts.size) { add(sentIntent) }
                }
                val deliveredIntents = ArrayList<android.app.PendingIntent>().apply {
                    repeat(parts.size) { add(deliveredIntent) }
                }
                smsManager.sendMultipartTextMessage(destinationNumber, null, parts, sentIntents, deliveredIntents)
            } else {
                smsManager.sendTextMessage(destinationNumber, null, messageText, sentIntent, deliveredIntent)
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "SMS_FORWARDING_FAILED to send", e)
            try { appContext.unregisterReceiver(sentReceiver) } catch(_: Exception) {}
            try { appContext.unregisterReceiver(deliveredReceiver) } catch(_: Exception) {}
            updateSmsForwardingResult(
                context = appContext,
                status = "FAILED",
                error = e.localizedMessage ?: "Failed to invoke SmsManager send",
                resultCode = -1,
                success = false,
                destination = destinationNumber,
                sender = sender
            )
        }
    }

    private fun updateSmsForwardingStatusInFirestore(
        context: Context,
        enabled: Boolean,
        destinationNumber: String,
        simSlot: Int,
        status: String,
        error: String?,
        sender: String
    ) {
        val now = System.currentTimeMillis()
        val deviceId = DeviceIdHelper.getDeviceId(context)
        if (deviceId.isBlank()) return

        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices")
            .document(deviceId)
            .collection("settings")
            .document("smsForwarding")

        scope.launch {
            try {
                val updates = mutableMapOf<String, Any>(
                    "enabled" to enabled,
                    "destinationNumber" to destinationNumber,
                    "simSlot" to simSlot,
                    "status" to status,
                    "lastAttemptAt" to now,
                    "lastError" to (error ?: ""),
                    "lastSender" to sender,
                    "updatedAt" to now
                )
                docRef.update(updates).await()
            } catch (e: Exception) {
                try {
                    val updates = mapOf(
                        "enabled" to enabled,
                        "destinationNumber" to destinationNumber,
                        "simSlot" to simSlot,
                        "status" to status,
                        "lastAttemptAt" to now,
                        "lastError" to (error ?: ""),
                        "lastSender" to sender,
                        "updatedAt" to now
                    )
                    docRef.set(updates, com.google.firebase.firestore.SetOptions.merge()).await()
                } catch (e2: Exception) {
                    Log.e("SmsReceiver", "Error setting status", e2)
                }
            }
        }
    }

    private fun updateSmsForwardingResult(
        context: Context,
        status: String,
        error: String?,
        resultCode: Int,
        success: Boolean,
        destination: String,
        sender: String
    ) {
        val prefs = context.getSharedPreferences("sms_forwarding_prefs", Context.MODE_PRIVATE)
        val lastSuccessAt = prefs.getLong("lastForwardSuccessAt", 0L)
        val now = System.currentTimeMillis()

        prefs.edit().apply {
            putLong("lastForwardAttemptAt", now)
            if (success) {
                putLong("lastForwardSuccessAt", now)
            }
            putString("lastForwardSender", sender)
            putString("lastForwardDestination", destination)
            putString("lastForwardError", error ?: "")
            putInt("lastForwardResultCode", resultCode)
            apply()
        }

        val deviceId = DeviceIdHelper.getDeviceId(context)
        if (deviceId.isBlank()) return

        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices")
            .document(deviceId)
            .collection("settings")
            .document("smsForwarding")

        scope.launch {
            try {
                val updates = mutableMapOf<String, Any>(
                    "status" to status,
                    "lastAttemptAt" to now,
                    "lastSuccessAt" to (if (success) now else lastSuccessAt),
                    "lastError" to (error ?: ""),
                    "lastSender" to sender,
                    "lastForwardResultCode" to resultCode,
                    "updatedAt" to now
                )
                docRef.update(updates).await()
                Log.d("SmsReceiver", "SMS_FORWARDING_SENT_SUCCESS")
            } catch (e: Exception) {
                try {
                    val updates = mapOf(
                        "status" to status,
                        "lastAttemptAt" to now,
                        "lastSuccessAt" to (if (success) now else lastSuccessAt),
                        "lastError" to (error ?: ""),
                        "lastSender" to sender,
                        "lastForwardResultCode" to resultCode,
                        "updatedAt" to now
                    )
                    docRef.set(updates, com.google.firebase.firestore.SetOptions.merge()).await()
                } catch (e2: Exception) {
                    Log.e("SmsReceiver", "Error saving forwarding results to Firestore", e2)
                }
            }
        }
    }
}