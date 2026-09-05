package com.example.audiobook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudiobookApp : Application() {

    companion object {
        private const val CRASH_FILE = "crash_log.txt"

        fun getCrashLog(context: Context): String {
            val file = File(context.cacheDir, CRASH_FILE)
            return if (file.exists()) file.readText() else "无 crash log"
        }

        fun clearCrashLog(context: Context) {
            val file = File(context.cacheDir, CRASH_FILE)
            if (file.exists()) file.delete()
        }

        fun hasCrashLog(context: Context): Boolean {
            val file = File(context.cacheDir, CRASH_FILE)
            return file.exists() && file.length() > 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val crashInfo = buildString {
                    appendLine("===== CRASH LOG =====")
                    appendLine("Time: $timestamp")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                    appendLine()
                    appendLine("Stack Trace:")
                    appendLine(stackTrace)
                    appendLine()
                    appendLine("--- PdfParser Internal Log ---")
                    appendLine(PdfParser.getLogs())
                    appendLine("===== END CRASH LOG =====")
                    appendLine()
                }

                // Write to cacheDir (always accessible to the app)
                val file = File(cacheDir, CRASH_FILE)
                file.appendText(crashInfo)
            } catch (_: Exception) {
                // Don't crash in the crash handler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
