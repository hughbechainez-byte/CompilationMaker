package com.example.compilationmaker

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

private const val CRASH_LOG_FILE = "last-crash.log"

fun installCrashRecorder(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching { writeCrashReport(context, thread.name, throwable) }
        previous?.uncaughtException(thread, throwable)
    }
}

fun writeCrashReport(context: Context, threadName: String, throwable: Throwable) {
    val file = File(context.filesDir, CRASH_LOG_FILE)
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    file.writeText(
        buildString {
            appendLine("timestamp=" + DateFormat.getDateTimeInstance().format(Date()))
            appendLine("thread=$threadName")
            appendLine("message=" + (throwable.message ?: ""))
            appendLine()
            append(writer.toString())
        }
    )
}

fun readCrashReport(context: Context): String? {
    val file = File(context.filesDir, CRASH_LOG_FILE)
    return if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
}

fun clearCrashReport(context: Context) {
    File(context.filesDir, CRASH_LOG_FILE).delete()
}

