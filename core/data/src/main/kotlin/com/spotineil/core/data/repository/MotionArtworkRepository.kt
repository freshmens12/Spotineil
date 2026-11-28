package com.spotineil.core.data.repository

import com.spotineil.core.common.AppDispatcher
import com.spotineil.core.common.Dispatcher
import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.network.di.BaseClient
import java.net.URLEncoder
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Moving album covers ("motion artwork"), for the Now playing screen. */
interface MotionArtworkRepository {
    /** A looping video of the album cover of this song (an HLS link), or null when its album has none. */
    suspend fun find(title: String, artist: String, album: String? = null): String?
}

/**
 * Finds Apple Music's motion artwork: Apple's public iTunes search finds the song's album, and the
 * album's page on music.apple.com names the square cover video when there is one. Only the song
 * and artist names are sent. Answers are kept for a week, so each album is looked up once.
 */
@Singleton
class AppleMotionArtworkRepository @Inject constructor(
    @BaseClient private val client: OkHttpClient,
    private val cache: JsonCache,
    private val json: Json,
    @Dispatcher(AppDispatcher.IO) private val io: CoroutineDispatcher,
) : MotionArtworkRepository {

    @Serializable
    private data class Found(val url: String? = null)

    override suspend fun find(title: String, artist: String, album: String?): String? = withContext(io) {
        val key = "motionart:${normalize(firstArtist(artist))}|${normalize(cleanTitle(title))}"
        cache.get(key, Found.serializer(), CACHE_MS)?.let { return@withContext it.url }
        val url = try {
            lookup(title, artist, album)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext null // offline or Apple's page changed: try again another time
        }
        cache.put(key, Found.serializer(), Found(url))
        url
    }

    private fun lookup(title: String, artist: String, album: String?): String? {
        val term = URLEncoder.encode("${firstArtist(artist)} ${cleanTitle(title)}", "UTF-8")
        val search = get("https://itunes.apple.com/search?term=$term&entity=song&limit=15&country=US") ?: return null
        val albums = matchingAlbums(json.parseToJsonElement(search) as? JsonObject ?: return null, title, artist, album)
        for (page in albums.take(MAX_PAGES)) {
            val html = get(page) ?: continue
            squareMotionVideo(html)?.let { return it }
        }
        return null
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    internal companion object {
        private const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_PAGES = 3

        // Apple's pages leave the artwork out for unknown browsers.
        private const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        /** The album pages (best first) whose song matches [title] by [artist] in an iTunes search answer. */
        fun matchingAlbums(answer: JsonObject, title: String, artist: String, album: String?): List<String> {
            val wantTitle = normalize(cleanTitle(title))
            val wantArtist = normalize(firstArtist(artist))
            val wantAlbum = album?.let { normalize(cleanTitle(it)) }?.takeIf { it.isNotEmpty() }
            data class Hit(val url: String, val collection: Long, val albumMatch: Boolean, val single: Boolean)
            val hits = (answer["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                val track = normalize(cleanTitle(s("trackName") ?: return@mapNotNull null))
                val by = normalize(s("artistName") ?: return@mapNotNull null)
                val url = s("collectionViewUrl")?.substringBefore('?') ?: return@mapNotNull null
                val collection = (o["collectionId"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val titleOk = track == wantTitle || (wantTitle.length >= 4 && (track.startsWith(wantTitle) || wantTitle.startsWith(track)))
                val artistOk = wantArtist.isNotEmpty() && (by.contains(wantArtist) || wantArtist.contains(by))
                if (!titleOk || !artistOk) return@mapNotNull null
                val name = normalize(cleanTitle(s("collectionName").orEmpty()))
                Hit(url, collection, wantAlbum != null && name == wantAlbum, s("collectionName").orEmpty().endsWith("- Single"))
            }
            // The named album first, then albums before singles (motion artwork is mostly on albums).
            return hits.distinctBy { it.collection }
                .sortedWith(compareByDescending<Hit> { it.albumMatch }.thenBy { it.single })
                .map { it.url }
        }

        /** The square motion artwork video named on an Apple Music album page, or null. */
        fun squareMotionVideo(html: String): String? {
            val text = html.replace("\\/", "/").replace("\\u002F", "/")
            val at = text.indexOf("\"motionDetailSquare\"")
            if (at >= 0) {
                Regex("\"video\"\\s*:\\s*\"(https://[^\"]+?\\.m3u8)\"").find(text.substring(at, minOf(text.length, at + 6000)))
                    ?.let { return it.groupValues[1] }
            }
            return Regex("editorial-video--cover\"[^>]*?src=\"(https://[^\"]+?\\.m3u8)\"").find(text)?.groupValues?.get(1)
        }

        /** "Taylor Swift" from "Taylor Swift, Ed Sheeran" or "Taylor Swift feat. Ed Sheeran". */
        fun firstArtist(artist: String): String =
            artist.split(Regex("\\s*(,|&| x | feat\\.? | ft\\.? | featuring | with )\\s*", RegexOption.IGNORE_CASE)).first().trim()

        /** A title without what YouTube adds: "(Official Video)", "[Lyrics]", "- Remastered 2011", "(feat. …)". */
        fun cleanTitle(title: String): String =
            title.replace(Regex("\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visualizer|feat|ft\\.|remaster|mv)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+-\\s+(\\d{4}\\s+)?remaster(ed)?.*$", RegexOption.IGNORE_CASE), "")
                .trim()

        /** Lower case, no accents, letters and digits only. */
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace("&", "and")
                .replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }
}
