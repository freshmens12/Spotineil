package com.spotineil.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.spotineil.app.BuildConfig
import com.spotineil.core.network.di.BaseClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A newer version of the app that can be downloaded. */
data class AppUpdate(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Finds, downloads and hands over new versions of the app (the user always has to agree). */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseClient base: OkHttpClient,
) {
    /** No shared HTTP cache: the APK is large and must always be fetched fresh. */
    private val client: OkHttpClient = base.newBuilder().cache(null).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()

    private val dir: File get() = File(context.cacheDir, "updates")

    /** The newest published version when it is newer than the installed one, otherwise null. */
    suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.UPDATE_URL).header("Accept", "application/vnd.github+json").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update check failed: HTTP ${response.code}")
            response.body.string()
        }
        val update = parseRelease(body)
        if (update == null || !isNewerVersion(update.versionName, BuildConfig.VERSION_NAME)) {
            dir.deleteRecursively() // leftovers of an update that was already installed
            null
        } else {
            update
        }
    }

    /** Downloads the APK into the app's cache; [onProgress] gets 0f..1f (or -1f when the size is unknown). */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val target = File(dir, "Spotineil-${update.versionName}.apk")
        val partial = File(dir, "download.part")
        try {
            client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                var done = 0L
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            ensureActive()
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
                if (update.sizeBytes > 0 && done != update.sizeBytes) throw IOException("Download incomplete")
            }
            if (!partial.renameTo(target)) throw IOException("Could not save the update")
            target
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    /** Android only lets an app install other APKs after the user allows it in Settings. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the Settings page where the user allows Spotineil to install updates. */
    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android's own installer screen for [apk]; it asks the user once more before installing. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** Reads a GitHub "latest release" answer; null when it has no APK. */
internal fun parseRelease(text: String): AppUpdate? {
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val tag = (root["tag_name"] as? JsonPrimitive)?.content ?: return null
    val asset = (root["assets"] as? JsonArray)?.filterIsInstance<JsonObject>()
        ?.firstOrNull { (it["name"] as? JsonPrimitive)?.content?.endsWith(".apk", ignoreCase = true) == true }
        ?: return null
    val url = (asset["browser_download_url"] as? JsonPrimitive)?.content ?: return null
    return AppUpdate(
        versionName = tag.removePrefix("v").removePrefix("V"),
        notes = (root["body"] as? JsonPrimitive)?.content.orEmpty().trim(),
        apkUrl = url,
        sizeBytes = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
    )
}

/** "1.10.0" is newer than "1.9.2"; suffixes such as "-debug" are ignored. */
internal fun isNewerVersion(remote: String, installed: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(remote)
    val b = parts(installed)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
