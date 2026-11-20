package com.spotineil.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.spotineil.core.data.repository.SearchRepository
import com.spotineil.core.model.Category
import com.spotineil.core.model.RecentSearch
import com.spotineil.core.model.SearchResult
import com.spotineil.core.model.SearchResults
import com.spotineil.core.model.SearchType
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.Track

sealed interface SearchUiState {
    data class Idle(val recents: List<RecentSearch>, val categories: List<Category>) : SearchUiState
    data object Loading : SearchUiState
    data class Results(val query: String, val results: SearchResults) : SearchUiState
    data class Empty(val query: String) : SearchUiState
    data class Error(val error: Throwable) : SearchUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val player: PlaybackController,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** `null` = "All" (top results across types). */
    private val _filter = MutableStateFlow<SearchType?>(null)
    val filter: StateFlow<SearchType?> = _filter.asStateFlow()

    private val retryTrigger = MutableStateFlow(0)

    private val categories = MutableStateFlow<List<Category>>(emptyList())

    private val debouncedQuery: Flow<String> = _query
        .map { it.trim() }
        .debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }
        .distinctUntilChanged()

    val uiState: StateFlow<SearchUiState> = combine(debouncedQuery, retryTrigger) { q, _ -> q }
        .flatMapLatest { q ->
            if (q.isEmpty()) {
                combine(repository.recentSearches, categories) { r, c -> SearchUiState.Idle(r, c) }
            } else {
                flow<SearchUiState> {
                    val state = try {
                        val results = repository.search(q, SearchType.entries.toSet())
                        if (results.isEmpty) SearchUiState.Empty(q) else SearchUiState.Results(q, results)
                    } catch (e: Exception) {
                        SearchUiState.Error(e)
                    }
                    emit(state)
                }.onStart { emit(SearchUiState.Loading) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle(emptyList(), emptyList()))

    /** Fully-paged results for the selected filter chip. */
    val pagedResults: Flow<PagingData<SearchResult>> = combine(debouncedQuery, _filter) { q, f -> q to f }
        .flatMapLatest { (q, f) -> if (q.isEmpty() || f == null) emptyFlow() else repository.searchPaged(q, f) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            // Browse categories may be restricted for new apps: just hide the grid on failure.
            categories.value = runCatching { repository.categories() }.getOrDefault(emptyList())
        }
    }

    fun onQueryChange(value: String) { _query.value = value }
    fun onFilterChange(type: SearchType?) { _filter.value = type }
    fun retry() { retryTrigger.value++ }

    fun commitQuery(value: String = _query.value) {
        viewModelScope.launch { repository.addRecentSearch(value) }
    }

    fun removeRecent(query: String) = viewModelScope.launch { repository.removeRecentSearch(query) }
    fun clearRecents() = viewModelScope.launch { repository.clearRecentSearches() }

    fun play(uri: String) {
        commitQuery()
        if (uri.startsWith("ytmusic:video:")) {
            val id = uri.substringAfter("ytmusic:video:")
            player.playMedia(
                listOf(
                    PlayableMedia(
                        mediaId = "innertube:$id",
                        uri = uri,
                        title = "",
                        artist = "",
                    ),
                ),
                startIndex = 0,
            )
        } else {
            player.play(PlayRequest(uri = uri))
        }
    }

    fun playTrack(track: Track) {
        commitQuery()
        player.playMedia(
            listOf(
                PlayableMedia(
                    mediaId = "innertube:${track.id}",
                    uri = track.uri,
                    title = track.name,
                    artist = track.artistNames,
                    album = track.album?.name,
                    artworkUrl = track.images.firstOrNull()?.url,
                    durationMs = track.durationMs,
                ),
            ),
            startIndex = 0,
        )
    }

    fun playContext(uri: String) {
        commitQuery()
        player.play(PlayRequest(contextUri = uri))
    }

    companion object {
        const val DEBOUNCE_MS = 350L
    }
}
