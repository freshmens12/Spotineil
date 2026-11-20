package com.spotineil.core.model

import kotlinx.serialization.Serializable

/** Legal, free audio sources that don't depend on Spotify. */
enum class StreamSource(val label: String) {
    Audius("Audius"),
    Jamendo("Jamendo"),
    Archive("Internet Archive"),
    CcMixter("ccMixter"),
    Deezer("Deezer preview"),
    ITunes("iTunes preview"),
    Radio("Radio"),
    Podcast("Podcast"),
    Local("On this device"),
}

/**
 * A full track (Audius/Jamendo, free and streamable by the artist's choice) or an official
 * 30-second preview (Deezer/iTunes).
 */
data class StreamTrack(
    val id: String,
    val source: StreamSource,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long,
    val streamUrl: String,
    /** Only set when the artist/licence explicitly allows downloading. */
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val licenseUrl: String? = null,
) {
    val isPreview: Boolean get() = source == StreamSource.Deezer || source == StreamSource.ITunes
    val canDownload: Boolean get() = downloadUrl != null
}

@Serializable
data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val faviconUrl: String?,
    val tags: List<String>,
    val country: String?,
    val countryCode: String?,
    val codec: String?,
    val bitrate: Int,
) {
    val subtitle: String
        get() = listOfNotNull(
            tags.take(3).joinToString(", ").takeIf { it.isNotBlank() },
            country?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
}

/** Anything the on-device (Media3) player can stream: local files, free tracks, previews, radio, episodes. */
@Serializable
data class PlayableMedia(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val isLive: Boolean = false,
    /** Full length of the song (for a 30 s preview this is the original song's length; used for lyrics matching). */
    val durationMs: Long = 0,
    val isPreview: Boolean = false,
    /** Podcast episodes: no lyrics lookup. */
    val isEpisode: Boolean = false,
)

fun StreamTrack.toPlayable() = PlayableMedia(
    mediaId = "${source.name.lowercase()}:$id",
    uri = streamUrl,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    isPreview = isPreview,
)

// ---------------------------------------------------------------------------------------------
// Internet Archive collections (Live Music Archive concerts, netlabel albums)
// ---------------------------------------------------------------------------------------------

enum class CollectionKind { Concert, Album }

@Serializable
data class ArchiveCollection(
    val id: String,
    val title: String,
    val creator: String,
    val date: String? = null,
    val kind: CollectionKind = CollectionKind.Album,
) {
    val artworkUrl: String get() = "https://archive.org/services/img/$id"
    val pageUrl: String get() = "https://archive.org/details/$id"
}

data class ArchiveCollectionDetail(
    val collection: ArchiveCollection,
    val description: String?,
    val tracks: List<StreamTrack>,
)

// ---------------------------------------------------------------------------------------------
// Podcasts (public RSS feeds, found through the Apple Podcasts directory)
// ---------------------------------------------------------------------------------------------

@Serializable
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String? = null,
)

data class PodcastEpisode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val publishedAtMs: Long?,
    val durationMs: Long,
    val description: String?,
    val artworkUrl: String?,
)

data class PodcastDetail(
    val podcast: Podcast,
    val description: String?,
    val episodes: List<PodcastEpisode>,
)

fun PodcastEpisode.toPlayable(podcast: Podcast) = PlayableMedia(
    mediaId = "episode:$id",
    uri = audioUrl,
    title = title,
    artist = podcast.title,
    album = podcast.author,
    artworkUrl = artworkUrl ?: podcast.artworkUrl,
    durationMs = durationMs,
    isEpisode = true,
)

// ---------------------------------------------------------------------------------------------
// "Jump back in": things the listener opened recently
// ---------------------------------------------------------------------------------------------

@Serializable
sealed interface RecentItem {
    val key: String
    val title: String
    val subtitle: String
    val imageUrl: String?

    @Serializable
    data class Song(val media: PlayableMedia) : RecentItem {
        override val key get() = "song:${media.mediaId}"
        override val title get() = media.title
        override val subtitle get() = media.artist
        override val imageUrl get() = media.artworkUrl
    }

