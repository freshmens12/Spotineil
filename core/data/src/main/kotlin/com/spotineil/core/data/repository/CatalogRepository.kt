package com.spotineil.core.data.repository

import androidx.paging.PagingData
import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.data.paging.offsetPager
import com.spotineil.core.model.Album
import com.spotineil.core.model.AlbumDetails
import com.spotineil.core.model.AlbumType
import com.spotineil.core.model.Artist
import com.spotineil.core.model.ArtistDetails
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.PlaylistDetails
import com.spotineil.core.model.PlaylistTrack
import com.spotineil.core.model.SimpleAlbum
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.model.Track
import com.spotineil.core.model.isUnavailableFeature
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.model.toModel
import com.spotineil.core.network.model.toPlaylistTracks
import com.spotineil.core.model.Page
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer

interface CatalogRepository {
    /** Cached album, if any — lets detail screens render instantly offline. */
    suspend fun cachedAlbum(id: String): Album?
    suspend fun album(id: String): AlbumDetails

    suspend fun cachedArtist(id: String): Artist?
    suspend fun artist(id: String): ArtistDetails

    suspend fun cachedPlaylist(id: String): Playlist?
    suspend fun playlist(id: String, myUserId: String?): PlaylistDetails
    fun playlistTracks(id: String): Flow<PagingData<PlaylistTrack>>

    /** First page of playlist tracks, for "play" without paging the whole list. */
    suspend fun playlistTrackUris(id: String, limit: Int = 100): List<String>

    /** Loads up to [max] items eagerly (used by the reorder editor). */
    suspend fun allPlaylistTracks(id: String, max: Int = 500): List<PlaylistTrack>
}

@Singleton
class DefaultCatalogRepository @Inject constructor(
    private val api: SpotifyApi,
    private val cache: JsonCache,
    private val library: LibraryRepository,
) : CatalogRepository {

    override suspend fun cachedAlbum(id: String): Album? = cache.get("album:$id", Album.serializer())

    override suspend fun album(id: String): AlbumDetails = coroutineScope {
        val saved = async { optional { library.isAlbumSaved(id) } ?: false }
        var album = apiCall { api.album(id) }.toModel()
        // Albums embed the first 50 tracks; page through the rest.
        if (album.tracks.size < album.totalTracks) {
            val simple = SimpleAlbum(album.id, album.uri, album.name, album.images)
            val all = album.tracks.toMutableList()
            var offset = all.size
            while (offset < album.totalTracks) {
                val page = apiCall { api.albumTracks(id, limit = 50, offset = offset) }
                if (page.items.isEmpty()) break
                all += page.items.map { it.toModel(fallbackAlbum = simple) }
                offset += page.items.size
            }
            album = album.copy(tracks = all)
        }
        cache.put("album:$id", Album.serializer(), album)
        AlbumDetails(album, saved.await())
    }

    override suspend fun cachedArtist(id: String): Artist? = cache.get("artist:$id", Artist.serializer())

    override suspend fun artist(id: String): ArtistDetails = coroutineScope {
        val artist = async { apiCall { api.artist(id) }.toModel() }
        val top = async { optional { api.artistTopTracks(id).tracks.map { it.toModel() } } ?: emptyList() }
        val albums = async { optional { api.artistAlbums(id, "album", limit = 20).items.map { it.toModel() } } ?: emptyList() }
        val singles = async {
            optional { api.artistAlbums(id, "single,compilation", limit = 20).items.map { it.toModel() } } ?: emptyList()
        }
        val following = async { optional { library.isFollowingArtist(id) } ?: false }
        val details = ArtistDetails(
            artist = artist.await(),
            topTracks = top.await(),
            albums = albums.await(),
            singles = singles.await().filter { it.albumType != AlbumType.Album },
            isFollowing = following.await(),
        )
        cache.put("artist:$id", Artist.serializer(), details.artist)
        cache.put("artist_top:$id", ListSerializer(Track.serializer()), details.topTracks)
        details
    }

    override suspend fun cachedPlaylist(id: String): Playlist? = cache.get("playlist:$id", Playlist.serializer())

    override suspend fun playlist(id: String, myUserId: String?): PlaylistDetails {
        val playlist = apiCall { api.playlist(id) }.toModel()
        cache.put("playlist:$id", Playlist.serializer(), playlist)
        val isOwner = myUserId != null && playlist.owner.id == myUserId
        val following = isOwner || (myUserId != null &&
            (optional { api.isFollowingPlaylist(id, myUserId).firstOrNull() } ?: false))
        return PlaylistDetails(playlist = playlist, isOwnedByMe = isOwner, isFollowing = following)
    }

    override fun playlistTracks(id: String): Flow<PagingData<PlaylistTrack>> =
        offsetPager(pageSize = 50) { offset, limit ->
            val dto = api.playlistItems(id, limit, offset)
            Page(
                items = dto.toPlaylistTracks(),
                offset = dto.offset,
                total = dto.total,
                nextOffset = if (dto.next != null) offset + dto.items.size else null,
            )
        }

    override suspend fun playlistTrackUris(id: String, limit: Int): List<String> =
        apiCall { api.playlistItems(id, limit = minOf(limit, 100), offset = 0) }
            .toPlaylistTracks().map { it.track.uri }.filter { it.isNotBlank() }

    override suspend fun allPlaylistTracks(id: String, max: Int): List<PlaylistTrack> {
        val result = mutableListOf<PlaylistTrack>()
        var offset = 0
        while (offset < max) {
            val page = apiCall { api.playlistItems(id, limit = 100, offset = offset) }
            result += page.toPlaylistTracks()
            if (page.next == null || page.items.isEmpty()) break
            offset += page.items.size
        }
        return result
    }

    /** Swallows "restricted for this app" responses so one missing section doesn't break a page. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        apiCall(block)
    } catch (e: SpotineilException) {
        if (e.isUnavailableFeature) null else throw e
    }
}
