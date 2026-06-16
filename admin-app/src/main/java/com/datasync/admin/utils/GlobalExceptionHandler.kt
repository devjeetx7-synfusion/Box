package com.datasync.admin.utils

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.datasync.admin.ui.ErrorActivity
import kotlin.system.exitProcess

class GlobalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val errorDetails = Log.getStackTraceString(throwable)
            val intent = Intent(context, ErrorActivity::class.java).apply {
                putExtra("error_details", errorDetails)
                putExtra("thread_name", thread.name)
                // Screen name is tricky to get here, but we can try to get it from Crashlytics if we were tracking it
                // For now, we'll pass a placeholder or let the user know it's the global handler
                putExtra("screen_name", "Global Exception")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)

            // Terminate the current process after starting the error activity
            Process.killProcess(Process.myPid())
            exitProcess(10)
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun initialize(context: Context) {
            val handler = GlobalExceptionHandler(context)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
