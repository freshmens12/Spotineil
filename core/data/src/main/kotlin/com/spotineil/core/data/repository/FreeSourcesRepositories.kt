package com.spotineil.core.data.repository

import com.spotineil.core.common.FreeSourcesConfig
import com.spotineil.core.data.db.FavoriteStationDao
import com.spotineil.core.data.db.FavoriteStationEntity
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.StreamSource
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.network.api.AudiusApi
import com.spotineil.core.network.api.AudiusTrackDto
import com.spotineil.core.network.api.DeezerApi
import com.spotineil.core.network.api.DeezerTrackDto
import com.spotineil.core.network.api.ItunesApi
import com.spotineil.core.network.api.JamendoApi
import com.spotineil.core.network.api.JamendoTrackDto
import com.spotineil.core.network.api.RadioBrowserApi
import com.spotineil.core.network.api.RadioStationDto
import com.spotineil.core.network.apiCall
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------------------------
// Free full tracks: Audius + Jamendo
// ---------------------------------------------------------------------------------------------

data class FreeMusicSections(
    val audius: List<StreamTrack>,
    val jamendo: List<StreamTrack>,
    val jamendoEnabled: Boolean,
)

interface FreeMusicRepository {
    suspend fun featured(): FreeMusicSections
    suspend fun search(query: String): FreeMusicSections

    /** Audius trending, optionally for one genre (Audius genre names, e.g. "Electronic", "Hip-Hop/Rap"). */
    suspend fun trending(genre: String? = null, limit: Int = 30): List<StreamTrack>

    /** Audius "underground" trending: rising artists. */
    suspend fun underground(limit: Int = 30): List<StreamTrack>

    /** Jamendo's most popular songs this week; empty when no `JAMENDO_CLIENT_ID` is set. */
    suspend fun jamendoPopular(limit: Int = 30): List<StreamTrack> = emptyList()
}

@Singleton
class DefaultFreeMusicRepository @Inject constructor(
    private val audius: AudiusApi,
    private val jamendo: JamendoApi,
    private val config: FreeSourcesConfig,
) : FreeMusicRepository {

    override suspend fun featured() = load(
        audiusCall = { audius.trending(config.audiusAppName, limit = 30) },
        jamendoOrder = "popularity_week",
        query = null,
    )

    override suspend fun search(query: String) = load(
        audiusCall = { audius.search(query, config.audiusAppName, limit = 30) },
        jamendoOrder = "relevance",
        query = query,
    )

    override suspend fun trending(genre: String?, limit: Int) =
        apiCall { audius.trending(config.audiusAppName, limit = limit, genre = genre) }.data.mapNotNull { it.toModel() }

    override suspend fun underground(limit: Int) =
        apiCall { audius.underground(config.audiusAppName, limit = limit) }.data.mapNotNull { it.toModel() }

    override suspend fun jamendoPopular(limit: Int): List<StreamTrack> =
        if (!config.isJamendoEnabled) emptyList()
        else jamendoTracks(search = null, order = "popularity_week", limit = limit)

    /**
     * Jamendo now and then answers "success" with zero results for a request that works a second
     * later, so an empty answer is retried a couple of times before it is believed.
     */
    private suspend fun jamendoTracks(search: String?, order: String, limit: Int = 30): List<StreamTrack> {
        var tracks = emptyList<StreamTrack>()
        repeat(JAMENDO_ATTEMPTS) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(400L * attempt)
            tracks = apiCall { jamendo.tracks(config.jamendoClientId, search = search, order = order, limit = limit) }
                .results.mapNotNull { it.toModel() }
            if (tracks.isNotEmpty()) return tracks
        }
        return tracks
    }

    /** One source failing doesn't hide the other; only fail when both fail. */
    private suspend fun load(
        audiusCall: suspend () -> com.spotineil.core.network.api.AudiusListDto<AudiusTrackDto>,
        jamendoOrder: String,
        query: String?,
    ): FreeMusicSections = coroutineScope {
        val a = async { runCatching { apiCall(audiusCall).data.mapNotNull { it.toModel() } } }
        val j = async {
            if (!config.isJamendoEnabled) Result.success(emptyList())
            else runCatching { jamendoTracks(search = query, order = jamendoOrder) }
        }
        val ar = a.await()
        val jr = j.await()
        if (ar.isFailure && (jr.isFailure || !config.isJamendoEnabled)) throw ar.exceptionOrNull()!!
        FreeMusicSections(ar.getOrDefault(emptyList()), jr.getOrDefault(emptyList()), config.isJamendoEnabled)
    }

    private fun AudiusTrackDto.toModel(): StreamTrack? {
        if (!isStreamable) return null
        val app = config.audiusAppName
        val ext = origFilename?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 } ?: "mp3"
        return StreamTrack(
            id = id,
            source = StreamSource.Audius,
            title = title,
            artist = user.name.ifBlank { user.handle },
            album = genre,
            artworkUrl = artwork?.medium ?: artwork?.small,
            durationMs = duration * 1000L,
            streamUrl = "https://api.audius.co/v1/tracks/$id/stream?app_name=$app",
            downloadUrl = if (isDownloadable) "https://api.audius.co/v1/tracks/$id/download?app_name=$app" else null,
            downloadFileName = safeFileName("${user.name} - $title.$ext"),
        )
    }

    private fun JamendoTrackDto.toModel(): StreamTrack? {
        val stream = audio?.takeIf { it.isNotBlank() } ?: return null
        return StreamTrack(
            id = id,
            source = StreamSource.Jamendo,
            title = name,
            artist = artistName,
            album = albumName,
            artworkUrl = albumImage ?: image,
            durationMs = duration * 1000L,
            streamUrl = stream,
            downloadUrl = audiodownload?.takeIf { audiodownloadAllowed && it.isNotBlank() },
            downloadFileName = safeFileName("$artistName - $name.mp3"),
            licenseUrl = licenseCcurl,
        )
    }

    private companion object {
        const val JAMENDO_ATTEMPTS = 3
    }
}

