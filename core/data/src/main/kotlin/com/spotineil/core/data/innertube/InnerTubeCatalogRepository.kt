package com.spotineil.core.data.innertube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.InnerTubeLogger
import com.spotineil.core.model.Album
import com.spotineil.core.model.AlbumType
import com.spotineil.core.model.Artist
import com.spotineil.core.model.Image
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.SearchResults
import com.spotineil.core.model.SimpleAlbum
import com.spotineil.core.model.SimpleArtist
import com.spotineil.core.model.Track
import com.spotineil.core.model.UserRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import com.spotineil.core.model.YouTubeVideo
import com.spotineil.core.data.cache.JsonCache

interface InnerTubeCatalogRepository {
    suspend fun search(query: String): Result<SearchResults>
    /** What YouTube Music suggests for a search that is still being typed ("taylor sw" → "taylor swift"). */
    suspend fun searchSuggestions(query: String): Result<List<String>> = Result.success(emptyList())
    /** Songs only (studio audio first), ready to play. */
    suspend fun searchSongs(query: String): Result<List<YouTubeVideo>>
    /** Today's top songs for the device's country. */
    suspend fun charts(): Result<List<YouTubeVideo>>
    suspend fun resolveStream(videoId: String): Result<ResolvedYouTubeStream>
    /** The artist's YouTube Music channel (by name), for artist pages. */
    suspend fun findArtist(name: String): Result<Artist?>
    /** An artist page: top songs, albums & singles, similar artists. */
    suspend fun artist(channelId: String): Result<YtArtistPage>
    /** The songs of an album or playlist ("MPRE…", "OLAK…", "VL…"). */
    suspend fun collectionSongs(browseId: String): Result<List<YouTubeVideo>>
    /** YouTube Music's "Moods & genres" buttons. */
    suspend fun moods(): Result<List<YtMood>>
    /** The playlists of one mood or genre. */
    suspend fun moodPlaylists(mood: YtMood): Result<List<Playlist>>
}


/** A YouTube Music artist page. */
data class YtArtistPage(
    val songs: List<YouTubeVideo>,
    val albums: List<Album>,
    val similar: List<Artist>,
    val playlists: List<Playlist>,
)

/** A "Moods & genres" button: opens a page of playlists. */
@kotlinx.serialization.Serializable
data class YtMood(val title: String, val browseId: String, val params: String?, val color: Long? = null)
/** InnerTube search filter for "Songs". */
private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
/** InnerTube search filter for "Artists". */
private const val ARTISTS_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
private val VIDEO_LIST = ListSerializer(YouTubeVideo.serializer())
private val MOOD_LIST = ListSerializer(YtMood.serializer())

