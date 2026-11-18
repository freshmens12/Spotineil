package com.spotineil.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

// ---------------- Audius (https://docs.audius.org) — open API, free full-track streaming ----------------

@Serializable
data class AudiusListDto<T>(val data: List<T> = emptyList())

@Serializable
data class AudiusArtworkDto(
    @SerialName("150x150") val small: String? = null,
    @SerialName("480x480") val medium: String? = null,
    @SerialName("1000x1000") val large: String? = null,
)

@Serializable
data class AudiusUserDto(val name: String = "", val handle: String = "")

@Serializable
data class AudiusTrackDto(
    val id: String,
    val title: String = "",
    val duration: Int = 0,
    val genre: String? = null,
    @SerialName("is_streamable") val isStreamable: Boolean = true,
    @SerialName("is_downloadable") val isDownloadable: Boolean = false,
    @SerialName("orig_filename") val origFilename: String? = null,
    val artwork: AudiusArtworkDto? = null,
    val user: AudiusUserDto = AudiusUserDto(),
)

interface AudiusApi {
    @GET("v1/tracks/trending")
    suspend fun trending(
        @Query("app_name") appName: String,
        @Query("limit") limit: Int = 30,
        @Query("genre") genre: String? = null,
    ): AudiusListDto<AudiusTrackDto>

    @GET("v1/tracks/trending/underground")
    suspend fun underground(@Query("app_name") appName: String, @Query("limit") limit: Int = 30): AudiusListDto<AudiusTrackDto>

    @GET("v1/tracks/search")
    suspend fun search(
        @Query("query") query: String,
        @Query("app_name") appName: String,
        @Query("limit") limit: Int = 30,
    ): AudiusListDto<AudiusTrackDto>
}

// ---------------- Jamendo (https://developer.jamendo.com) — Creative Commons music; needs a free client_id ----------------

@Serializable
data class JamendoHeadersDto(val status: String = "", val code: Int = 0, @SerialName("error_message") val errorMessage: String? = null)

@Serializable
data class JamendoTrackDto(
    val id: String,
    val name: String = "",
    val duration: Int = 0,
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_name") val albumName: String? = null,
    val image: String? = null,
    @SerialName("album_image") val albumImage: String? = null,
    val audio: String? = null,
    val audiodownload: String? = null,
    @SerialName("audiodownload_allowed") val audiodownloadAllowed: Boolean = false,
    @SerialName("license_ccurl") val licenseCcurl: String? = null,
)

@Serializable
data class JamendoResponseDto(val headers: JamendoHeadersDto = JamendoHeadersDto(), val results: List<JamendoTrackDto> = emptyList())

interface JamendoApi {
    @GET("tracks/")
    suspend fun tracks(
        @Query("client_id") clientId: String,
        @Query("search") search: String? = null,
        @Query("order") order: String? = null,
        @Query("limit") limit: Int = 30,
        @Query("format") format: String = "json",
        @Query("audioformat") audioFormat: String = "mp32",
        @Query("imagesize") imageSize: Int = 300,
    ): JamendoResponseDto
}

// ---------------- Radio Browser (https://api.radio-browser.info) — community radio directory ----------------

@Serializable
data class RadioStationDto(
    val stationuuid: String,
    val name: String = "",
    val url: String? = null,
    @SerialName("url_resolved") val urlResolved: String? = null,
    val favicon: String? = null,
    val tags: String? = null,
    val country: String? = null,
    val countrycode: String? = null,
    val codec: String? = null,
    val bitrate: Int = 0,
    val lastcheckok: Int = 1,
)

/** Full URLs are passed in so the repository can fail over between Radio Browser mirrors. */
interface RadioBrowserApi {
    @GET
    suspend fun stations(@Url url: String): List<RadioStationDto>

    /** Registers a play ("click") — Radio Browser asks clients to do this. */
    @GET
    suspend fun click(@Url url: String): Map<String, kotlinx.serialization.json.JsonElement>
}

// ---------------- Deezer & iTunes — official 30-second previews ----------------

@Serializable
data class DeezerArtistDto(val name: String = "")

@Serializable
data class DeezerAlbumDto(
    val title: String = "",
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("cover_xl") val coverXl: String? = null,
)

