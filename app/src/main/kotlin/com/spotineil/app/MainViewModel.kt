package com.spotineil.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.AuthRepository
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.core.model.UserSettings
import com.spotineil.core.player.PlaybackController
import com.spotineil.core.player.PlayerEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
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
    player: PlaybackController,
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
}

@Composable
fun StateFlow<MainUiState>.collectAsStateValue(): MainUiState {
    val value by collectAsStateWithLifecycle()
    return value
}
