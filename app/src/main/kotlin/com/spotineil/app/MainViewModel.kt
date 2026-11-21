package com.spotineil.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.AuthRepository
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.app.widget.WidgetLink
import com.spotineil.core.model.UserSettings
import com.spotineil.core.model.YOUTUBE_MEDIA_PREFIX
import com.spotineil.feature.discover.free.FreePlayer
import com.spotineil.core.player.PlaybackController
import com.spotineil.core.player.PlayerEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MainUiState {
    data object Loading : MainUiState
    data class Ready(val settings: UserSettings, val isLoggedIn: Boolean) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val player: PlaybackController,
    private val freePlayer: FreePlayer,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(settings.settings, auth.isLoggedIn) { s, loggedIn ->
        MainUiState.Ready(s, loggedIn)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState.Loading)

    val hasTrack: StateFlow<Boolean> = player.state.map { it.track != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val playerEvents: SharedFlow<PlayerEvent> = player.events

    fun isLoggedInNow(): Boolean = auth.isLoggedInNow()

    /** Welcome screen's "Get started": open the app (remembered, so Welcome shows only once). */
    fun finishWelcome() = viewModelScope.launch { settings.setGuestMode(true) }

    /** Screens a home-screen widget asked for; the navigation collects them. */
    private val _open = Channel<String>(Channel.BUFFERED)
    val openRequests = _open.receiveAsFlow()

    /** A widget opened the app on [destination] (one of [WidgetLink]). */
    fun openFromWidget(destination: String) {
        viewModelScope.launch {
            when (destination) {
                WidgetLink.RESUME -> {
                    // Nothing playing: bring back the last session, then show it.
                    val session = if (player.state.value.track == null) freePlayer.youTubeSession.first() else null
                    if (session != null) {
                        freePlayer.continueYouTube(session)
                        _open.send(WidgetLink.PLAYER)
                    } else if (player.state.value.track != null) {
                        _open.send(if (isPlayingYouTube()) WidgetLink.PLAYER else WidgetLink.EXPAND)
                    }
                }
                WidgetLink.PLAYER -> _open.send(if (isPlayingYouTube()) WidgetLink.PLAYER else WidgetLink.EXPAND)
                else -> _open.send(destination)
            }
        }
    }

    private fun isPlayingYouTube() = player.state.value.track?.uri?.startsWith(YOUTUBE_MEDIA_PREFIX) == true
}

@Composable
fun StateFlow<MainUiState>.collectAsStateValue(): MainUiState {
    val value by collectAsStateWithLifecycle()
    return value
}