@Serializable
data class DeezerTrackDto(
    val id: Long,
    val title: String = "",
    val duration: Int = 0,
    val preview: String? = null,
    val readable: Boolean = true,
    val artist: DeezerArtistDto = DeezerArtistDto(),
    val album: DeezerAlbumDto = DeezerAlbumDto(),
)

@Serializable
data class DeezerListDto(val data: List<DeezerTrackDto> = emptyList())

@Serializable
data class DeezerChartDto(val tracks: DeezerListDto = DeezerListDto())

interface DeezerApi {
    @GET("search")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int = 40): DeezerListDto

    @GET("chart/{genre}")
    suspend fun chart(@Path("genre") genre: Int = 0, @Query("limit") limit: Int = 40): DeezerChartDto
}

@Serializable
data class ItunesTrackDto(
    val trackId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val artworkUrl100: String? = null,
    val previewUrl: String? = null,
    val trackTimeMillis: Long? = null,
)

@Serializable
data class ItunesSearchDto(val results: List<ItunesTrackDto> = emptyList())

interface ItunesApi {
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("country") country: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 40,
    ): ItunesSearchDto
}

// ---------------- Internet Archive (https://archive.org/developers) — Live Music Archive + netlabels ----------------

/** Archive fields can be a string or an array of strings; read either. */
fun kotlinx.serialization.json.JsonElement?.archiveText(): String? = when (this) {
    null, is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonArray -> mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .firstOrNull { it.isNotBlank() }
    is kotlinx.serialization.json.JsonPrimitive -> content.takeIf { it.isNotBlank() }
    else -> null
}

@Serializable
data class ArchiveDocDto(
    val identifier: String,
    val title: kotlinx.serialization.json.JsonElement? = null,
    val creator: kotlinx.serialization.json.JsonElement? = null,
    val date: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class ArchiveSearchBodyDto(val numFound: Int = 0, val docs: List<ArchiveDocDto> = emptyList())

@Serializable
data class ArchiveSearchDto(val response: ArchiveSearchBodyDto = ArchiveSearchBodyDto())

@Serializable
data class ArchiveFileDto(
    val name: String,
    val source: String? = null,
    val format: String? = null,
    val length: String? = null,
    val title: String? = null,
    val creator: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: String? = null,
)

@Serializable
data class ArchiveMetadataFieldsDto(
    val identifier: String? = null,
    val title: kotlinx.serialization.json.JsonElement? = null,
    val creator: kotlinx.serialization.json.JsonElement? = null,
    val date: kotlinx.serialization.json.JsonElement? = null,
    val description: kotlinx.serialization.json.JsonElement? = null,
    val mediatype: String? = null,
)

@Serializable
data class ArchiveMetadataDto(
    val metadata: ArchiveMetadataFieldsDto = ArchiveMetadataFieldsDto(),
    val files: List<ArchiveFileDto> = emptyList(),
)

interface ArchiveApi {
    @GET("advancedsearch.php")
    suspend fun search(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf("identifier", "title", "creator", "date"),
        @Query("sort[]") sort: String = "downloads desc",
        @Query("rows") rows: Int = 30,
        @Query("page") page: Int = 1,
        @Query("output") output: String = "json",
    ): ArchiveSearchDto

    @GET("metadata/{id}")
    suspend fun metadata(@Path("id") id: String): ArchiveMetadataDto
}

// ---------------- ccMixter (https://ccmixter.org/query-api) — Creative Commons songs & remixes ----------------

@Serializable
data class CcMixterFormatDto(val ps: String? = null, @SerialName("mime_type") val mimeType: String? = null)

@Serializable
data class CcMixterFileDto(
    @SerialName("file_name") val fileName: String = "",
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("file_format_info") val formatInfo: CcMixterFormatDto? = null,
)

@Serializable
data class CcMixterUploadDto(
    @SerialName("upload_id") val uploadId: Long,
    @SerialName("upload_name") val uploadName: String = "",
    @SerialName("user_real_name") val userRealName: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("license_url") val licenseUrl: String? = null,
    @SerialName("file_page_url") val filePageUrl: String? = null,
    val files: List<CcMixterFileDto> = emptyList(),
)

interface CcMixterApi {
    @GET("api/query")
    suspend fun query(
        @Query("search") search: String? = null,
        @Query("tags") tags: String? = null,
        @Query("sort") sort: String = "rank",
        @Query("limit") limit: Int = 30,
        @Query("f") format: String = "json",
    ): List<CcMixterUploadDto>
}

// ---------------- Apple Podcasts directory (search + top charts); episodes come from each show's RSS feed ----------------

@Serializable
data class ApplePodcastDto(
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
)

@Serializable
data class ApplePodcastSearchDto(val results: List<ApplePodcastDto> = emptyList())

@Serializable
data class AppleTopEntryDto(val id: String, val name: String = "", val artistName: String = "", val artworkUrl100: String? = null)

@Serializable
data class AppleTopFeedDto(val results: List<AppleTopEntryDto> = emptyList())

@Serializable
data class AppleTopDto(val feed: AppleTopFeedDto = AppleTopFeedDto())

interface ApplePodcastsApi {
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("country") country: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("limit") limit: Int = 30,
    ): ApplePodcastSearchDto

    /** [ids] is a comma-separated list; returns feed URLs. */
    @GET("lookup")
    suspend fun lookup(@Query("id") ids: String, @Query("entity") entity: String = "podcast"): ApplePodcastSearchDto

    /** e.g. https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/25/podcasts.json */
    @GET
    suspend fun top(@Url url: String): AppleTopDto
}

