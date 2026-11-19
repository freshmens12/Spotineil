package com.spotineil.core.data.repository

import com.spotineil.core.common.FreeSourcesConfig
import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.network.api.YouTubeApi
import com.spotineil.core.network.api.YtVideoDto
import com.spotineil.core.network.apiCall
import java.time.Duration
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Today's YouTube quota use as Spotineil counts it (Google resets it at midnight Pacific time). */
@Serializable
data class YouTubeQuota(val day: String, val usedUnits: Int, val budget: Int, val exhausted: Boolean = false) {
    val remaining: Int get() = (budget - usedUnits).coerceAtLeast(0)
}

interface YouTubeRepository {
    val isEnabled: Boolean
    val quota: StateFlow<YouTubeQuota?>

    /** Most popular music videos in this country (1 quota unit, cached for hours). */
    suspend fun trending(regionCode: String = defaultRegion()): List<YouTubeVideo>

    /**
     * Music-only search (≈101 quota units, cached for a day). Results are "song-style": official
     * "Artist - Topic" audio tracks are moved up, and duplicate versions of the same song collapse
     * into the best one.
     */
    suspend fun search(query: String): List<YouTubeVideo>

    /**
     * A radio queue for [seed]: songs by its artist and by [similarArtists], mixed so the same
     * artist doesn't play twice in a row. One search (≈101 units, cached for a day). Karaoke,
     * medleys, playlists, reactions and the like are left out.
     */
    suspend fun radio(seed: YouTubeVideo, similarArtists: List<String> = emptyList()): List<YouTubeVideo> = emptyList()

    companion object {
        fun defaultRegion(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    }
}

/**
 * YouTube Data API access behind a small "gateway", so heavy use doesn't lag or burn the key's
 * daily quota:
 *  - **Cache**: memory + Room, with stale results reused when quota is low or the network fails.
 *  - **Request coalescing**: identical requests in flight share one network call.
 *  - **Concurrency cap**: at most [MAX_PARALLEL] calls at once.
 *  - **Quota budget**: units are counted per Pacific-time day; expensive searches stop before the
 *    budget runs out, and a 403 "quota exceeded" marks the day as exhausted.
 *
 * It does not rotate keys or spread load over several projects — Google's terms forbid
 * circumventing quota that way.
 */
@Singleton
class DefaultYouTubeRepository @Inject constructor(
    private val api: YouTubeApi,
    private val cache: JsonCache,
    private val config: FreeSourcesConfig,
) : YouTubeRepository {

    override val isEnabled: Boolean get() = config.isYouTubeEnabled

    private val _quota = MutableStateFlow<YouTubeQuota?>(null)
    override val quota: StateFlow<YouTubeQuota?> = _quota.asStateFlow()

    private val memory = object : LinkedHashMap<String, Pair<Long, List<YouTubeVideo>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<YouTubeVideo>>>?) = size > 40
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<YouTubeVideo>>>()
    private val gate = Semaphore(MAX_PARALLEL)
    private val quotaLock = Mutex()
    internal var now: () -> Long = System::currentTimeMillis

    override suspend fun trending(regionCode: String): List<YouTubeVideo> =
        cached("yt:chart:${regionCode.uppercase()}", TRENDING_TTL_MS, cost = VIDEOS_COST) {
            val dto = try {
                apiCall { api.videos(key = config.youTubeApiKey, chart = "mostPopular", category = MUSIC_CATEGORY, region = regionCode) }
            } catch (e: SpotineilException.NotFound) {
                // Some regions have no music chart: fall back to the US one.
                apiCall { api.videos(key = config.youTubeApiKey, chart = "mostPopular", category = MUSIC_CATEGORY, region = "US") }
            }
            dto.items.mapNotNull { it.toSong() }
        }.unescaped()

