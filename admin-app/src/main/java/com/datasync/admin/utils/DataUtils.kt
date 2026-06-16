package com.datasync.admin.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

object DataUtils {
    fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun formatDate(timestamp: Long, relative: Boolean = true): String {
        if (timestamp == 0L) return "N/A"
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        if (relative && diff < 24 * 60 * 60 * 1000 && diff >= 0) {
            return when {
                diff < 60 * 1000 -> "Just now"
                diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} min ago"
                else -> "${diff / (3600 * 1000)} hours ago"
            }
        }

        val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun extractOtp(body: String): String? {
        val otpRegex = Regex("\\b\\d{4,8}\\b")
        return otpRegex.find(body)?.value
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Data", text)
        clipboard.setPrimaryClip(clip)
    }
}