// ---------------- Last.fm (https://www.last.fm/api) — listening charts (metadata only; needs a free API key) ----------------

@Serializable
data class LastFmArtistDto(val name: String = "")

@Serializable
data class LastFmTrackDto(val name: String = "", val artist: LastFmArtistDto = LastFmArtistDto())

@Serializable
data class LastFmTrackListDto(val track: List<LastFmTrackDto> = emptyList())

@Serializable
data class LastFmChartDto(val tracks: LastFmTrackListDto = LastFmTrackListDto())

interface LastFmApi {
    @GET("2.0/")
    suspend fun topTracks(
        @Query("api_key") apiKey: String,
        @Query("method") method: String = "chart.gettoptracks",
        @Query("limit") limit: Int = 25,
        @Query("format") format: String = "json",
    ): LastFmChartDto
}

// ---------------- YouTube Data API v3 (metadata only; playback happens in YouTube's official player) ----------------

@Serializable
data class YtThumbDto(val url: String? = null)

@Serializable
data class YtThumbsDto(
    val default: YtThumbDto? = null,
    val medium: YtThumbDto? = null,
    val high: YtThumbDto? = null,
    val standard: YtThumbDto? = null,
    val maxres: YtThumbDto? = null,
)

@Serializable
data class YtSnippetDto(
    val title: String = "",
    val channelTitle: String = "",
    val thumbnails: YtThumbsDto = YtThumbsDto(),
    val liveBroadcastContent: String? = null,
    val categoryId: String? = null,
)

@Serializable
data class YtContentDetailsDto(val duration: String? = null)

@Serializable
data class YtStatusDto(val embeddable: Boolean = true, val privacyStatus: String? = null)

@Serializable
data class YtVideoDto(
    val id: String,
    val snippet: YtSnippetDto = YtSnippetDto(),
    val contentDetails: YtContentDetailsDto = YtContentDetailsDto(),
    val status: YtStatusDto = YtStatusDto(),
)

@Serializable
data class YtVideoListDto(val items: List<YtVideoDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
data class YtSearchIdDto(val videoId: String? = null)

@Serializable
data class YtSearchItemDto(val id: YtSearchIdDto = YtSearchIdDto())

@Serializable
data class YtSearchListDto(val items: List<YtSearchItemDto> = emptyList())

interface YouTubeApi {
    /** Costs 100 quota units. */
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("key") key: String,
        @Query("part") part: String = "id",
        @Query("type") type: String = "video",
        @Query("videoCategoryId") category: String = "10",
        @Query("videoEmbeddable") embeddable: String = "true",
        @Query("videoSyndicated") syndicated: String = "true",
        @Query("regionCode") region: String? = null,
        @Query("maxResults") max: Int = 25,
        @Query("safeSearch") safeSearch: String = "moderate",
    ): YtSearchListDto

    /** Costs 1 quota unit (for up to 50 ids, or one chart page). */
    @GET("videos")
    suspend fun videos(
        @Query("key") key: String,
        @Query("part") part: String = "snippet,contentDetails,status",
        @Query("id") ids: String? = null,
        @Query("chart") chart: String? = null,
        @Query("videoCategoryId") category: String? = null,
        @Query("regionCode") region: String? = null,
        @Query("maxResults") max: Int = 50,
    ): YtVideoListDto
}