    override suspend fun search(query: String): List<YouTubeVideo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return rawSearch(q).songStyle(query = q)
    }

    override suspend fun radio(seed: YouTubeVideo, similarArtists: List<String>): List<YouTubeVideo> {
        val artists = (listOf(seed.artist) + similarArtists)
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(RADIO_ARTISTS)
        if (artists.isEmpty()) return emptyList()
        // YouTube search supports OR ("|"), so one search covers every artist of the station.
        val q = artists.joinToString("|") { "\"$it\"" }.lowercase()
        val seedKey = seed.songKey()
        return rawSearch(q)
            .songStyle(query = "", dropJunk = true)
            .filter { it.id != seed.id && it.songKey() != seedKey }
            .mixArtists()
    }

    private suspend fun rawSearch(q: String): List<YouTubeVideo> =
        cached("yt:search:$q", SEARCH_TTL_MS, cost = SEARCH_COST + VIDEOS_COST) {
            val ids = apiCall { api.search(query = q, key = config.youTubeApiKey, region = YouTubeRepository.defaultRegion()) }
                .items.mapNotNull { it.id.videoId }.distinct()
            if (ids.isEmpty()) return@cached emptyList()
            // One cheap call gets durations + embeddable status for all results, keeping search order.
            val byId = apiCall { api.videos(key = config.youTubeApiKey, ids = ids.joinToString(",")) }.items.associateBy { it.id }
            ids.mapNotNull { byId[it]?.toSong() }
        }.unescaped()

    /** Lists cached by older versions still have HTML-escaped titles. */
    private fun List<YouTubeVideo>.unescaped() = map { it.copy(title = unescapeHtml(it.title), channel = unescapeHtml(it.channel)) }

    /** Cache → coalesce → quota check → rate-limited network call → cache. */
    private suspend fun cached(key: String, ttlMs: Long, cost: Int, load: suspend () -> List<YouTubeVideo>): List<YouTubeVideo> {
        if (!isEnabled) return emptyList()
        synchronized(memory) { memory[key] }?.let { (at, value) -> if (now() - at < ttlMs) return value }
        cache.get(key, LIST, maxAgeMs = ttlMs)?.let { fresh -> remember(key, fresh); return fresh }

        val mine = CompletableDeferred<List<YouTubeVideo>>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()

        try {
            val result = try {
                if (!reserve(cost)) throw SpotineilException.QuotaExceeded()
                gate.withPermit { load() }
            } catch (e: SpotineilException) {
                if (e is SpotineilException.Forbidden) markExhausted()
                // Better an old list than nothing (quota out, offline, YouTube error).
                val stale = cache.get(key, LIST)
                if (stale != null) stale else throw if (e is SpotineilException.Forbidden) SpotineilException.QuotaExceeded() else e
            }
            remember(key, result)
            cache.put(key, LIST, result)
            mine.complete(result)
            return result
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun remember(key: String, value: List<YouTubeVideo>) {
        synchronized(memory) { memory[key] = now() to value }
    }

    /** Reserves [cost] units from today's budget; false when it would go over. */
    private suspend fun reserve(cost: Int): Boolean = quotaLock.withLock {
        val today = pacificDay()
        val current = (_quota.value ?: cache.get(QUOTA_KEY, YouTubeQuota.serializer()))
            ?.takeIf { it.day == today }
            ?: YouTubeQuota(today, 0, config.youTubeDailyBudget)
        if (current.exhausted || current.usedUnits + cost > current.budget) {
            _quota.value = current
            return@withLock false
        }
        val next = current.copy(usedUnits = current.usedUnits + cost)
        _quota.value = next
        cache.put(QUOTA_KEY, YouTubeQuota.serializer(), next)
        true
    }

    private suspend fun markExhausted() = quotaLock.withLock {
        val today = pacificDay()
        val next = (_quota.value?.takeIf { it.day == today } ?: YouTubeQuota(today, 0, config.youTubeDailyBudget)).copy(exhausted = true)
        _quota.value = next
        cache.put(QUOTA_KEY, YouTubeQuota.serializer(), next)
    }

    private fun pacificDay(): String =
        java.time.Instant.ofEpochMilli(now()).atZone(ZoneId.of("America/Los_Angeles")).toLocalDate().toString()

    companion object {
        const val MUSIC_CATEGORY = "10"
        const val SEARCH_COST = 100
        const val VIDEOS_COST = 1
        const val MAX_PARALLEL = 2
        const val RADIO_ARTISTS = 5
        const val TRENDING_TTL_MS = 6 * 60 * 60 * 1000L
        const val SEARCH_TTL_MS = 24 * 60 * 60 * 1000L
        private const val QUOTA_KEY = "yt:quota"
        private val LIST = ListSerializer(YouTubeVideo.serializer())

        /** Full songs only: no Shorts/teasers, no hour-long mixes or livestreams. */
        val SONG_LENGTH_MS = 60_000L..15 * 60_000L

        fun parseIsoDuration(raw: String?): Long =
            raw?.let { runCatching { Duration.parse(it).toMillis() }.getOrNull() } ?: 0L
    }
}

/** Keeps only playable, embeddable, full-length music videos. */
internal fun YtVideoDto.toSong(): YouTubeVideo? {
    val duration = DefaultYouTubeRepository.parseIsoDuration(contentDetails.duration)
    if (!status.embeddable) return null
    if (status.privacyStatus != null && status.privacyStatus != "public" && status.privacyStatus != "unlisted") return null
    if (snippet.liveBroadcastContent != null && snippet.liveBroadcastContent != "none") return null
    if (duration !in DefaultYouTubeRepository.SONG_LENGTH_MS) return null
    val t = snippet.thumbnails
    return YouTubeVideo(
        id = id,
        title = unescapeHtml(snippet.title),
        channel = unescapeHtml(snippet.channelTitle),
        thumbnailUrl = (t.high ?: t.medium ?: t.standard ?: t.default)?.url,
        durationMs = duration,
    )
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
