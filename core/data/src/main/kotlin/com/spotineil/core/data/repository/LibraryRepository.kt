package com.spotineil.core.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.spotineil.core.data.db.LibraryItemEntity
import com.spotineil.core.data.db.SpotineilDatabase
import com.spotineil.core.data.paging.LibraryRemoteMediator
import com.spotineil.core.data.paging.offsetPager
import com.spotineil.core.model.LibraryItem
import com.spotineil.core.model.LibraryItemType
import com.spotineil.core.model.LibrarySort
import com.spotineil.core.model.Track
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.model.IdsBody
import com.spotineil.core.network.model.toModel
import com.spotineil.core.network.model.toPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface LibraryRepository {
    fun items(type: LibraryItemType, sort: LibrarySort): Flow<PagingData<LibraryItem>>
    fun likedSongs(): Flow<PagingData<Track>>
    suspend fun likedSongsCount(): Int

    suspend fun areTracksSaved(ids: List<String>): List<Boolean>
    suspend fun setTrackSaved(id: String, saved: Boolean)
    suspend fun isAlbumSaved(id: String): Boolean
    suspend fun setAlbumSaved(id: String, saved: Boolean)
    suspend fun isFollowingArtist(id: String): Boolean
    suspend fun setFollowingArtist(id: String, follow: Boolean)
    suspend fun setFollowingPlaylist(id: String, follow: Boolean)
}

@Singleton
class DefaultLibraryRepository @Inject constructor(
    private val api: SpotifyApi,
    private val db: SpotineilDatabase,
    private val clock: Clock,
) : LibraryRepository {

    private val dao = db.libraryDao()
    private val keyDao = db.libraryRemoteKeyDao()

    @OptIn(ExperimentalPagingApi::class)
    override fun items(type: LibraryItemType, sort: LibrarySort): Flow<PagingData<LibraryItem>> = Pager(
        config = PagingConfig(pageSize = LibraryRemoteMediator.PAGE_SIZE, enablePlaceholders = false),
        remoteMediator = LibraryRemoteMediator(type, api, db, clock),
        pagingSourceFactory = {
            when (sort) {
                LibrarySort.Recent -> dao.pagingByRecent(type.name)
                LibrarySort.Alphabetical -> dao.pagingByName(type.name)
                LibrarySort.Creator -> dao.pagingByCreator(type.name)
            }
        },
    ).flow.map { data -> data.map { it.toModel() } }

    override fun likedSongs(): Flow<PagingData<Track>> = offsetPager(pageSize = 50) { offset, limit ->
        api.savedTracks(limit, offset).toPage { it.track.toModel() }
    }

    override suspend fun likedSongsCount(): Int = apiCall { api.savedTracks(1, 0) }.total

    override suspend fun areTracksSaved(ids: List<String>): List<Boolean> {
        if (ids.isEmpty()) return emptyList()
        // Endpoint accepts at most 50 ids per call.
        return ids.chunked(50).flatMap { chunk -> apiCall { api.containsSavedTracks(chunk.joinToString(",")) } }
    }

    override suspend fun setTrackSaved(id: String, saved: Boolean) = apiCall {
        if (saved) api.saveTracks(IdsBody(listOf(id))) else api.removeTracks(IdsBody(listOf(id)))
    }

    override suspend fun isAlbumSaved(id: String): Boolean =
        apiCall { api.containsSavedAlbums(id) }.firstOrNull() ?: false

    override suspend fun setAlbumSaved(id: String, saved: Boolean) {
        apiCall { if (saved) api.saveAlbums(IdsBody(listOf(id))) else api.removeAlbums(IdsBody(listOf(id))) }
        invalidate(LibraryItemType.Album, id, removed = !saved)
    }

    override suspend fun isFollowingArtist(id: String): Boolean =
        apiCall { api.isFollowingArtists(ids = id) }.firstOrNull() ?: false

    override suspend fun setFollowingArtist(id: String, follow: Boolean) {
        apiCall { if (follow) api.followArtists(body = IdsBody(listOf(id))) else api.unfollowArtists(body = IdsBody(listOf(id))) }
        invalidate(LibraryItemType.Artist, id, removed = !follow)
    }

    override suspend fun setFollowingPlaylist(id: String, follow: Boolean) {
        apiCall { if (follow) api.followPlaylist(id) else api.unfollowPlaylist(id) }
        invalidate(LibraryItemType.Playlist, id, removed = !follow)
    }

    /** Removals apply instantly; additions force a refresh the next time the tab loads. */
    private suspend fun invalidate(type: LibraryItemType, id: String, removed: Boolean) {
        if (removed) dao.delete(type.name, id)
        keyDao.delete(type.name)
    }
}

internal fun LibraryItemEntity.toModel() = LibraryItem(
    id = id,
    uri = uri,
    type = LibraryItemType.valueOf(type),
    name = name,
    subtitle = subtitle,
    creator = creator,
    imageUrl = imageUrl,
    addedAt = addedAt,
)
