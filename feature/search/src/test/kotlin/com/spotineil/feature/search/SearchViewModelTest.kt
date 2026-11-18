package com.spotineil.feature.search

import androidx.paging.PagingData
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.spotineil.core.data.repository.SearchRepository
import com.spotineil.core.model.Category
import com.spotineil.core.model.RecentSearch
import com.spotineil.core.model.SearchResult
import com.spotineil.core.model.SearchResults
import com.spotineil.core.model.SearchType
import com.spotineil.core.model.SpotineilException
import com.spotineil.core.testing.FakePlaybackController
import com.spotineil.core.testing.MainDispatcherRule
import com.spotineil.core.testing.TestData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FakeSearchRepository : SearchRepository {
    val searchedQueries = mutableListOf<String>()
    var failNext = false
    var results = SearchResults(tracks = listOf(TestData.track(1)))
    private val recents = MutableStateFlow<List<String>>(emptyList())

    override val recentSearches: Flow<List<RecentSearch>> = recents.map { l -> l.mapIndexed { i, q -> RecentSearch(q, i.toLong()) } }
    override suspend fun addRecentSearch(query: String) { recents.value = (listOf(query.trim()) + recents.value).distinct() }
    override suspend fun removeRecentSearch(query: String) { recents.value = recents.value - query }
    override suspend fun clearRecentSearches() { recents.value = emptyList() }

    override suspend fun search(query: String, types: Set<SearchType>, limit: Int): SearchResults {
        searchedQueries += query
        if (failNext) { failNext = false; throw SpotineilException.Network() }
        return results
    }

    override fun searchPaged(query: String, type: SearchType): Flow<PagingData<SearchResult>> = flowOf(PagingData.empty())
    override suspend fun categories(): List<Category> = emptyList()
}

class SearchViewModelTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val repo = FakeSearchRepository()
    private val player = FakePlaybackController()

    private suspend fun ReceiveTurbine<SearchUiState>.awaitUntil(predicate: (SearchUiState) -> Boolean): SearchUiState {
        while (true) { val item = awaitItem(); if (predicate(item)) return item }
    }

    @Test
    fun `typing is debounced so only the final query hits the API`() = runTest {
        val vm = SearchViewModel(repo, player)
        vm.uiState.test {
            vm.onQueryChange("d")
            advanceTimeBy(100)
            vm.onQueryChange("de")
            advanceTimeBy(100)
            vm.onQueryChange("debug")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)

            val state = awaitUntil { it is SearchUiState.Results } as SearchUiState.Results
            assertEquals("debug", state.query)
            assertEquals(listOf("debug"), repo.searchedQueries)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty results produce the Empty state`() = runTest {
        repo.results = SearchResults()
        val vm = SearchViewModel(repo, player)
        vm.uiState.test {
            vm.onQueryChange("nothing")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            assertEquals(SearchUiState.Empty("nothing"), awaitUntil { it is SearchUiState.Empty })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `errors are shown and retry re-runs the query`() = runTest {
        repo.failNext = true
        val vm = SearchViewModel(repo, player)
        vm.uiState.test {
            vm.onQueryChange("offline")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            val error = awaitUntil { it is SearchUiState.Error } as SearchUiState.Error
            assertTrue(error.error is SpotineilException.Network)

            vm.retry()
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            awaitUntil { it is SearchUiState.Results }
            assertEquals(listOf("offline", "offline"), repo.searchedQueries)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing the query returns to recents and playing commits the query`() = runTest {
        val vm = SearchViewModel(repo, player)
        vm.uiState.test {
            vm.onQueryChange("focus")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 1)
            awaitUntil { it is SearchUiState.Results }

            vm.play(TestData.track(1).uri)
            vm.onQueryChange("")

            val idle = awaitUntil { it is SearchUiState.Idle && it.recents.isNotEmpty() } as SearchUiState.Idle
            assertEquals(listOf("focus"), idle.recents.map { it.query })
            assertEquals(TestData.track(1).uri, player.playRequests.single().uri)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
