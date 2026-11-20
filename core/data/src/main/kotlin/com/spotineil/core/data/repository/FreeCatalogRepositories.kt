package com.spotineil.core.data.repository

import com.spotineil.core.common.FreeSourcesConfig
import com.spotineil.core.data.db.FollowedPodcastDao
import com.spotineil.core.data.db.FollowedPodcastEntity
import com.spotineil.core.data.db.LikedMediaDao
import com.spotineil.core.data.db.LikedMediaEntity
import com.spotineil.core.data.db.RecentItemDao
import com.spotineil.core.data.db.RecentItemEntity
import com.spotineil.core.data.podcast.PodcastFeedParser
import com.spotineil.core.model.ArchiveCollection
import com.spotineil.core.model.ArchiveCollectionDetail
import com.spotineil.core.model.CollectionKind
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.Podcast
import com.spotineil.core.model.PodcastDetail
import com.spotineil.core.model.RecentItem
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.StreamSource
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.network.api.ApplePodcastDto
import com.spotineil.core.network.api.ApplePodcastsApi
import com.spotineil.core.network.api.ArchiveApi
import com.spotineil.core.network.api.ArchiveDocDto
import com.spotineil.core.network.api.ArchiveFileDto
import com.spotineil.core.network.api.CcMixterApi
import com.spotineil.core.network.api.CcMixterUploadDto
import com.spotineil.core.network.api.LastFmApi
import com.spotineil.core.network.api.archiveText
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.di.BaseClient
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

// ---------------------------------------------------------------------------------------------
// Liked songs (no account needed)
// ---------------------------------------------------------------------------------------------

interface LikedMediaRepository {
    val liked: Flow<List<PlayableMedia>>
    fun isLiked(mediaId: String): Flow<Boolean>
    suspend fun setLiked(media: PlayableMedia, liked: Boolean)
}

@Singleton
class DefaultLikedMediaRepository @Inject constructor(
    private val dao: LikedMediaDao,
    private val json: Json,
) : LikedMediaRepository {
    override val liked: Flow<List<PlayableMedia>> = dao.observe().map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(PlayableMedia.serializer(), it.json) }.getOrNull() }
    }

    override fun isLiked(mediaId: String): Flow<Boolean> = dao.observeIsLiked(mediaId)

    override suspend fun setLiked(media: PlayableMedia, liked: Boolean) {
        if (liked) {
            dao.upsert(LikedMediaEntity(media.mediaId, json.encodeToString(PlayableMedia.serializer(), media), System.currentTimeMillis()))
        } else {
            dao.delete(media.mediaId)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "Jump back in" history
// ---------------------------------------------------------------------------------------------

interface RecentsRepository {
    fun recent(limit: Int = 20): Flow<List<RecentItem>>
    suspend fun record(item: RecentItem)
    suspend fun clear()
}

@Singleton
class DefaultRecentsRepository @Inject constructor(
    private val dao: RecentItemDao,
    private val json: Json,
) : RecentsRepository {
    override fun recent(limit: Int): Flow<List<RecentItem>> = dao.observe(limit).map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(RecentItem.serializer(), it.json) }.getOrNull() }
    }

    override suspend fun record(item: RecentItem) {
        dao.upsert(RecentItemEntity(item.key, json.encodeToString(RecentItem.serializer(), item), System.currentTimeMillis()))
        dao.trim(KEEP)
    }

    override suspend fun clear() = dao.clear()

    private companion object { const val KEEP = 500 }
}

// ---------------------------------------------------------------------------------------------
// Podcasts: Apple Podcasts directory for discovery, the show's own RSS feed for episodes
// ---------------------------------------------------------------------------------------------

interface PodcastRepository {
    val followed: Flow<List<Podcast>>
    fun isFollowed(feedUrl: String): Flow<Boolean>
    suspend fun setFollowed(podcast: Podcast, followed: Boolean)
    suspend fun top(limit: Int = 25): List<Podcast>
    suspend fun search(query: String): List<Podcast>
    suspend fun detail(podcast: Podcast): PodcastDetail
}

