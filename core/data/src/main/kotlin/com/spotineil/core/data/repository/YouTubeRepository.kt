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

    /** Music-only search (≈101 quota units, cached for a day). */
    suspend fun search(query: String): List<YouTubeVideo>

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
        }

    override suspend fun search(query: String): List<YouTubeVideo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return cached("yt:search:$q", SEARCH_TTL_MS, cost = SEARCH_COST + VIDEOS_COST) {
            val ids = apiCall { api.search(query = q, key = config.youTubeApiKey, region = YouTubeRepository.defaultRegion()) }
                .items.mapNotNull { it.id.videoId }.distinct()
            if (ids.isEmpty()) return@cached emptyList()
            // One cheap call gets durations + embeddable status for all results, keeping search order.
            val byId = apiCall { api.videos(key = config.youTubeApiKey, ids = ids.joinToString(",")) }.items.associateBy { it.id }
            ids.mapNotNull { byId[it]?.toSong() }
        }
    }

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
        title = snippet.title,
        channel = snippet.channelTitle,
        thumbnailUrl = (t.high ?: t.medium ?: t.standard ?: t.default)?.url,
        durationMs = duration,
    )
}
