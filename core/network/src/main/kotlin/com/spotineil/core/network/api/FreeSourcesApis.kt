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
    /** Set when downloading needs something first (e.g. following the artist): we then can't download it. */
    @SerialName("download_conditions") val downloadConditions: kotlinx.serialization.json.JsonElement? = null,
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

@Serializable
data class LastFmArtistListDto(val artist: List<LastFmArtistDto> = emptyList())

@Serializable
data class LastFmSimilarDto(val similarartists: LastFmArtistListDto = LastFmArtistListDto())

@Serializable
data class LastFmTagListDto(val tag: List<LastFmArtistDto> = emptyList())

@Serializable
data class LastFmTopTagsDto(val toptags: LastFmTagListDto = LastFmTagListDto())

interface LastFmApi {
    @GET("2.0/")
    suspend fun topTracks(
        @Query("api_key") apiKey: String,
        @Query("method") method: String = "chart.gettoptracks",
        @Query("limit") limit: Int = 25,
        @Query("format") format: String = "json",
    ): LastFmChartDto

    @GET("2.0/")
    suspend fun similarArtists(
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("method") method: String = "artist.getsimilar",
        @Query("autocorrect") autocorrect: Int = 1,
        @Query("limit") limit: Int = 8,
        @Query("format") format: String = "json",
    ): LastFmSimilarDto

    /** Genre-like tags listeners gave an artist ("soul", "pop", "opm"…), most used first. */
    @GET("2.0/")
    suspend fun artistTags(
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("method") method: String = "artist.gettoptags",
        @Query("autocorrect") autocorrect: Int = 1,
        @Query("format") format: String = "json",
    ): LastFmTopTagsDto
}