@Singleton
class DefaultPodcastRepository @Inject constructor(
    private val api: ApplePodcastsApi,
    @BaseClient private val client: OkHttpClient,
    private val dao: FollowedPodcastDao,
    private val json: Json,
) : PodcastRepository {

    private val country: String
        get() = Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"

    override val followed: Flow<List<Podcast>> = dao.observe().map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(Podcast.serializer(), it.json) }.getOrNull() }
    }

    override fun isFollowed(feedUrl: String): Flow<Boolean> = dao.observeIsFollowed(feedUrl)

    override suspend fun setFollowed(podcast: Podcast, followed: Boolean) {
        if (followed) {
            dao.upsert(FollowedPodcastEntity(podcast.feedUrl, json.encodeToString(Podcast.serializer(), podcast), System.currentTimeMillis()))
        } else {
            dao.delete(podcast.feedUrl)
        }
    }

    /** Apple's top-shows chart for this country, then a lookup for the shows' public feed URLs. */
    override suspend fun top(limit: Int): List<Podcast> {
        val chart = apiCall { api.top("https://rss.marketingtools.apple.com/api/v2/$country/podcasts/top/$limit/podcasts.json") }
            .feed.results
        if (chart.isEmpty()) return emptyList()
        val feeds = apiCall { api.lookup(chart.joinToString(",") { it.id }) }.results
            .associateBy { it.collectionId?.toString() }
        return chart.mapNotNull { entry ->
            val found = feeds[entry.id] ?: return@mapNotNull null
            found.toModel(fallbackArtwork = entry.artworkUrl100?.let(::bigArtwork))
        }
    }

    override suspend fun search(query: String): List<Podcast> =
        apiCall { api.search(query, country) }.results.mapNotNull { it.toModel() }

    override suspend fun detail(podcast: Podcast): PodcastDetail = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(podcast.feedUrl).header("Accept", "application/rss+xml, application/xml, text/xml").build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) throw SpotineilException.NotFound()
                if (!response.isSuccessful) throw SpotineilException.Server(response.code)
                val body = response.body ?: throw SpotineilException.Unknown()
                PodcastFeedParser.parse(body.byteStream(), podcast)
            }
        } catch (e: IOException) {
            throw SpotineilException.Network(e)
        } catch (e: SpotineilException) {
            throw e
        } catch (e: Exception) {
            // Malformed XML.
            throw SpotineilException.Unknown(e)
        }
    }

    private fun ApplePodcastDto.toModel(fallbackArtwork: String? = null): Podcast? {
        val feed = feedUrl?.takeIf { it.startsWith("http") } ?: return null
        return Podcast(
            feedUrl = feed,
            title = collectionName.orEmpty(),
            author = artistName.orEmpty(),
            artworkUrl = artworkUrl600 ?: artworkUrl100?.let(::bigArtwork) ?: fallbackArtwork,
        )
    }

    /** Apple image URLs encode their size; ask for a sharper one. */
    private fun bigArtwork(url: String) = url.replace(Regex("/\\d+x\\d+(bb)?\\.(jpg|png|webp)$"), "/600x600bb.jpg")
}

// ---------------------------------------------------------------------------------------------
// Internet Archive: Live Music Archive (taper-friendly bands) + netlabel albums
// ---------------------------------------------------------------------------------------------

interface ArchiveRepository {
    suspend fun concerts(page: Int = 1): List<ArchiveCollection>
    suspend fun albums(page: Int = 1): List<ArchiveCollection>
    suspend fun search(query: String): List<ArchiveCollection>
    suspend fun detail(collection: ArchiveCollection): ArchiveCollectionDetail
}

@Singleton
class DefaultArchiveRepository @Inject constructor(private val api: ArchiveApi) : ArchiveRepository {

    override suspend fun concerts(page: Int) = find("collection:etree AND mediatype:etree", CollectionKind.Concert, page)

    override suspend fun albums(page: Int) = find("collection:netlabels AND mediatype:audio", CollectionKind.Album, page)

    override suspend fun search(query: String): List<ArchiveCollection> = coroutineScope {
        val q = query.replace(Regex("[\\\\\"():\\[\\]{}^~*?!+&|-]"), " ").trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val concerts = async { runCatching { find("collection:etree AND mediatype:etree AND ($q)", CollectionKind.Concert, rows = 15) } }
        val albums = async { runCatching { find("collection:netlabels AND mediatype:audio AND ($q)", CollectionKind.Album, rows = 15) } }
        val c = concerts.await()
        val a = albums.await()
        if (c.isFailure && a.isFailure) throw c.exceptionOrNull()!!
        c.getOrDefault(emptyList()) + a.getOrDefault(emptyList())
    }

    override suspend fun detail(collection: ArchiveCollection): ArchiveCollectionDetail {
        val dto = apiCall { api.metadata(collection.id) }
        val meta = dto.metadata
        val resolved = collection.copy(
            title = meta.title.archiveText() ?: collection.title,
            creator = meta.creator.archiveText() ?: collection.creator,
            date = meta.date.archiveText()?.take(10) ?: collection.date,
        )
        val tracks = dto.files
            .filter { it.isPlayableMp3() }
            .sortedWith(compareBy<ArchiveFileDto> { it.trackNumber() ?: Int.MAX_VALUE }.thenBy { it.name })
            .mapIndexed { index, file -> file.toTrack(resolved, index) }
        return ArchiveCollectionDetail(
            collection = resolved,
            description = meta.description.archiveText()?.let(PodcastFeedParser::stripHtml)?.take(800),
            tracks = tracks,
        )
    }

    private suspend fun find(query: String, kind: CollectionKind, page: Int = 1, rows: Int = 30): List<ArchiveCollection> =
        apiCall { api.search(query, rows = rows, page = page) }.response.docs.map { it.toModel(kind) }

    private fun ArchiveDocDto.toModel(kind: CollectionKind) = ArchiveCollection(
        id = identifier,
        title = title.archiveText() ?: identifier,
        creator = creator.archiveText() ?: "Unknown artist",
        date = date.archiveText()?.take(10),
        kind = kind,
    )

