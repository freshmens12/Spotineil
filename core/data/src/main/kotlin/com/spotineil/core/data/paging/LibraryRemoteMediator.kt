package com.spotineil.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.spotineil.core.data.db.LibraryItemEntity
import com.spotineil.core.data.db.LibraryRemoteKeyEntity
import com.spotineil.core.data.db.SpotineilDatabase
import com.spotineil.core.model.LibraryItemType
import com.spotineil.core.model.bestFor
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.model.toModel
import com.spotineil.core.network.model.toModels
import java.time.Instant

/**
 * Offline-first library: Room is the single source of truth for the Library tabs; this
 * mediator pages from the Web API into Room.
 */
@OptIn(ExperimentalPagingApi::class)
class LibraryRemoteMediator(
    private val type: LibraryItemType,
    private val api: SpotifyApi,
    private val db: SpotineilDatabase,
    private val clock: Clock,
) : RemoteMediator<Int, LibraryItemEntity>() {

    private val dao = db.libraryDao()
    private val keyDao = db.libraryRemoteKeyDao()

    override suspend fun initialize(): InitializeAction {
        val key = keyDao.get(type.name) ?: return InitializeAction.LAUNCH_INITIAL_REFRESH
        return if (clock.now() - key.lastUpdated < CACHE_TTL_MS) {
            InitializeAction.SKIP_INITIAL_REFRESH
        } else {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        }
    }

    override suspend fun load(loadType: LoadType, state: PagingState<Int, LibraryItemEntity>): MediatorResult {
        val key: String? = when (loadType) {
            LoadType.REFRESH -> null
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> keyDao.get(type.name)?.nextKey
                ?: return MediatorResult.Success(endOfPaginationReached = true)
        }
        return try {
            val (items, nextKey) = apiCall { fetch(key) }
            db.withTransaction {
                if (loadType == LoadType.REFRESH) dao.clearType(type.name)
                val base = dao.maxPosition(type.name) + 1
                dao.upsertAll(items.mapIndexed { i, item -> item.copy(position = base + i) })
                keyDao.upsert(LibraryRemoteKeyEntity(type.name, nextKey, clock.now()))
            }
            MediatorResult.Success(endOfPaginationReached = nextKey == null)
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun fetch(key: String?): Pair<List<LibraryItemEntity>, String?> {
        val offset = key?.toIntOrNull() ?: 0
        return when (type) {
            LibraryItemType.Playlist -> {
                val page = api.myPlaylists(PAGE_SIZE, offset)
                page.items.map {
                    val p = it.toModel()
                    entity(p.id, p.uri, p.name, "Playlist • ${p.owner.displayName.orEmpty()}",
                        p.owner.displayName.orEmpty(), p.images.bestFor(300), 0)
                } to page.next?.let { (offset + page.items.size).toString() }
            }
            LibraryItemType.Album -> {
                val page = api.savedAlbums(PAGE_SIZE, offset)
                page.items.map {
                    val a = it.album.toModel()
                    val artists = a.artists.joinToString { ar -> ar.name }
                    entity(a.id, a.uri, a.name, "Album • $artists", artists, a.images.bestFor(300), it.addedAt.epoch())
                } to page.next?.let { (offset + page.items.size).toString() }
            }
            LibraryItemType.Artist -> {
                val page = api.followedArtists(limit = PAGE_SIZE, after = key).artists
                page.items.map {
                    val a = it.toModel()
                    entity(a.id, a.uri, a.name, "Artist", a.name, a.images.bestFor(300), 0)
                } to page.next?.let { page.cursors?.after }
            }
            LibraryItemType.Show -> {
                val page = api.savedShows(PAGE_SIZE, offset)
                page.items.map {
                    val s = it.show.toModel()
                    entity(s.id, s.uri, s.name, "Podcast • ${s.publisher}", s.publisher,
                        it.show.images.toModels().bestFor(300), it.addedAt.epoch())
                } to page.next?.let { (offset + page.items.size).toString() }
            }
        }
    }

    private fun entity(
        id: String, uri: String, name: String, subtitle: String, creator: String, image: String?, addedAt: Long,
    ) = LibraryItemEntity(type.name, id, uri, name, subtitle, creator, image, addedAt, position = 0)

    private fun String?.epoch(): Long = this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    companion object {
        const val PAGE_SIZE = 50
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }
}
