package com.spotineil.feature.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotineil.core.designsystem.theme.SpotineilTheme
import com.spotineil.core.model.RecentSearch
import com.spotineil.core.model.SearchResults
import com.spotineil.core.model.SearchType
import com.spotineil.core.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchScreenTest {

    @get:Rule val compose = createComposeRule()

    private val noop = SearchCallbacks({}, {}, {}, {})

    private fun setContent(
        query: String,
        state: SearchUiState,
        filter: SearchType? = null,
        onQueryChange: (String) -> Unit = {},
        onFilterChange: (SearchType?) -> Unit = {},
        onPlay: (String) -> Unit = {},
    ) {
        compose.setContent {
            SpotineilTheme {
                SearchScreen(
                    query = query,
                    filter = filter,
                    state = state,
                    paged = null,
                    onQueryChange = onQueryChange,
                    onFilterChange = onFilterChange,
                    onSubmit = {},
                    onRetry = {},
                    onRemoveRecent = {},
                    onClearRecents = {},
                    onPlay = onPlay,
                    onPlayContext = {},
                    onOpen = {},
                    callbacks = noop,
                )
            }
        }
    }

    @Test
    fun typingForwardsTheQuery() {
        val typed = mutableListOf<String>()
        setContent("", SearchUiState.Idle(emptyList(), emptyList()), onQueryChange = { typed += it })
        compose.onNodeWithTag("search_field").performTextInput("debug")
        assertEquals("debug", typed.last())
    }

    @Test
    fun recentSearchesAreShownWhenIdle() {
        setContent("", SearchUiState.Idle(listOf(RecentSearch("lofi beats", 1)), emptyList()))
        compose.onNodeWithText("lofi beats").assertIsDisplayed()
    }

    @Test
    fun resultsShowTracksAndTappingPlays() {
        val played = mutableListOf<String>()
        val track = TestData.track(1)
        setContent("test", SearchUiState.Results("test", SearchResults(tracks = listOf(track))), onPlay = { played += it })

        compose.onNodeWithText(track.name).assertIsDisplayed()
        compose.onNodeWithTag("track_${track.id}").performClick()

        assertEquals(listOf(track.uri), played)
    }

    @Test
    fun filterChipSelectsType() {
        var selected: SearchType? = null
        setContent("test", SearchUiState.Results("test", SearchResults(tracks = listOf(TestData.track()))), onFilterChange = { selected = it })
        compose.onNodeWithTag("chip_Artist").performClick()
        assertEquals(SearchType.Artist, selected)
    }

    @Test
    fun emptyStateIsShown() {
        setContent("zzz", SearchUiState.Empty("zzz"))
        compose.onNodeWithText("No results for \"zzz\"").assertIsDisplayed()
    }
}