    /** Concerts ship lossless + derived MP3s; netlabels ship originals. Either way: one MP3 per song. */
    private fun ArchiveFileDto.isPlayableMp3(): Boolean {
        val f = format.orEmpty()
        return name.endsWith(".mp3", ignoreCase = true) && (f.contains("MP3", ignoreCase = true) || f.isEmpty()) &&
            !f.contains("64Kbps", ignoreCase = true) && !name.contains("_64kb", ignoreCase = true) &&
            !name.contains("sample", ignoreCase = true)
    }

    private fun ArchiveFileDto.trackNumber(): Int? = track?.substringBefore('/')?.trim()?.toIntOrNull()

    private fun ArchiveFileDto.toTrack(c: ArchiveCollection, index: Int): StreamTrack {
        val url = "https://archive.org/download/${c.id}/" + name.split('/').joinToString("/") { encPath(it) }
        return StreamTrack(
            id = "${c.id}/$name",
            source = StreamSource.Archive,
            title = title?.takeIf { it.isNotBlank() } ?: name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' '),
            artist = artist?.takeIf { it.isNotBlank() } ?: creator?.takeIf { it.isNotBlank() } ?: c.creator,
            album = album?.takeIf { it.isNotBlank() } ?: c.title,
            artworkUrl = c.artworkUrl,
            durationMs = length?.let(::parseLength) ?: 0L,
            streamUrl = url,
            // Items in these collections are published for free download by the artists/labels.
            downloadUrl = url,
            downloadFileName = safeFileName("${c.creator} - ${index + 1}. ${title ?: name.substringAfterLast('/')}".removeSuffix(".mp3") + ".mp3"),
            licenseUrl = c.pageUrl,
        )
    }

    private fun parseLength(raw: String): Long? =
        raw.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: PodcastFeedParser.parseDuration(raw)

    private fun encPath(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}

// ---------------------------------------------------------------------------------------------
// ccMixter: Creative Commons songs and remixes
// ---------------------------------------------------------------------------------------------

interface CcMixterRepository {
    suspend fun featured(): List<StreamTrack>
    suspend fun search(query: String): List<StreamTrack>
}

@Singleton
class DefaultCcMixterRepository @Inject constructor(private val api: CcMixterApi) : CcMixterRepository {
    override suspend fun featured() = apiCall { api.query(tags = "editorial_pick", limit = 30) }.mapNotNull { it.toModel() }
    override suspend fun search(query: String) = apiCall { api.query(search = query, limit = 25) }.mapNotNull { it.toModel() }

    private fun CcMixterUploadDto.toModel(): StreamTrack? {
        val file = files.firstOrNull { it.formatInfo?.mimeType == "audio/mpeg" || it.fileName.endsWith(".mp3", true) } ?: return null
        val url = file.downloadUrl?.takeIf { it.startsWith("http") } ?: return null
        val artist = userRealName?.takeIf { it.isNotBlank() } ?: userName.orEmpty()
        return StreamTrack(
            id = uploadId.toString(),
            source = StreamSource.CcMixter,
            title = uploadName,
            artist = artist,
            album = "ccMixter",
            artworkUrl = null,
            durationMs = file.formatInfo?.ps?.let(PodcastFeedParser::parseDuration) ?: 0L,
            streamUrl = url,
            // Creative Commons: sharing is allowed (with attribution — kept in the file name and licence link).
            downloadUrl = url,
            downloadFileName = safeFileName("$artist - $uploadName.mp3"),
            licenseUrl = licenseUrl ?: filePageUrl,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Last.fm: metadata only (similar artists and tags), used to build radio and "similar" queues
// ---------------------------------------------------------------------------------------------

interface LastFmRepository {
    val isEnabled: Boolean

    /** Artists that sound like [artist] (empty without a Last.fm key or when unknown). */
    suspend fun similarArtists(artist: String, limit: Int = 8): List<String> = emptyList()

    /** Genre-like tags for [artist], most used first (lower case). */
    suspend fun artistTags(artist: String): List<String> = emptyList()
}

@Singleton
class DefaultLastFmRepository @Inject constructor(
    private val api: LastFmApi,
    private val config: FreeSourcesConfig,
) : LastFmRepository {
    override val isEnabled: Boolean get() = config.isLastFmEnabled


    override suspend fun similarArtists(artist: String, limit: Int): List<String> {
        if (!isEnabled || artist.isBlank()) return emptyList()
        return apiCall { api.similarArtists(artist, config.lastFmApiKey, limit = limit) }
            .similarartists.artist.map { it.name }.filter { it.isNotBlank() }
    }

    override suspend fun artistTags(artist: String): List<String> {
        if (!isEnabled || artist.isBlank()) return emptyList()
        return apiCall { api.artistTags(artist, config.lastFmApiKey) }
            .toptags.tag.map { it.name.lowercase().trim() }.filter { it.isNotEmpty() }.take(10)
    }
}
