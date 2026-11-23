package com.spotineil.app

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports the person controls: when the app crashes, the error is saved on the phone only.
 * Next time the app opens it asks whether to send it; "Send" opens a GitHub issue already filled
 * in. Nothing is sent by itself, and the report holds no personal data (only the app version,
 * the Android version, the phone model and the error).
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"
    private const val ISSUES_URL = "https://github.com/freshmens12/Spotineil/issues/new"
    private const val MAX_TRACE_LINES = 60

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE_NAME).writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report from the last crash, or null. */
    fun pending(context: Context): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    /** A GitHub "new issue" link with the title and the report filled in. */
    fun issueUrl(report: String): String {
        val firstError = report.lineSequence().firstOrNull { it.startsWith("Error:") }?.removePrefix("Error:")?.trim().orEmpty()
        val title = "Crash: " + firstError.take(80)
        val body = "**What were you doing when it crashed?**\n\n\n\n**Crash report**\n```\n" + report.take(6_000) + "\n```"
        return Uri.parse(ISSUES_URL).buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .build()
            .toString()
    }

    private fun report(thread: Thread, error: Throwable): String {
        val trace = error.stackTraceToString().lineSequence().take(MAX_TRACE_LINES).joinToString("\n")
        val root = generateSequence(error) { it.cause }.last()
        return buildString {
            appendLine("App: Spotineil ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
            appendLine("Thread: ${thread.name}")
            appendLine("Error: ${root.javaClass.simpleName}: ${root.message.orEmpty().take(200)}")
            appendLine()
            append(trace)
        }
    }
}
