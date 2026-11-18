package com.spotineil.core.data.repository

import com.spotineil.core.data.db.LibraryDao
import com.spotineil.core.data.db.LibraryItemEntity
import com.spotineil.core.data.db.LibraryRemoteKeyDao
import com.spotineil.core.model.LibraryItemType
import com.spotineil.core.model.Playlist
import com.spotineil.core.model.bestFor
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.model.AddTracksBody
import com.spotineil.core.network.model.CreatePlaylistBody
import com.spotineil.core.network.model.RemoveTracksBody
import com.spotineil.core.network.model.ReorderTracksBody
import com.spotineil.core.network.model.UpdatePlaylistBody
import com.spotineil.core.network.model.UriRef
import com.spotineil.core.network.model.toModel
import javax.inject.Inject
import javax.inject.Singleton

interface PlaylistRepository {
    suspend fun create(name: String, description: String?, public: Boolean, collaborative: Boolean): Playlist
    suspend fun updateDetails(
        id: String,
        name: String? = null,
        description: String? = null,
        public: Boolean? = null,
        collaborative: Boolean? = null,
    )
    /** Returns the new snapshot id. */
    suspend fun addTracks(id: String, uris: List<String>): String
    suspend fun removeTrack(id: String, uri: String, position: Int, snapshotId: String?): String
    /** Moves the item at [from] so it ends up at index [to]. */
    suspend fun move(id: String, from: Int, to: Int, snapshotId: String?): String

    /** Playlists the current user may add tracks to (owned or collaborative). */
    suspend fun editablePlaylists(): List<Playlist>
}

@Singleton
class DefaultPlaylistRepository @Inject constructor(
    private val api: SpotifyApi,
    private val userRepository: UserRepository,
    private val libraryDao: LibraryDao,
    private val keyDao: LibraryRemoteKeyDao,
) : PlaylistRepository {

    override suspend fun create(name: String, description: String?, public: Boolean, collaborative: Boolean): Playlist {
        val me = userRepository.cachedCurrentUser() ?: userRepository.refreshCurrentUser()
        // Spotify requires collaborative playlists to be non-public.
        val body = CreatePlaylistBody(name, description, public = public && !collaborative, collaborative = collaborative)
        val playlist = apiCall { api.createPlaylist(me.id, body) }.toModel()
        val top = libraryDao.minPosition(LibraryItemType.Playlist.name) - 1
        libraryDao.upsertAll(
            listOf(
                LibraryItemEntity(
                    type = LibraryItemType.Playlist.name,
                    id = playlist.id,
                    uri = playlist.uri,
                    name = playlist.name,
                    subtitle = "Playlist • ${me.displayName.orEmpty()}",
                    creator = me.displayName.orEmpty(),
                    imageUrl = playlist.images.bestFor(300),
                    addedAt = System.currentTimeMillis(),
                    position = top,
                ),
            ),
        )
        return playlist
    }

    override suspend fun updateDetails(id: String, name: String?, description: String?, public: Boolean?, collaborative: Boolean?) {
        val safePublic = if (collaborative == true) false else public
        apiCall { api.updatePlaylist(id, UpdatePlaylistBody(name, description, safePublic, collaborative)) }
        if (name != null) libraryDao.rename(LibraryItemType.Playlist.name, id, name)
    }

    override suspend fun addTracks(id: String, uris: List<String>): String {
        var snapshot = ""
        // Max 100 URIs per request.
        uris.chunked(100).forEach { chunk -> snapshot = apiCall { api.addToPlaylist(id, AddTracksBody(chunk)) }.snapshotId }
        keyDao.delete(LibraryItemType.Playlist.name) // track counts / covers changed
        return snapshot
    }

    override suspend fun removeTrack(id: String, uri: String, position: Int, snapshotId: String?): String =
        apiCall {
            api.removeFromPlaylist(id, RemoveTracksBody(listOf(UriRef(uri, positions = listOf(position))), snapshotId))
        }.snapshotId

    override suspend fun move(id: String, from: Int, to: Int, snapshotId: String?): String {
        // Web API semantics: insert_before is an index in the *original* list.
        val insertBefore = if (to > from) to + 1 else to
        return apiCall { api.reorderPlaylist(id, ReorderTracksBody(from, insertBefore, 1, snapshotId)) }.snapshotId
    }

    override suspend fun editablePlaylists(): List<Playlist> {
        val me = userRepository.cachedCurrentUser() ?: userRepository.refreshCurrentUser()
        val result = mutableListOf<Playlist>()
        var offset = 0
        while (offset < MAX_SCAN) {
            val page = apiCall { api.myPlaylists(limit = 50, offset = offset) }
            result += page.items.map { it.toModel() }.filter { it.owner.id == me.id || it.collaborative }
            if (page.next == null || page.items.isEmpty()) break
            offset += page.items.size
        }
        return result
    }

    private companion object { const val MAX_SCAN = 500 }
}