@Singleton
@OptIn(ExperimentalSabrApi::class)
class DefaultInnerTubeCatalogRepository @Inject constructor(
    private val innerTube: InnerTube,
    private val extractor: InnerTubeExtractor,
    private val logger: InnerTubeLogger,
    private val cache: JsonCache,
) : InnerTubeCatalogRepository {

    override suspend fun search(query: String): Result<SearchResults> {
        val key = "innertube:search:${query.trim().lowercase()}"
        cache.get(key, SearchResults.serializer(), 24 * 60 * 60 * 1000L)?.let { return Result.success(it) }
        val result = runCatching {
            val results = retry(times = 2) {
                withTimeout(15_000) {
                    val response = innerTube.search(YouTubeClient.WEB_REMIX, query = query)
                    mapSearch(parseJson(response.bodyAsText()))
                }
            }
            cache.put(key, SearchResults.serializer(), results)
            results
        }
        return result.recoverCatching { failure ->
            cache.get(key, SearchResults.serializer()) ?: throw failure
        }
    }

    override suspend fun searchSuggestions(query: String): Result<List<String>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        withTimeout(6_000) {
            val response = innerTube.getSearchSuggestions(YouTubeClient.WEB_REMIX, q)
            suggestionTexts(parseJson(response.bodyAsText()))
        }
    }

    override suspend fun searchSongs(query: String): Result<List<YouTubeVideo>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val key = "innertube:songs:${q.lowercase()}"
        cache.get(key, VIDEO_LIST, 24 * 60 * 60 * 1000L)?.let { return@runCatching it }
        try {
            val songs = retry(times = 2) {
                withTimeout(15_000) {
                    val response = innerTube.search(YouTubeClient.WEB_REMIX, query = q, params = SONGS_FILTER)
                    mapSearch(parseJson(response.bodyAsText())).tracks.map { it.toVideo() }
                }
            }
            cache.put(key, VIDEO_LIST, songs)
            songs
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            cache.get(key, VIDEO_LIST) ?: throw e
        }
    }

    override suspend fun charts(): Result<List<YouTubeVideo>> = runCatching {
        val key = "innertube:charts:${Locale.getDefault().country}"
        cache.get(key, VIDEO_LIST, 6 * 60 * 60 * 1000L)?.let { return@runCatching it }
        try {
            val songs = retry(times = 2) {
                withTimeout(25_000) {
                    val root = parseJson(innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_charts").bodyAsText())
                    // The charts page used to list the top songs itself. Today it lists chart playlists
                    // ("Trending 20", "Top 100 Music Videos"): open the best one and read its songs.
                    mapSearch(root).tracks.map { it.toVideo() }.ifEmpty {
                        val playlist = chartPlaylists(root).let { all ->
                            all.firstOrNull { it.first.startsWith("Trending", ignoreCase = true) }
                                ?: all.firstOrNull { it.first.contains("Top", ignoreCase = true) }
                                ?: all.firstOrNull()
                        } ?: return@withTimeout emptyList()
                        val page = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = playlist.second).bodyAsText()
                        mapSearch(parseJson(page)).tracks.map { it.toVideo() }
                    }
                }
            }.ifEmpty { throw IllegalStateException("Empty chart") }
            cache.put(key, VIDEO_LIST, songs)
            songs
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            cache.get(key, VIDEO_LIST) ?: throw e
        }
    }

    /** The chart playlists on the charts page as (title, browseId), e.g. "Trending 20" to "VLOLAK5uy_…". */
    private fun chartPlaylists(node: JsonElement, out: MutableList<Pair<String, String>> = mutableListOf()): List<Pair<String, String>> {
        when (node) {
            is JsonObject -> {
                (node["musicTwoRowItemRenderer"] as? JsonObject)?.let { item ->
                    val title = (((item["title"] as? JsonObject)?.get("runs") as? JsonArray)?.firstOrNull() as? JsonObject)
                        ?.get("text")?.let { it as? JsonPrimitive }?.content
                    val id = ((item["navigationEndpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject)
                        ?.get("browseId")?.let { it as? JsonPrimitive }?.content
                    if (title != null && id != null && id.startsWith("VL")) out += title to id
                }
                node.values.forEach { chartPlaylists(it, out) }
            }
            is JsonArray -> node.forEach { chartPlaylists(it, out) }
            else -> Unit
        }
        return out
    }

    private fun parseJson(text: String): JsonObject =
        Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

    private fun Track.toVideo() = YouTubeVideo(
        id = id,
        title = name,
        channel = artistNames,
        thumbnailUrl = images.firstOrNull()?.url ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        durationMs = durationMs,
    )

    override suspend fun findArtist(name: String): Result<Artist?> = runCatching {
        val results = retry(times = 1) {
            withTimeout(15_000) { mapSearch(parseJson(innerTube.search(YouTubeClient.WEB_REMIX, query = name, params = ARTISTS_FILTER).bodyAsText())) }
        }
        val wanted = name.trim().lowercase()
        results.artists.firstOrNull { it.name.trim().lowercase() == wanted } ?: results.artists.firstOrNull()
    }

    override suspend fun artist(channelId: String): Result<YtArtistPage> = runCatching {
        val root = retry(times = 1) {
            withTimeout(25_000) { parseJson(innerTube.browse(YouTubeClient.WEB_REMIX, browseId = channelId).bodyAsText()) }
        }
        val page = mapSearch(root)
        YtArtistPage(
            // The same song can appear in several versions: keep the first of each title.
            songs = page.tracks.map { it.toVideo() }.distinctBy { it.songTitle.lowercase().trim() },
            albums = page.albums,
            similar = page.artists.filter { it.id != channelId },
            playlists = page.playlists,
        )
    }

    override suspend fun collectionSongs(browseId: String): Result<List<YouTubeVideo>> = runCatching {
        val root = retry(times = 1) {
            withTimeout(25_000) { parseJson(innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId).bodyAsText()) }
        }
        // Album rows often have no picture of their own: use the album cover for every song.
        val cover = findThumbnail(root)
        mapSearch(root).tracks.map { t ->
            val v = t.toVideo()
            if (t.images.isEmpty() && cover != null) v.copy(thumbnailUrl = cover) else v
        }
    }

    override suspend fun moods(): Result<List<YtMood>> = runCatching {
        val key = "innertube:moods:${Locale.getDefault().country}"
        cache.get(key, MOOD_LIST, 24 * 60 * 60 * 1000L)?.let { return@runCatching it }
        val root = retry(times = 1) {
            withTimeout(25_000) { parseJson(innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_moods_and_genres").bodyAsText()) }
        }
        val out = mutableListOf<YtMood>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["musicNavigationButtonRenderer"] as? JsonObject)?.let { b ->
                        val title = jsonRunsText(b["buttonText"])
                        val endpoint = (b["clickCommand"] as? JsonObject)?.get("browseEndpoint") as? JsonObject
                        val id = (endpoint?.get("browseId") as? JsonPrimitive)?.content
                        val params = (endpoint?.get("params") as? JsonPrimitive)?.content
                        val color = (((b["solid"] as? JsonObject)?.get("leftStripeColor")) as? JsonPrimitive)?.content?.toLongOrNull()
                        if (title.isNotBlank() && id != null) out += YtMood(title, id, params, color)
                    }
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        val moods = out.distinctBy { it.title }
        if (moods.isNotEmpty()) cache.put(key, MOOD_LIST, moods)
        moods
    }

    override suspend fun moodPlaylists(mood: YtMood): Result<List<Playlist>> = runCatching {
        val root = retry(times = 1) {
            withTimeout(25_000) {
                parseJson(innerTube.browse(YouTubeClient.WEB_REMIX, browseId = mood.browseId, params = mood.params).bodyAsText())
            }
        }
        mapSearch(root).playlists
    }

    override suspend fun resolveStream(videoId: String): Result<ResolvedYouTubeStream> = runCatching {
        retry(times = 2) {
            withTimeout(30_000) {
                val hints = ContentHints(wantVideo = false)
                    .withStreamCapabilities(allowHls = true, allowSabr = true, allowBoundedRange = true)
                val stream = extractor.extract(
                    videoId = videoId,
                    hints = hints,
                    audioQuality = AudioQuality.HIGH,
                ) ?: throw IllegalStateException("No playable stream returned")
                ResolvedYouTubeStream(
                    videoId = stream.videoId,
                    directUrl = stream.audioUrl.takeIf { it.isNotBlank() },
                    headers = stream.headers,
                    expiresAtEpochMillis = stream.expiresAt?.toEpochMilliseconds(),
                    contentLengthBytes = stream.contentLengthBytes,
                    sabrBytes = stream.sabrBootstrap?.let<SabrBootstrap, (Long) -> Flow<ByteArray>?> { bootstrap ->
                        { positionMs: Long ->
                            sabrFlow(innerTube.httpClient, bootstrap, positionMs)
                        }
                    } ?: { _: Long -> null },
                )
            }
        }
    }

    private fun sabrFlow(
        httpClient: HttpClient,
        bootstrap: SabrBootstrap,
        positionMs: Long,
    ): Flow<ByteArray> = SabrAudioStream(
        httpClient = httpClient,
        bootstrap = bootstrap,
        initialPlayerTimeMs = positionMs,
        logger = logger,
    ).bytes()

    private suspend fun <T> retry(times: Int, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times + 1) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                last = t
                android.util.Log.w("SpotineilYT", "request failed (attempt ${attempt + 1})", t)
                if (attempt < times) delay(250L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("Retry failed")
    }

    private fun mapSearch(root: JsonObject): SearchResults {
        val tracks = mutableListOf<Track>()
        val albums = mutableListOf<Album>()
        val artists = mutableListOf<Artist>()
        val playlists = mutableListOf<Playlist>()
        collectRendererObjects(root, tracks, albums, artists, playlists)

        return SearchResults(
            tracks = tracks.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            playlists = playlists.distinctBy { it.id },
        )
    }

    private fun collectRendererObjects(
        node: JsonObject,
        tracks: MutableList<Track>,
        albums: MutableList<Album>,
        artists: MutableList<Artist>,
        playlists: MutableList<Playlist>,
    ) {
        node["musicResponsiveListItemRenderer"]?.let { element ->
            val renderer = element as? JsonObject ?: return@let
            mapResponsive(renderer)?.let { item ->
                when (item.kind) {
                    ItemKind.Track -> tracks.add(item.track!!)
                    ItemKind.Album -> albums.add(item.album!!)
                    ItemKind.Artist -> artists.add(item.artist!!)
                    ItemKind.Playlist -> playlists.add(item.playlist!!)
                }
            }
        }
        node["musicTwoRowItemRenderer"]?.let { element ->
            val renderer = element as? JsonObject ?: return@let
            mapTwoRow(renderer)?.let { item ->
                when (item.kind) {
                    ItemKind.Track -> tracks.add(item.track!!)
                    ItemKind.Album -> albums.add(item.album!!)
                    ItemKind.Artist -> artists.add(item.artist!!)
                    ItemKind.Playlist -> playlists.add(item.playlist!!)
                }
            }
        }
        node.values.forEach { value ->
            when (value) {
                is JsonObject -> collectRendererObjects(value, tracks, albums, artists, playlists)
                is JsonArray -> value.forEach { if (it is JsonObject) collectRendererObjects(it, tracks, albums, artists, playlists) }
                else -> Unit
            }
        }
    }

    private fun mapResponsive(renderer: JsonObject): MappedItem? {
        val flexColumns = renderer["flexColumns"] as? JsonArray ?: JsonArray(emptyList())
        val texts = flexColumns.mapNotNull { column ->
            val c = (column as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject
            val runs = (c?.get("text") as? JsonObject)?.get("runs") as? JsonArray
            runs?.joinToString("") { run -> ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }?.trim()
        }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return null

        val title = texts[0]
        val subtitle = texts.getOrNull(1).orEmpty()
        val endpoint = findEndpoint(renderer)
        val videoId = renderer["playlistItemData"]?.let {
            ((it as? JsonObject)?.get("videoId") as? JsonPrimitive)?.content
        } ?: endpoint.videoId
        val browseId = endpoint.browseId
        val imageUrl = findThumbnail(renderer)?.let { Regex("=w\\d+-h\\d+").replace(it, "=w544-h544") }

        return when {
            videoId != null -> MappedItem(
                ItemKind.Track,
                track = Track(
                    id = videoId,
                    uri = "ytmusic:video:$videoId",
                    name = title,
                    artists = listOf(SimpleArtist(id = "", uri = "", name = trackArtist(subtitle))),
                    album = imageUrl?.let { SimpleAlbum(id = "", uri = "", name = "", images = listOf(Image(it))) },
                    durationMs = parseDuration(subtitle),
                ),
            )
            browseId?.startsWith("UC") == true -> MappedItem(
                ItemKind.Artist,
                artist = Artist(id = browseId, uri = "ytmusic:channel:$browseId", name = title, images = imageUrl?.let { listOf(Image(it)) }.orEmpty()),
            )
            browseId?.startsWith("VL") == true -> MappedItem(
                ItemKind.Playlist,
                playlist = Playlist(
                    id = browseId,
                    uri = "ytmusic:playlist:$browseId",
                    name = title,
                    description = subtitle.ifEmpty { null },
                    images = imageUrl?.let { listOf(Image(it)) }.orEmpty(),
                    owner = UserRef(id = "", displayName = "YouTube Music"),
                    isPublic = true,
                    collaborative = false,
                    trackCount = 0,
                ),
            )
            browseId?.startsWith("MPRE") == true || browseId?.startsWith("OLAK") == true -> MappedItem(
                ItemKind.Album,
                album = Album(
                    id = browseId,
                    uri = "ytmusic:album:$browseId",
                    name = title,
                    artists = listOf(SimpleArtist(id = "", uri = "", name = subtitle.substringBefore("•").trim())),
                    images = imageUrl?.let { listOf(Image(it)) }.orEmpty(),
                    albumType = AlbumType.Album,
                ),
            )
            else -> null
        }
    }

    private fun mapTwoRow(renderer: JsonObject): MappedItem? {
        val title = jsonRunsText(renderer["title"])
        if (title.isBlank()) return null
        val subtitle = jsonRunsText(renderer["subtitle"])
        val endpoint = findEndpoint(renderer)
        val imageUrl = findThumbnail(renderer)
        val browseId = endpoint.browseId
        val videoId = endpoint.videoId

        return when {
            videoId != null -> MappedItem(ItemKind.Track, track = Track(
                id = videoId,
                uri = "ytmusic:video:$videoId",
                name = title,
                artists = listOf(SimpleArtist(id = "", uri = "", name = subtitle.substringBefore("•").trim().ifEmpty { "YouTube Music" })),
                album = null,
                durationMs = parseDuration(subtitle),
            ))
            browseId?.startsWith("UC") == true -> MappedItem(ItemKind.Artist, artist = Artist(
                id = browseId,
                uri = "ytmusic:channel:$browseId",
                name = title,
                images = imageUrl?.let { listOf(Image(it)) }.orEmpty(),
            ))
            browseId?.startsWith("VL") == true -> MappedItem(ItemKind.Playlist, playlist = Playlist(
                id = browseId,
                uri = "ytmusic:playlist:$browseId",
                name = title,
                description = subtitle.ifEmpty { null },
                images = imageUrl?.let { listOf(Image(it)) }.orEmpty(),
                owner = UserRef(id = "", displayName = "YouTube Music"),
                isPublic = true,
                collaborative = false,
                trackCount = 0,
            ))
            browseId != null -> MappedItem(ItemKind.Album, album = Album(
                id = browseId,
                uri = "ytmusic:album:$browseId",
                name = title,
                artists = listOf(SimpleArtist(id = "", uri = "", name = subtitle.substringBefore("•").trim())),
                images = imageUrl?.let { listOf(Image(it)) }.orEmpty(),
                albumType = AlbumType.Album,
            ))
            else -> null
        }
    }

    /** "Song • Artist • Album • 3:45" or "Artist • Album • 3:45" → the artist. */
    private fun trackArtist(subtitle: String): String {
        val parts = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
        val kinds = setOf("song", "video", "single", "episode")
        val start = if (parts.firstOrNull()?.lowercase() in kinds) 1 else 0
        return parts.getOrNull(start)?.takeUnless { Regex("""\d+:\d+(:\d+)?""").matches(it) } ?: "Unknown artist"
    }

    private data class Endpoint(val videoId: String? = null, val browseId: String? = null)

    private fun findEndpoint(node: JsonObject): Endpoint {
        val endpoint = node["navigationEndpoint"] as? JsonObject ?: return Endpoint()
        val watch = endpoint["watchEndpoint"] as? JsonObject
        val browse = endpoint["browseEndpoint"] as? JsonObject
        val videoId = (watch?.get("videoId") as? JsonPrimitive)?.content
        val browseId = (browse?.get("browseId") as? JsonPrimitive)?.content
        if (videoId != null || browseId != null) return Endpoint(videoId, browseId)
        var found = Endpoint()
        node.values.forEach { value ->
            when (value) {
                is JsonObject -> found = findEndpoint(value)
                is JsonArray -> value.forEach { if (it is JsonObject) found = findEndpoint(it) }
                else -> Unit
            }
            if (found.videoId != null || found.browseId != null) return@forEach
        }
        return found
    }

    private fun findThumbnail(node: JsonObject): String? {
        val thumbs = node["thumbnail"] as? JsonObject
        val urls = mutableListOf<Pair<Int, String>>()
        collectUrls(thumbs ?: node, urls)
        return urls.sortedByDescending { it.first }.firstOrNull()?.second
    }

    private fun collectUrls(node: JsonObject, out: MutableList<Pair<Int, String>>) {
        node["url"]?.let { if (it is JsonPrimitive) out.add(0 to it.content) }
        node["width"]?.let { primitive ->
            val width = (primitive as? JsonPrimitive)?.intOrNull
            val url = (node["url"] as? JsonPrimitive)?.content
            if (width != null && url != null) out.add(width to url)
        }
        node.values.forEach { value ->
            when (value) {
                is JsonObject -> collectUrls(value, out)
                is JsonArray -> value.forEach { if (it is JsonObject) collectUrls(it, out) }
                else -> Unit
            }
        }
    }

    private fun jsonRunsText(element: JsonElement?): String =
        ((element as? JsonObject)?.get("runs") as? JsonArray)
            ?.joinToString("") { run -> ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
            ?.trim()
            .orEmpty()

    private fun parseDuration(text: String): Long {
        val duration = text.substringAfterLast("•").trim()
        if (!Regex("""\d+:\d+(?::\d+)?""").matches(duration)) return 0L
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        return if (parts.size == 3) ((parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L)
        else if (parts.size == 2) ((parts[0] * 60L + parts[1]) * 1000L) else 0L
    }

    private enum class ItemKind { Track, Album, Artist, Playlist }
    private data class MappedItem(
        val kind: ItemKind,
        val track: Track? = null,
        val album: Album? = null,
        val artist: Artist? = null,
        val playlist: Playlist? = null,
    )
}
