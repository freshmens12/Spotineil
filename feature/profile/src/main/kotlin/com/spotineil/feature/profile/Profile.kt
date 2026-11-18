package com.spotineil.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.spotineil.core.data.repository.StatsRepository
import com.spotineil.core.data.repository.UserRepository
import com.spotineil.core.designsystem.component.DetailScaffold
import com.spotineil.core.designsystem.component.ErrorState
import com.spotineil.core.designsystem.component.MediaCard
import com.spotineil.core.designsystem.component.SectionHeader
import com.spotineil.core.designsystem.component.SkeletonTrackRow
import com.spotineil.core.designsystem.component.TrackRow
import com.spotineil.core.designsystem.component.rememberImageUrl
import com.spotineil.core.model.Product
import com.spotineil.core.model.TimeRange
import com.spotineil.core.model.TopItems
import com.spotineil.core.model.User
import com.spotineil.core.player.PlayRequest
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** [userId] null = the logged-in user, which also shows listening stats. */
@Serializable
data class ProfileRoute(val userId: String? = null)

fun NavController.navigateToProfile(userId: String? = null) = navigate(ProfileRoute(userId))

fun NavGraphBuilder.profileScreen(onBack: () -> Unit, onArtistClick: (String) -> Unit) {
    composable<ProfileRoute> { ProfileRoute(onBack, onArtistClick) }
}

data class ProfileUiState(
    val user: User? = null,
    val isMe: Boolean = true,
    val range: TimeRange = TimeRange.ShortTerm,
    val top: TopItems? = null,
    val topLoading: Boolean = false,
    val topError: Throwable? = null,
    val error: Throwable? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val users: UserRepository,
    private val stats: StatsRepository,
    private val player: PlaybackController,
) : ViewModel() {
    private val requestedId = savedStateHandle.toRoute<ProfileRoute>().userId

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val topCache = mutableMapOf<TimeRange, TopItems>()
    private var topJob: Job? = null

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(error = null) }
        val me = users.cachedCurrentUser()
        val isMe = requestedId == null || requestedId == me?.id
        if (isMe && me != null) _state.update { it.copy(user = me) }
        runCatching { if (isMe) users.refreshCurrentUser() else users.user(requestedId!!) }
            .onSuccess { u -> _state.update { it.copy(user = u, isMe = isMe) } }
            .onFailure { e -> _state.update { it.copy(error = if (it.user == null) e else null, isMe = isMe) } }
        if (isMe) selectRange(_state.value.range)
    }

    fun selectRange(range: TimeRange) {
        _state.update { it.copy(range = range, top = topCache[range], topError = null) }
        if (topCache.containsKey(range)) return
        topJob?.cancel()
        topJob = viewModelScope.launch {
            _state.update { it.copy(topLoading = true) }
            runCatching { stats.top(range) }
                .onSuccess { t -> topCache[range] = t; _state.update { if (it.range == range) it.copy(top = t, topLoading = false) else it } }
                .onFailure { e -> _state.update { it.copy(topError = e, topLoading = false) } }
        }
    }

    fun playTrack(uri: String) = player.play(PlayRequest(uri = uri))
}

@Composable
fun ProfileRoute(onBack: () -> Unit, onArtistClick: (String) -> Unit, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user = state.user

    DetailScaffold(
        title = user?.displayName ?: user?.id.orEmpty(),
        imageUrl = user?.let { rememberImageUrl(it.images, 400) },
        imageShape = CircleShape,
        onBack = onBack,
        headerMeta = {
            if (user != null) {
                Text(
                    stringResource(R.string.profile_followers, NumberFormat.getIntegerInstance().format(user.followers)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.isMe && user.product != Product.Unknown) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(stringResource(if (user.product == Product.Premium) R.string.profile_premium else R.string.profile_free))
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
    ) {
        if (user == null && state.error != null) {
            item { ErrorState(state.error!!, onRetry = { viewModel.load() }) }
            return@DetailScaffold
        }
        if (!state.isMe) return@DetailScaffold

        item(key = "statsHeader") { SectionHeader(stringResource(R.string.profile_your_top)) }
        item(key = "range") {
            val ranges = TimeRange.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ranges.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = state.range == r,
                        onClick = { viewModel.selectRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                    ) {
                        Text(
                            stringResource(
                                when (r) {
                                    TimeRange.ShortTerm -> R.string.profile_range_4w
                                    TimeRange.MediumTerm -> R.string.profile_range_6m
                                    TimeRange.LongTerm -> R.string.profile_range_all
                                },
                            ),
                        )
                    }
                }
            }
        }
        val top = state.top
        when {
            top != null -> {
                item(key = "artistsHeader") { SectionHeader(stringResource(R.string.profile_top_artists)) }
                item(key = "artists") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(top.artists, key = { _, a -> a.id }) { i, a ->
                            MediaCard("${i + 1}. ${a.name}", null, rememberImageUrl(a.images, 300), { onArtistClick(a.id) }, size = 120.dp, circle = true)
                        }
                    }
                }
                item(key = "tracksHeader") { SectionHeader(stringResource(R.string.profile_top_tracks)) }
                top.tracks.forEachIndexed { i, t ->
                    item(key = "t$i:${t.id}") {
                        TrackRow(t.name, t.artistNames, onClick = { viewModel.playTrack(t.uri) }, index = i + 1, explicit = t.explicit)
                    }
                }
            }
            state.topError != null -> item { ErrorState(state.topError!!, onRetry = { viewModel.selectRange(state.range) }) }
            else -> items(6) { SkeletonTrackRow() }
        }
    }
}
