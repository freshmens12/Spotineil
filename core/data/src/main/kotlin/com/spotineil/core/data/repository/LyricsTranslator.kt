package com.spotineil.core.data.repository

import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Lyrics in another language, line by line; [sourceLanguage] is what Google detected. */
data class TranslatedLyrics(val lines: List<String>, val sourceLanguage: String?)

/**
 * Translates lyrics with Google Translate's free web endpoint (the one translate.google.com uses).
 * The lyric lines are sent to Google; nothing else. Results are kept in memory for the session.
 */
@Singleton
class LyricsTranslator @Inject constructor() {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LinkedHashMap<String, TranslatedLyrics>()

    /** The language lyrics are translated into: the app's language (Tagalog is "tl" for Google). */
    fun targetLanguage(): String = when (val lang = Locale.getDefault().language) {
        "fil", "tl" -> "tl"
        "" -> "en"
        else -> lang
    }

    suspend fun translate(lines: List<String>, target: String = targetLanguage()): TranslatedLyrics {
        val key = target + "\u0000" + lines.joinToString("\n")
        synchronized(cache) { cache[key] }?.let { return it }
        val result = withContext(Dispatchers.IO) {
            // One request for the whole song: lines joined with newlines, split again afterwards.
            val text = lines.joinToString("\n") { it.ifBlank { "♪" } }
            val request = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${URLEncoder.encode(target, "UTF-8")}&dt=t")
                .post(FormBody.Builder().add("q", text).build())
                .build()
            val body = http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("Translate HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            parse(body, lines.size)
        }
        synchronized(cache) {
            cache[key] = result
            while (cache.size > 20) cache.remove(cache.keys.first())
        }
        return result
    }

    /** Answer: [[["translated","original",…],…], null, "detected-language", …]. */
    internal fun parse(body: String, expectedLines: Int): TranslatedLyrics {
        val root = json.parseToJsonElement(body) as? JsonArray ?: error("Unexpected answer")
        val segments = root.getOrNull(0) as? JsonArray ?: error("No translation")
        val joined = segments.joinToString("") { seg -> ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.content.orEmpty() }
        val source = (root.getOrNull(2) as? JsonPrimitive)?.content
        val lines = joined.split("\n").map { it.trim().let { l -> if (l == "♪") "" else l } }
        // Keep the line count in step with the original even if Google merged or split a line.
        val fitted = List(expectedLines) { i -> lines.getOrElse(i) { "" } }
        return TranslatedLyrics(fitted, source)
    }
}
