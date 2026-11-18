package com.spotineil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.HomeRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.model.HomeFeed
import com.spotineil.core.model.Product
import com.spotineil.core.model.User
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Greeting { Morning, Afternoon, Evening, Night }

fun greetingFor(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting.Morning
    in 12..17 -> Greeting.Afternoon
    in 18..22 -> Greeting.Evening
    else -> Greeting.Night
}

data class HomeUiState(
    val feed: HomeFeed? = null,
    val user: User? = null,
    val greeting: Greeting = Greeting.Morning,
    val isRefreshing: Boolean = false,
    val error: Throwable? = null,
) {
    val isInitialLoading: Boolean get() = feed == null && error == null
    val showFreeNotice: Boolean get() = user?.product == Product.Free
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val userRepository: UserRepository,
    private val player: PlaybackController,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<Throwable?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        homeRepository.feed,
        userRepository.currentUser,
        refreshing,
        error,
    ) { feed, user, isRefreshing, err ->
        HomeUiState(
            feed = feed,
            user = user,
            greeting = greetingFor(LocalTime.now().hour),
            isRefreshing = isRefreshing,
            // Cached content wins over an error: offline-first.
            error = if (feed == null) err else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init { refresh() }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            error.value = null
            runCatching { userRepository.refreshCurrentUser() }
            runCatching { homeRepository.refresh() }.onFailure { error.value = it }
            refreshing.value = false
        }
    }

    fun play(uri: String) = player.play(PlayRequest(uri = uri))

    fun playContext(contextUri: String) = player.play(PlayRequest(contextUri = contextUri))
}
