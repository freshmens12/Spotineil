package com.spotineil.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.spotineil.app.BuildConfig
import com.spotineil.app.CrashReporter
import com.spotineil.app.R
import com.spotineil.core.designsystem.theme.dialogContainer
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "Spotineil stopped last time": shown once after a crash. Returns true while it is showing, so
 * other launch dialogs wait.
 */
@Composable
fun CrashReportDialog(): Boolean {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.pending(context)) }
    val text = report ?: return false
    val close = {
        CrashReporter.clear(context)
        report = null
    }
    AlertDialog(
        containerColor = dialogContainer(),
        onDismissRequest = close,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.crash_message))
                Text(
                    text.lineSequence().take(8).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUrl(context, CrashReporter.issueUrl(text))
                close()
            }) { Text(stringResource(R.string.crash_send)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    copy(context, text)
                    close()
                }) { Text(stringResource(R.string.crash_copy)) }
                TextButton(onClick = close) { Text(stringResource(R.string.crash_not_now)) }
            }
        },
    )
    return true
}

/** After an update: what changed in this version (its release notes from GitHub). */
@Composable
fun WhatsNewDialog(enabled: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("whats_new", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val seen = prefs.getInt(KEY_SEEN, 0)
        val current = BuildConfig.VERSION_CODE
        when {
            seen == 0 -> prefs.edit().putInt(KEY_SEEN, current).apply() // fresh install: nothing to announce
            seen < current -> {
                notes = releaseNotes(BuildConfig.VERSION_NAME)
                show = true
            }
        }
    }
    if (!show || !enabled) return
    val dismiss = {
        prefs.edit().putInt(KEY_SEEN, BuildConfig.VERSION_CODE).apply()
        show = false
    }
    AlertDialog(
        containerColor = dialogContainer(),
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val body = notes
                if (body.isNullOrBlank()) Text(stringResource(R.string.whats_new_fallback)) else Text(formatNotes(body))
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.whats_new_ok)) } },
        dismissButton = {
            TextButton(onClick = {
                openUrl(context, "https://github.com/freshmens12/Spotineil/releases/tag/v${BuildConfig.VERSION_NAME}")
                dismiss()
            }) { Text(stringResource(R.string.whats_new_more)) }
        },
    )
}

private const val KEY_SEEN = "seen_version_code"

/** The release notes of [version] from GitHub, or null when offline. */
private suspend fun releaseNotes(version: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("https://api.github.com/repos/freshmens12/Spotineil/releases/tags/v$version").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val text = c.inputStream.use { it.readBytes().decodeToString() }
        (Json.parseToJsonElement(text) as? JsonObject)?.get("body")?.let { (it as? JsonPrimitive)?.content }
    }.getOrNull()
}

/** Changelog Markdown → readable text: "### Added" as bold headings, "**x**" as bold, "- " as bullets. */
private fun formatNotes(markdown: String) = buildAnnotatedString {
    markdown.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEachIndexed { i, line ->
        if (i > 0) append("\n")
        when {
            line.startsWith("#") -> {
                if (i > 0) append("\n")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.trimStart('#', ' ')) }
            }
            else -> {
                val text = if (line.startsWith("- ")) "•  " + line.removePrefix("- ") else line
                // **bold** parts
                text.split("**").forEachIndexed { j, part ->
                    if (j % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Spotineil crash report", text))
}
