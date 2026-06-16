package com.boxx.datasync.utils

import java.security.MessageDigest

object DataUtils {
    fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
