package com.spotineil.core.data.repository

import androidx.paging.PagingData
import com.spotineil.core.data.db.RecentSearchDao
import com.spotineil.core.data.db.RecentSearchEntity
import com.spotineil.core.data.paging.offsetPager
import com.spotineil.core.model.Category
import com.spotineil.core.model.Page
import com.spotineil.core.model.RecentSearch
import com.spotineil.core.model.SearchResult
import com.spotineil.core.model.SearchResults
import com.spotineil.core.model.SearchType
import com.spotineil.core.network.api.SpotifyApi
import com.spotineil.core.network.apiCall
import com.spotineil.core.network.auth.Clock
import com.spotineil.core.network.model.PagingDto
import com.spotineil.core.network.model.toModel
import com.spotineil.core.network.model.toPage
import com.spotineil.core.data.cache.JsonCache
import com.spotineil.core.data.innertube.InnerTubeCatalogRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SearchRepository {
    val recentSearches: Flow<List<RecentSearch>>
    suspend fun addRecentSearch(query: String)
    suspend fun removeRecentSearch(query: String)
    suspend fun clearRecentSearches()

    /** Top results across [types] (small limit), for the "All" tab. */
    suspend fun search(query: String, types: Set<SearchType>, limit: Int = 10): SearchResults

    /** Fully paginated results of a single type, for filter chips. */
    fun searchPaged(query: String, type: SearchType): Flow<PagingData<SearchResult>>

    suspend fun categories(): List<Category>
}

@Singleton
class DefaultSearchRepository @Inject constructor(
    private val api: SpotifyApi,
    private val recentDao: RecentSearchDao,
    private val clock: Clock,
    private val innerTubeCatalog: InnerTubeCatalogRepository,
    private val cache: JsonCache,
) : SearchRepository {

    override val recentSearches: Flow<List<RecentSearch>> =
        recentDao.observe(MAX_RECENTS).map { list -> list.map { RecentSearch(it.query, it.timestamp) } }

    override suspend fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        recentDao.upsert(RecentSearchEntity(q, clock.now()))
        recentDao.trim(MAX_RECENTS)
    }

    override suspend fun removeRecentSearch(query: String) = recentDao.delete(query)

    override suspend fun clearRecentSearches() = recentDao.clear()

    override suspend fun search(query: String, types: Set<SearchType>, limit: Int): SearchResults {
        val normalized = query.trim()
        val cacheKey = "innertube:search:${normalized.lowercase()}"
        cache.get(cacheKey, SearchResults.serializer(), 24 * 60 * 60 * 1000L)?.let { cached ->
            return filterSearchTypes(cached, types)
        }
        val youtube = innerTubeCatalog.search(normalized).getOrElse { failure ->
            cache.get(cacheKey, SearchResults.serializer())?.let { stale ->
                return filterSearchTypes(stale, types)
            }
            throw failure
        }
        cache.put(cacheKey, SearchResults.serializer(), youtube)
        return filterSearchTypes(youtube, types).let { if (limit <= 0) it else it.copy(
            tracks = it.tracks.take(limit),
            artists = it.artists.take(limit),
            albums = it.albums.take(limit),
            playlists = it.playlists.take(limit),
        ) }
    }

    private fun filterSearchTypes(results: SearchResults, types: Set<SearchType>): SearchResults = SearchResults(
        tracks = if (SearchType.Track in types) results.tracks else emptyList(),
        artists = if (SearchType.Artist in types) results.artists else emptyList(),
        albums = if (SearchType.Album in types) results.albums else emptyList(),
        playlists = if (SearchType.Playlist in types) results.playlists else emptyList(),
        shows = emptyList(),
    )

    private suspend fun spotifySearch(query: String, types: Set<SearchType>, limit: Int): SearchResults {
        val dto = apiCall { api.search(query, types.joinToString(",") { it.apiValue }, limit) }
        return SearchResults(
            tracks = dto.tracks?.items.orEmpty().filterNotNull().map { it.toModel() },
            artists = dto.artists?.items.orEmpty().filterNotNull().map { it.toModel() },
            albums = dto.albums?.items.orEmpty().filterNotNull().map { it.toModel() },
            playlists = dto.playlists?.items.orEmpty().filterNotNull().map { it.toModel() },
            shows = dto.shows?.items.orEmpty().filterNotNull().map { it.toModel() },
        )
    }

    override fun searchPaged(query: String, type: SearchType): Flow<PagingData<SearchResult>> =
        offsetPager(pageSize = PAGE_SIZE, maxOffset = SEARCH_MAX_OFFSET) { offset, limit ->
            val results = innerTubeCatalog.search(query.trim()).getOrThrow()
            when (type) {
                SearchType.Track -> paged(results.tracks.map { SearchResult.TrackResult(it) }, offset, limit)
                SearchType.Artist -> paged(results.artists.map { SearchResult.ArtistResult(it) }, offset, limit)
                SearchType.Album -> paged(results.albums.map { SearchResult.AlbumResult(it) }, offset, limit)
                SearchType.Playlist -> paged(results.playlists.map { SearchResult.PlaylistResult(it) }, offset, limit)
                SearchType.Show -> Page(emptyList(), offset, 0, null)
            }
        }

    override suspend fun categories(): List<Category> =
        apiCall { api.categories(limit = 50) }.categories.items.map { it.toModel() }

    private fun <T : Any> PagingDto<T?>?.page(transform: (T) -> SearchResult): Page<SearchResult> =
        this?.toPage { it?.let(transform) } ?: Page(emptyList(), 0, 0, null)

    private fun paged(items: List<SearchResult>, offset: Int, limit: Int): Page<SearchResult> {
        val end = (offset + limit).coerceAtMost(items.size)
        return Page(
            items = items.drop(offset).take(end - offset),
            offset = offset,
            total = items.size,
            nextOffset = if (end < items.size) end else null,
        )
    }

    private companion object {
        const val MAX_RECENTS = 20
        const val PAGE_SIZE = 20
        const val SEARCH_MAX_OFFSET = 1000
    }
}
