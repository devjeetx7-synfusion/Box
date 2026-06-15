package com.boxx.datasync.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.boxx.datasync.domain.model.CallLog as CallLogModel
import com.boxx.datasync.domain.model.Contact
import com.boxx.datasync.domain.model.SMS

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

                contacts.add(Contact(name, phone, System.currentTimeMillis()))
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

                smsList.add(SMS(address, body, date, type))
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

                callLogs.add(CallLogModel(number, name, type, date, duration))
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
}
