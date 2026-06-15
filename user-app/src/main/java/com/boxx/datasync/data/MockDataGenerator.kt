package com.boxx.datasync.data

import com.boxx.datasync.domain.model.CallLog
import com.boxx.datasync.domain.model.Contact
import com.boxx.datasync.domain.model.NotificationData
import com.boxx.datasync.domain.model.SMS

object MockDataGenerator {

    fun generateMockContacts(): List<Contact> {
        return listOf(
            Contact("John Doe (Demo)", "+1 555-0101", System.currentTimeMillis()),
            Contact("Jane Smith (Demo)", "+1 555-0102", System.currentTimeMillis()),
            Contact("Alice Johnson (Demo)", "+1 555-0103", System.currentTimeMillis()),
            Contact("Bob Brown (Demo)", "+1 555-0104", System.currentTimeMillis()),
            Contact("Charlie Davis (Demo)", "+1 555-0105", System.currentTimeMillis())
        )
    }

    fun generateMockSMS(): List<SMS> {
        return listOf(
            SMS("+1 555-0101", "Hey, how are you? (Demo)", System.currentTimeMillis() - 3600000, 1),
            SMS("+1 555-0102", "Don't forget the meeting (Demo)", System.currentTimeMillis() - 7200000, 1),
            SMS("+1 555-0103", "I'm on my way (Demo)", System.currentTimeMillis() - 10800000, 2),
            SMS("+1 555-0104", "Great job today! (Demo)", System.currentTimeMillis() - 14400000, 1),
            SMS("+1 555-0105", "Check this out (Demo)", System.currentTimeMillis() - 18000000, 1)
        )
    }

    fun generateMockCallLogs(): List<CallLog> {
        return listOf(
            CallLog("+1 555-0101", "John Doe (Demo)", 1, System.currentTimeMillis() - 3600000, 120),
            CallLog("+1 555-0102", "Jane Smith (Demo)", 2, System.currentTimeMillis() - 7200000, 300),
            CallLog("+1 555-0103", "Alice Johnson (Demo)", 3, System.currentTimeMillis() - 10800000, 0),
            CallLog("+1 555-0104", "Bob Brown (Demo)", 1, System.currentTimeMillis() - 14400000, 45),
            CallLog("+1 555-0105", "Charlie Davis (Demo)", 1, System.currentTimeMillis() - 18000000, 90)
        )
    }
}
