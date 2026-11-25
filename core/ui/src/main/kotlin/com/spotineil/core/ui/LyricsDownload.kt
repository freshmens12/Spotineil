package com.spotineil.core.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.spotineil.core.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns a function that saves a song’s lyrics to Downloads/Spotineil (see [lyricsFile]) and
 * says where they went, in a short message.
 */
@Composable
fun rememberLyricsSaver(): (lyrics: Lyrics, title: String, artist: String) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { lyrics, title, artist ->
            scope.launch {
                val saved = saveLyrics(context, lyrics, title, artist)
                val message = if (saved != null) context.getString(R.string.lyrics_saved, saved) else context.getString(R.string.lyrics_save_failed)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** A lyrics file ready to save: its name and text. */
internal data class LyricsFile(val name: String, val text: String)

/**
 * Synced lyrics become an .lrc file ("[mm:ss.xx] line", which music players show in time with the
 * song and any text app can open); plain lyrics become a .txt file with the song and artist on top.
 * Named "Artist - Title" so players that look for lyrics next to a song can find it.
 */
internal fun lyricsFile(lyrics: Lyrics, title: String, artist: String): LyricsFile {
    val base = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "Lyrics" }
    return if (lyrics.isSynced) {
        val text = buildString {
            if (title.isNotBlank()) append("[ti:").append(title).append("]\n")
            if (artist.isNotBlank()) append("[ar:").append(artist).append("]\n")
            append("[re:Spotineil]\n")
            for (line in lyrics.lines) append(lrcTime(line.timeMs)).append(line.text).append('\n')
        }
        LyricsFile("$base.lrc", text)
    } else {
        val text = buildString {
            if (title.isNotBlank()) append(title).append('\n')
            if (artist.isNotBlank()) append(artist).append('\n')
            if (title.isNotBlank() || artist.isNotBlank()) append('\n')
            for (line in lyrics.lines) append(line.text).append('\n')
        }
        LyricsFile("$base.txt", text)
    }
}

private fun lrcTime(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    val minutes = t / 60_000
    val seconds = t / 1000 % 60
    val hundredths = t % 1000 / 10
    return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
}

/**
 * Saves the lyrics to Downloads/Spotineil (no permission needed). Returns the file name it got
 * (Android adds " (1)" when one with that name is already there), or null when saving failed.
 */
internal suspend fun saveLyrics(context: Context, lyrics: Lyrics, title: String, artist: String): String? =
    withContext(Dispatchers.IO) {
        val file = lyricsFile(lyrics, title, artist)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            // .lrc has no MIME type of its own; octet-stream keeps Android from adding ".txt".
            put(MediaStore.Downloads.MIME_TYPE, if (file.name.endsWith(".lrc")) "application/octet-stream" else "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Spotineil")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return@withContext null
        runCatching {
            resolver.openOutputStream(uri)!!.use { it.write(file.text.toByteArray(Charsets.UTF_8)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: file.name
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
