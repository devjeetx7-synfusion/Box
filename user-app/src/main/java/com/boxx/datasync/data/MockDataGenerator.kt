package com.boxx.datasync.data

import com.boxx.datasync.domain.model.CallLog
import com.boxx.datasync.domain.model.Contact
import com.boxx.datasync.domain.model.NotificationData
import com.boxx.datasync.domain.model.SMS
import com.boxx.datasync.utils.DataUtils.hashString

object MockDataGenerator {

    fun generateMockContacts(): List<Contact> {
        return listOf(
            createContact("John Doe (Demo)", "+1 555-0101"),
            createContact("Jane Smith (Demo)", "+1 555-0102"),
            createContact("Alice Johnson (Demo)", "+1 555-0103"),
            createContact("Bob Brown (Demo)", "+1 555-0104"),
            createContact("Charlie Davis (Demo)", "+1 555-0105")
        )
    }

    private fun createContact(name: String, phone: String): Contact {
        val id = hashString("$name$phone")
        return Contact(id, name, phone, System.currentTimeMillis())
    }

    fun generateMockSMS(): List<SMS> {
        val now = System.currentTimeMillis()
        return listOf(
            createSms("+1 555-0101", "Hey, how are you? (Demo)", now - 3600000, 1),
            createSms("+1 555-0102", "Don't forget the meeting (Demo)", now - 7200000, 1),
            createSms("+1 555-0103", "I'm on my way (Demo)", now - 10800000, 2),
            createSms("+1 555-0104", "Great job today! (Demo)", now - 14400000, 1),
            createSms("+1 555-0105", "Check this out (Demo)", now - 18000000, 1)
        )
    }

    private fun createSms(address: String, body: String, date: Long, type: Int): SMS {
        val id = hashString("$address$body$date")
        return SMS(id, address, body, date, type)
    }

    fun generateMockCallLogs(): List<CallLog> {
        val now = System.currentTimeMillis()
        return listOf(
            createCallLog("+1 555-0101", "John Doe (Demo)", 1, now - 3600000, 120),
            createCallLog("+1 555-0102", "Jane Smith (Demo)", 2, now - 7200000, 300),
            createCallLog("+1 555-0103", "Alice Johnson (Demo)", 3, now - 10800000, 0),
            createCallLog("+1 555-0104", "Bob Brown (Demo)", 1, now - 14400000, 45),
            createCallLog("+1 555-0105", "Charlie Davis (Demo)", 1, now - 18000000, 90)
        )
    }

    private fun createCallLog(number: String, name: String, type: Int, date: Long, duration: Long): CallLog {
        val id = hashString("$number$date$type")
        return CallLog(id, number, name, type, date, duration)
    }
}
