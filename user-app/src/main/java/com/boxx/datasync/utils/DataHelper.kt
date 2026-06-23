package com.boxx.datasync.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.boxx.datasync.domain.model.CallLog as CallLogModel
import com.boxx.datasync.domain.model.Contact
import com.boxx.datasync.domain.model.NotificationData
import com.boxx.datasync.domain.model.SMS
import com.boxx.datasync.utils.DataUtils.hashString

object DataHelper {

    @SuppressLint("Range")
    fun fetchContacts(context: Context, maskData: Boolean = false, sinceTimestamp: Long = 0): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val selection = if (sinceTimestamp > 0) "${ContactsContract.CommonDataKinds.Phone.CONTACT_LAST_UPDATED_TIMESTAMP} > ?" else null
        val selectionArgs = if (sinceTimestamp > 0) arrayOf(sinceTimestamp.toString()) else null

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, selection, selectionArgs, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: "Unknown"
                var phone = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))?.takeIf { it.isNotBlank() } ?: "Number unavailable"

                if (maskData) {
                    phone = maskPhoneNumber(phone)
                }

                val id = hashString("$name$phone")
                contacts.add(Contact(id, name, phone, System.currentTimeMillis()))
            }
        }
        return contacts
    }

    @SuppressLint("Range")
    fun fetchSMS(context: Context, maskData: Boolean = false, sinceTimestamp: Long = 0): List<SMS> {
        val smsList = mutableListOf<SMS>()
        val selection = if (sinceTimestamp > 0) "${Telephony.Sms.DATE} > ?" else null
        val selectionArgs = if (sinceTimestamp > 0) arrayOf(sinceTimestamp.toString()) else null

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null, selection, selectionArgs, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                var address = it.getString(it.getColumnIndex(Telephony.Sms.ADDRESS))?.takeIf { it.isNotBlank() } ?: "Number unavailable"
                var body = it.getString(it.getColumnIndex(Telephony.Sms.BODY)) ?: ""
                val date = it.getLong(it.getColumnIndex(Telephony.Sms.DATE))
                val type = it.getInt(it.getColumnIndex(Telephony.Sms.TYPE))

                if (maskData) {
                    address = maskPhoneNumber(address)
                    body = redactMessageBody(body)
                }

                val id = hashString("$address$body$date")
                smsList.add(SMS(id, address, body, date, type))
            }
        }
        return smsList
    }

    @SuppressLint("Range")
    fun fetchCallLogs(context: Context, maskData: Boolean = false, sinceTimestamp: Long = 0): List<CallLogModel> {
        val callLogs = mutableListOf<CallLogModel>()
        val selection = if (sinceTimestamp > 0) "${CallLog.Calls.DATE} > ?" else null
        val selectionArgs = if (sinceTimestamp > 0) arrayOf(sinceTimestamp.toString()) else null

        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, selection, selectionArgs, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                var number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER))?.takeIf { it.isNotBlank() } ?: "Number unavailable"
                val name = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME)) ?: "Unknown"
                val type = it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))
                val date = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                val duration = it.getLong(it.getColumnIndex(CallLog.Calls.DURATION))

                if (maskData) {
                    number = maskPhoneNumber(number)
                }

                val id = hashString("$number$date$type")
                callLogs.add(CallLogModel(id, number, name, type, date, duration))
            }
        }
        return callLogs
    }

    fun maskPhoneNumber(phone: String): String {
        if (phone.length < 7) return "***"
        val firstPart = phone.take(3)
        val lastPart = phone.takeLast(3)
        return "$firstPart***$lastPart"
    }

    fun redactMessageBody(body: String): String {
        if (body.isBlank()) return ""
        val words = body.split(" ")
        if (words.size <= 2) return "[REDACTED]"
        return words.take(2).joinToString(" ") + "... [REDACTED]"
    }

    fun fetchNotifications(context: Context): List<NotificationData> {
        return emptyList()
    }

    @SuppressLint("MissingPermission")
    fun getSimState(context: Context): Map<String, Any> {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

        val result = mutableMapOf<String, Any>(
            "sim1Carrier" to "No SIM available",
            "sim2Carrier" to "No SIM available",
            "sim1Number" to "Number not provided by carrier",
            "sim2Number" to "Number not provided by carrier",
            "sim1SubscriptionId" to -1,
            "sim2SubscriptionId" to -1,
            "sim1Ready" to false,
            "sim2Ready" to false
        )

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.e("DataHelper", "SIM_STATE_FAILED: Missing READ_PHONE_STATE permission")
            return result
        }

        try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (!activeSubscriptionInfoList.isNullOrEmpty()) {
                // Sort by slot index to ensure consistent mapping
                val sortedList = activeSubscriptionInfoList.sortedBy { it.simSlotIndex }

                for (subscriptionInfo in sortedList) {
                    val slotIndex = subscriptionInfo.simSlotIndex
                    val subId = subscriptionInfo.subscriptionId
                    val carrierName = subscriptionInfo.carrierName?.toString()?.takeIf { it.isNotBlank() && it != "Android" } ?: "Unknown Carrier"

                    var number: String? = null

                    // Try manual override first
                    val manualNumber = prefs.getString("manual_sim_number_${slotIndex + 1}", null)
                    if (!manualNumber.isNullOrBlank()) {
                        number = manualNumber
                    }

                    // Try SubscriptionManager
                    if (number == null) {
                        number = try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                subscriptionManager.getPhoneNumber(subId)
                            } else {
                                @Suppress("DEPRECATION")
                                subscriptionInfo.number
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    // Try TelephonyManager as fallback for primary slot
                    if (number.isNullOrBlank() && slotIndex == 0) {
                        number = try {
                            @Suppress("DEPRECATION")
                            telephonyManager.line1Number
                        } catch (e: Exception) {
                            null
                        }
                    }

                    val finalNumber = number?.takeIf { it.isNotBlank() } ?: "Number not provided by carrier"

                    if (slotIndex == 0) {
                        result["sim1Carrier"] = carrierName
                        result["sim1Number"] = finalNumber
                        result["sim1SubscriptionId"] = subId
                        result["sim1Ready"] = true
                    } else if (slotIndex == 1) {
                        result["sim2Carrier"] = carrierName
                        result["sim2Number"] = finalNumber
                        result["sim2SubscriptionId"] = subId
                        result["sim2Ready"] = true
                    } else if (slotIndex > 1 && !(result["sim2Ready"] as Boolean)) {
                        result["sim2Carrier"] = carrierName
                        result["sim2Number"] = finalNumber
                        result["sim2SubscriptionId"] = subId
                        result["sim2Ready"] = true
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DataHelper", "SIM_STATE_ERROR", e)
        }

        android.util.Log.d("DataHelper", "SIM_STATE_UPDATED")
        return result
    }
}