    @Serializable
    data class Station(val station: RadioStation) : RecentItem {
        override val key get() = "station:${station.id}"
        override val title get() = station.name
        override val subtitle get() = station.subtitle
        override val imageUrl get() = station.faviconUrl
    }

    @Serializable
    data class Show(val podcast: Podcast) : RecentItem {
        override val key get() = "podcast:${podcast.feedUrl}"
        override val title get() = podcast.title
        override val subtitle get() = podcast.author
        override val imageUrl get() = podcast.artworkUrl
    }

    @Serializable
    data class Collection(val collection: ArchiveCollection) : RecentItem {
        override val key get() = "archive:${collection.id}"
        override val title get() = collection.title
        override val subtitle get() = collection.creator
        override val imageUrl get() = collection.artworkUrl
    }
}

fun RadioStation.toPlayable() = PlayableMedia(
    mediaId = "radio:$id",
    uri = streamUrl,
    title = name,
    artist = subtitle.ifBlank { "Live radio" },
    artworkUrl = faviconUrl,
    isLive = true,
)

fun LocalTrack.toPlayable() = PlayableMedia(
    mediaId = "local:$id",
    uri = contentUri,
    title = title,
    artist = artist,
    album = album,
    artworkUrl = albumArtUri,
)

// ---------------------------------------------------------------------------------------------
// Main song catalog
// ---------------------------------------------------------------------------------------------

@Serializable
data class YouTubeVideo(
    val id: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
) {
    /** "Artist - Song (Official Video)" → artist/song guesses (for lyrics and display). */
    val songTitle: String get() = YouTubeTitles.song(title, channel)
    val artist: String get() = YouTubeTitles.artist(title, channel)

    /**
     * Uploaded by YouTube's auto-generated "Artist - Topic" channel: the official studio recording
     * with its album cover, i.e. what YouTube Music plays as a "song" (not a music video).
     */
    val isTopic: Boolean get() = channel.endsWith(" - Topic", ignoreCase = true)
}

/** Where YouTube listening stopped, so it can continue later (even after the app was closed). */
@Serializable
data class YouTubeSession(
    val queue: List<YouTubeVideo>,
    val index: Int,
    val positionMs: Long,
) {
    val current: YouTubeVideo? get() = queue.getOrNull(index)
}

const val YOUTUBE_MEDIA_PREFIX = "youtube:"

/** Saved in Liked Songs / history, and played by the on-device player through the `ytmusic:video:` URI. */
fun YouTubeVideo.toPlayable() = PlayableMedia(
    mediaId = YOUTUBE_MEDIA_PREFIX + id,
    uri = "ytmusic:video:$id",
    title = songTitle,
    artist = artist,
    artworkUrl = thumbnailUrl,
    durationMs = durationMs,
)

val PlayableMedia.isYouTube: Boolean get() = mediaId.startsWith(YOUTUBE_MEDIA_PREFIX)

fun PlayableMedia.toYouTubeVideo(): YouTubeVideo? = if (!isYouTube) null else YouTubeVideo(
    id = mediaId.removePrefix(YOUTUBE_MEDIA_PREFIX),
    title = if (artist.isNotBlank()) "$artist - $title" else title,
    channel = artist,
    thumbnailUrl = artworkUrl,
    durationMs = durationMs,
)

/** Heuristics for music-video titles. */
object YouTubeTitles {
    private val NOISE = Regex(
        """\s*[(\[](?:official|lyric|lyrics|audio|video|music video|mv|visualizer|hd|hq|4k|live|performance|explicit|clean)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val SEPARATOR = Regex("""\s+[-–—|]\s+""")
    private val CHANNEL_SUFFIX = Regex("""\s*(?:-\s*Topic|VEVO|Official)$""", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String = raw.replace(NOISE, "").replace(Regex("""\s{2,}"""), " ").trim()

    fun song(title: String, channel: String): String {
        val cleaned = clean(title)
        val parts = cleaned.split(SEPARATOR, limit = 2)
        return if (parts.size == 2) parts[1].trim() else cleaned
    }

    fun artist(title: String, channel: String): String {
        val parts = clean(title).split(SEPARATOR, limit = 2)
        return if (parts.size == 2) parts[0].trim() else channel.replace(CHANNEL_SUFFIX, "").trim()
    }
}
