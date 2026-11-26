package com.spotineil.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spotineil.core.designsystem.theme.SpotineilTheme
import com.spotineil.core.model.LyricLine
import com.spotineil.core.model.Lyrics
import com.spotineil.core.model.PlaybackRestrictions
import com.spotineil.core.model.PlaybackSource
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerUiTest {

    @get:Rule val compose = createComposeRule()

    private fun uiState(isPlaying: Boolean = true, freeNotice: Boolean = false, restrictions: PlaybackRestrictions = PlaybackRestrictions()) =
        PlayerUiState(
            playback = PlaybackState(
                source = PlaybackSource.SpotifyAppRemote,
                track = TestData.nowPlaying,
                isPlaying = isPlaying,
                restrictions = restrictions,
            ),
            positionMs = 50_000,
            isLiked = false,
            showFreeNotice = freeNotice,
        )

    @Test
    fun miniPlayerShowsTrackAndTogglesPlayback() {
        val calls = mutableListOf<String>()
        compose.setContent {
            TestTheme {
                MiniPlayer(uiState(), PlayerActions(onTogglePlay = { calls += "toggle" }, onExpand = { calls += "expand" }))
            }
        }
        compose.onNodeWithText("Test Track 1").assertIsDisplayed()
        compose.onNodeWithTag("mini_play_pause").performClick()
        compose.onNodeWithTag("mini_player").performClick()
        assertEquals(listOf("toggle", "expand"), calls)
    }

    @Test
    fun fullPlayerControlsInvokeActions() {
        val calls = mutableListOf<String>()
        compose.setContent {
            TestTheme {
                FullPlayer(
                    state = uiState(isPlaying = false),
                    actions = PlayerActions(
                        onTogglePlay = { calls += "toggle" },
                        onNext = { calls += "next" },
                        onLike = { calls += "like" },
                        onToggleLyrics = { calls += "lyrics" },
                    ),
                    showLyrics = false,
                    lyricsContent = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithTag("full_play_pause").performClick()
        compose.onNodeWithTag("next_button").performClick()
        compose.onNodeWithTag("like_button").performClick()
        compose.onNodeWithTag("lyrics_toggle").performClick()
        assertEquals(listOf("toggle", "next", "like", "lyrics"), calls)
    }

    @Test
    fun freeTierNoticeIsShownForFreeAccounts() {
        compose.setContent {
            TestTheme { FullPlayer(uiState(freeNotice = true), PlayerActions(), showLyrics = false, lyricsContent = {}) }
        }
        compose.onNodeWithText("You're on Spotify Free", substring = true).assertIsDisplayed()
    }

    @Test
    fun restrictedSkipIsDisabled() {
        val calls = mutableListOf<String>()
        compose.setContent {
            TestTheme {
                FullPlayer(
                    uiState(restrictions = PlaybackRestrictions(canSkipNext = false)),
                    PlayerActions(onNext = { calls += "next" }),
                    showLyrics = false,
                    lyricsContent = {},
                )
            }
        }
        compose.onNodeWithTag("next_button").performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun tappingASyncedLyricLineSeeks() {
        val seeks = mutableListOf<Long>()
        val lyrics = Lyrics(
            lines = listOf(LyricLine(1_000, "placeholder line one"), LyricLine(4_000, "placeholder line two")),
            isSynced = true,
        )
        compose.setContent {
            TestTheme { LyricsPanel(LyricsUiState.Loaded(lyrics), positionMs = 1_500, onSeek = { seeks += it }) }
        }
        compose.onNodeWithText("placeholder line two").performClick()
        assertEquals(listOf(4_000L), seeks)
    }

    @Test
    fun lyricsNotAvailableState() {
        compose.setContent { TestTheme { LyricsPanel(LyricsUiState.NotAvailable, 0, {}) } }
        compose.onNodeWithText("Lyrics not available").assertIsDisplayed()
    }
}

/** The app theme with the living cover still: an endless animation would keep the test from ever settling. */
@androidx.compose.runtime.Composable
private fun TestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    SpotineilTheme {
        androidx.compose.runtime.CompositionLocalProvider(com.spotineil.core.ui.LocalAnimatedCovers provides false, content = content)
    }
}
