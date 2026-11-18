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
            val dto = api.search(query, type.apiValue, limit, offset)
            when (type) {
                SearchType.Track -> dto.tracks.page { SearchResult.TrackResult(it.toModel()) }
                SearchType.Artist -> dto.artists.page { SearchResult.ArtistResult(it.toModel()) }
                SearchType.Album -> dto.albums.page { SearchResult.AlbumResult(it.toModel()) }
                SearchType.Playlist -> dto.playlists.page { SearchResult.PlaylistResult(it.toModel()) }
                SearchType.Show -> dto.shows.page { SearchResult.ShowResult(it.toModel()) }
            }
        }

    override suspend fun categories(): List<Category> =
        apiCall { api.categories(limit = 50) }.categories.items.map { it.toModel() }

    private fun <T : Any> PagingDto<T?>?.page(transform: (T) -> SearchResult): Page<SearchResult> =
        this?.toPage { it?.let(transform) } ?: Page(emptyList(), 0, 0, null)

    private companion object {
        const val MAX_RECENTS = 20
        const val PAGE_SIZE = 20
        const val SEARCH_MAX_OFFSET = 1000
    }
}