internal fun safeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(120)

// ---------------------------------------------------------------------------------------------
// Internet radio: Radio Browser
// ---------------------------------------------------------------------------------------------

interface RadioRepository {
    val favorites: Flow<List<RadioStation>>
    suspend fun top(limit: Int = 40): List<RadioStation>
    suspend fun byCountry(countryCode: String, limit: Int = 40): List<RadioStation>
    suspend fun byTag(tag: String, limit: Int = 50): List<RadioStation>
    suspend fun search(query: String, limit: Int = 50): List<RadioStation>
    suspend fun setFavorite(station: RadioStation, favorite: Boolean)
    /** Tells Radio Browser a station was played (their API etiquette); failures are ignored. */
    suspend fun reportPlay(station: RadioStation)
}

@Singleton
class DefaultRadioRepository @Inject constructor(
    private val api: RadioBrowserApi,
    private val favoriteDao: FavoriteStationDao,
    private val json: Json,
) : RadioRepository {

    /** Radio Browser is community-run on several mirrors; fail over between them. */
    internal var hosts = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
    )

    override val favorites: Flow<List<RadioStation>> = favoriteDao.observe().map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(RadioStation.serializer(), it.json) }.getOrNull() }
    }

    override suspend fun top(limit: Int) = stations("/json/stations/topclick/$limit?hidebroken=true")

    override suspend fun byCountry(countryCode: String, limit: Int) =
        stations("/json/stations/search?countrycode=${enc(countryCode)}&order=clickcount&reverse=true&hidebroken=true&limit=$limit")

    override suspend fun byTag(tag: String, limit: Int) =
        stations("/json/stations/search?tag=${enc(tag)}&order=clickcount&reverse=true&hidebroken=true&limit=$limit")

    override suspend fun search(query: String, limit: Int) =
        stations("/json/stations/search?name=${enc(query)}&order=clickcount&reverse=true&hidebroken=true&limit=$limit")

    override suspend fun setFavorite(station: RadioStation, favorite: Boolean) {
        if (favorite) {
            favoriteDao.upsert(FavoriteStationEntity(station.id, json.encodeToString(RadioStation.serializer(), station), System.currentTimeMillis()))
        } else {
            favoriteDao.delete(station.id)
        }
    }

    override suspend fun reportPlay(station: RadioStation) {
        runCatching { api.click(hosts.first() + "/json/url/${station.id}") }
    }

    private suspend fun stations(path: String): List<RadioStation> {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                return apiCall { api.stations(host + path) }.mapNotNull { it.toModel() }.distinctBy { it.id }
            } catch (e: SpotineilException) {
                last = e
            }
        }
        throw last ?: SpotineilException.Unknown()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun RadioStationDto.toModel(): RadioStation? {
        if (lastcheckok == 0) return null
        val stream = (urlResolved ?: url)?.takeIf { it.isNotBlank() } ?: return null
        return RadioStation(
            id = stationuuid,
            name = name.trim().ifBlank { "Unnamed station" },
            streamUrl = stream,
            faviconUrl = favicon?.takeIf { it.startsWith("http") },
            tags = tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() },
            country = country,
            countryCode = countrycode,
            codec = codec,
            bitrate = bitrate,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 30-second previews: Deezer, falling back to iTunes
// ---------------------------------------------------------------------------------------------

interface PreviewRepository {
    suspend fun charts(): List<StreamTrack>
    suspend fun search(query: String): List<StreamTrack>
}

@Singleton
class DefaultPreviewRepository @Inject constructor(
    private val deezer: DeezerApi,
    private val itunes: ItunesApi,
) : PreviewRepository {

    override suspend fun charts(): List<StreamTrack> =
        apiCall { deezer.chart() }.tracks.data.mapNotNull { it.toModel() }

    override suspend fun search(query: String): List<StreamTrack> {
        val fromDeezer = runCatching { apiCall { deezer.search(query) }.data.mapNotNull { it.toModel() } }
        fromDeezer.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        return try {
            apiCall { itunes.search(query, country) }.results.mapNotNull { t ->
                val preview = t.previewUrl ?: return@mapNotNull null
                StreamTrack(
                    id = (t.trackId ?: preview.hashCode().toLong()).toString(),
                    source = StreamSource.ITunes,
                    title = t.trackName.orEmpty(),
                    artist = t.artistName.orEmpty(),
                    album = t.collectionName,
                    // iTunes serves any size by rewriting the dimensions in the URL.
                    artworkUrl = t.artworkUrl100?.replace("100x100bb", "600x600bb"),
                    durationMs = t.trackTimeMillis ?: 0L,
                    streamUrl = preview,
                )
            }
        } catch (e: SpotineilException) {
            fromDeezer.getOrNull() ?: throw e
        }
    }

    private fun DeezerTrackDto.toModel(): StreamTrack? {
        val url = preview?.takeIf { it.isNotBlank() && readable } ?: return null
        return StreamTrack(
            id = id.toString(),
            source = StreamSource.Deezer,
            title = title,
            artist = artist.name,
            album = album.title,
            artworkUrl = album.coverXl ?: album.coverMedium,
            durationMs = duration * 1000L,
            streamUrl = url,
        )
    }
}
