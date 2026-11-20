package com.spotineil.core.data.repository

import com.spotineil.core.data.innertube.InnerTubeCatalogRepository
import com.spotineil.core.model.YouTubeVideo
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The main song catalog: search, charts and radio. */
interface YouTubeRepository {
    val isEnabled: Boolean

    /** Today's most popular songs. */
    suspend fun trending(regionCode: String = defaultRegion()): List<YouTubeVideo>

    /**
     * Song search. Results are "song-style": studio recordings are moved up, and duplicate
     * versions of the same song collapse into the best one.
     */
    suspend fun search(query: String): List<YouTubeVideo>

    /**
     * A radio queue for [seed]: songs by its artist and by [similarArtists], mixed so the same
     * artist doesn't play twice in a row. Karaoke, medleys, playlists and the like are left out.
     */
    suspend fun radio(seed: YouTubeVideo, similarArtists: List<String> = emptyList()): List<YouTubeVideo> = emptyList()

    companion object {
        fun defaultRegion(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    }
}

@Singleton
class DefaultYouTubeRepository @Inject constructor(
    private val catalog: InnerTubeCatalogRepository,
) : YouTubeRepository {

    override val isEnabled: Boolean get() = true

    override suspend fun trending(regionCode: String): List<YouTubeVideo> =
        catalog.charts().getOrThrow().songStyle(query = "", dropJunk = true).take(CHART_SIZE)

    override suspend fun search(query: String): List<YouTubeVideo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return catalog.searchSongs(q).getOrThrow().songStyle(query = q.lowercase())
    }

    override suspend fun radio(seed: YouTubeVideo, similarArtists: List<String>): List<YouTubeVideo> {
        val artists = (listOf(seed.artist) + similarArtists)
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(RADIO_ARTISTS)
        if (artists.isEmpty()) return emptyList()
        val seedKey = seed.songKey()
        val perArtist = coroutineScope {
            artists.map { artist ->
                async { catalog.searchSongs(artist).getOrDefault(emptyList()).filter { it.isBy(artist) }.take(PER_ARTIST) }
            }.map { it.await() }
        }
        return perArtist.flatten()
            .songStyle(query = "", dropJunk = true)
            .filter { it.id != seed.id && it.songKey() != seedKey }
            .mixArtists()
    }

    companion object {
        const val RADIO_ARTISTS = 5
        private const val PER_ARTIST = 8
        private const val CHART_SIZE = 40
    }
}

/** The Data API returns titles HTML-escaped ("I&#39;m"); turn them back into plain text. */
internal fun unescapeHtml(s: String): String {
    if ('&' !in s) return s
    return Regex("""&(#\d+|#x[0-9a-fA-F]+|amp|quot|apos|lt|gt|nbsp);""").replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                code?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "Song-style" ranking: prefer the official studio recording over music videos and re-uploads
// ---------------------------------------------------------------------------------------------

/** Not a song: karaoke, medleys, playlists, reactions, lessons… */
private val JUNK = Regex(
    """\b(karaoke|medley|playlist|best songs|greatest hits|full album|top \d+|reaction|reacts?|tutorial|lesson|how to play|tiny desk|concert|interview|podcast|8d audio|nightcore|slowed|sped up|reverb|1 hour|10 hours)\b""",
    RegexOption.IGNORE_CASE,
)
private val LIVE = Regex("""\blive\b|\(live|\[live""", RegexOption.IGNORE_CASE)
private val COVER = Regex("""\bcover\b""", RegexOption.IGNORE_CASE)
private val OFFICIAL_AUDIO = Regex("""official\s+(audio|song)|\(audio\)|\[audio]""", RegexOption.IGNORE_CASE)
private val LYRICS = Regex("""\blyrics?\b""", RegexOption.IGNORE_CASE)
private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

/** Same song regardless of version: "adele|someonelikeyou". */
internal fun YouTubeVideo.songKey(): String =
    artist.lowercase().replace(NON_ALNUM, "") + "|" + songTitle.lowercase().replace(NON_ALNUM, "")

/**
 * Keeps YouTube's relevance order but moves official audio up and junk down:
 *  - "Artist - Topic" tracks (studio audio + album art) jump up to 8 places;
 *  - "Official Audio" uploads and the artist's own channel get a smaller boost;
 *  - lyric re-uploads, live versions, covers and junk sink (junk is removed when [dropJunk]);
 *  - words the user actually searched for ("live", "karaoke"…) are never penalised;
 *  - each song appears once, as its best-ranked version.
 */
internal fun List<YouTubeVideo>.songStyle(query: String, dropJunk: Boolean = false): List<YouTubeVideo> {
    fun penalised(re: Regex, v: YouTubeVideo) = re.containsMatchIn(v.title) && !re.containsMatchIn(query)
    // Searched for "… karaoke" / "… live" / "… cover": that version is what's wanted.
    fun asked(v: YouTubeVideo) = listOf(JUNK, LIVE, COVER).any { it.containsMatchIn(query) && it.containsMatchIn(v.title) }
    val scored = mapIndexedNotNull { i, v ->
        val junk = penalised(JUNK, v)
        if (junk && dropJunk) return@mapIndexedNotNull null
        var score = i.toDouble()
        if (asked(v)) score -= 6
        if (v.isTopic) score -= 8
        if (OFFICIAL_AUDIO.containsMatchIn(v.title)) score -= 4
        if (!v.isTopic && v.channel.replace(" ", "").equals(v.artist.replace(" ", ""), ignoreCase = true)) score -= 2
        if (penalised(LYRICS, v)) score += 3
        if (penalised(LIVE, v)) score += 6
        if (penalised(COVER, v)) score += 6
        if (junk) score += 15
        score to v
    }
    val seen = HashSet<String>()
    return scored.sortedBy { it.first }.map { it.second }.filter { seen.add(it.songKey()) }
}

/** Round-robin by artist so a radio station doesn't play the same artist back to back. */
internal fun List<YouTubeVideo>.mixArtists(): List<YouTubeVideo> {
    val byArtist = groupBy { it.artist.lowercase() }.values.map { ArrayDeque(it) }
    val out = ArrayList<YouTubeVideo>(size)
    while (out.size < size) byArtist.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
    return out
}

/** True when [artist] is (one of) the credited artists of this song, e.g. "Cup of Joe & Janine" is by "Cup of Joe". */
fun YouTubeVideo.isBy(artist: String): Boolean {
    val wanted = artist.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "")
    if (wanted.isEmpty()) return false
    return channel.lowercase().split(Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bx\b|\band\b)\s*"""))
        .any { it.replace(Regex("""[^\p{L}\p{N}]+"""), "") == wanted }
}
