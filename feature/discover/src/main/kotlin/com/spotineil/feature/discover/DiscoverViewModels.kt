package com.spotineil.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotineil.core.data.repository.DownloadRepository
import com.spotineil.core.data.repository.FreeMusicRepository
import com.spotineil.core.data.repository.FreeMusicSections
import com.spotineil.core.data.repository.RadioRepository
import com.spotineil.core.model.RadioStation
import com.spotineil.core.model.StreamTrack
import com.spotineil.core.model.toPlayable
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Generic load state for the Discover tabs. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val data: T) : LoadState<T>
    data class Failed(val error: Throwable) : LoadState<Nothing>
}

internal const val SEARCH_DEBOUNCE_MS = 400L

/** query → debounced load, re-run on retry. */
internal fun <T> searchFlow(
    query: StateFlow<String>,
    retry: StateFlow<Int>,
    load: suspend (String) -> T,
): Flow<LoadState<T>> = combine(
    query.map { it.trim() }.debounce { if (it.isEmpty()) 0 else SEARCH_DEBOUNCE_MS }.distinctUntilChanged(),
    retry,
) { q, _ -> q }.flatMapLatest { q ->
    flow<LoadState<T>> {
        emit(
            try { LoadState.Loaded(load(q)) } catch (e: Exception) { LoadState.Failed(e) },
        )
    }.onStart { emit(LoadState.Loading) }
}

private fun PlaybackController.nowPlayingId(scope: kotlinx.coroutines.CoroutineScope): StateFlow<String?> =
    state.map { it.track?.uri }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

// ---------------------------------------------------------------------------------------------

@HiltViewModel
class FreeMusicViewModel @Inject constructor(
    private val repository: FreeMusicRepository,
    private val downloads: DownloadRepository,
    private val player: PlaybackController,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val retry = MutableStateFlow(0)

    val state: StateFlow<LoadState<FreeMusicSections>> =
        searchFlow(_query, retry) { q -> if (q.isEmpty()) repository.featured() else repository.search(q) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val nowPlayingId = player.nowPlayingId(viewModelScope)

    /** String resource ids for snackbar messages. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun onQueryChange(q: String) { _query.value = q }
    fun retry() { retry.value++ }

    fun play(list: List<StreamTrack>, index: Int) = player.playMedia(list.map { it.toPlayable() }, index)

    fun download(track: StreamTrack) {
        val ok = downloads.download(track)
        _messages.tryEmit(if (ok) R.string.discover_download_started else R.string.discover_download_failed)
    }
}

// ---------------------------------------------------------------------------------------------

data class RadioSections(
    val favorites: List<RadioStation> = emptyList(),
    val local: List<RadioStation> = emptyList(),
    val top: List<RadioStation> = emptyList(),
    /** Non-null when searching or filtering by tag. */
    val results: List<RadioStation>? = null,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val repository: RadioRepository,
    private val player: PlaybackController,
) : ViewModel() {

    val countryCode: String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
    val countryName: String = Locale("", countryCode).displayCountry

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _tag = MutableStateFlow<String?>(null)
    val tag: StateFlow<String?> = _tag.asStateFlow()
    private val retry = MutableStateFlow(0)

    val favorites: StateFlow<List<RadioStation>> =
        repository.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filter = MutableStateFlow("")

    val state: StateFlow<LoadState<RadioSections>> =
        searchFlow(filter, retry) { key ->
            when {
                key.startsWith(TAG_PREFIX) -> RadioSections(results = repository.byTag(key.removePrefix(TAG_PREFIX)))
                key.isNotEmpty() -> RadioSections(results = repository.search(key))
                else -> {
                    val local = runCatching { repository.byCountry(countryCode) }.getOrDefault(emptyList())
                    RadioSections(local = local, top = repository.top())
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val nowPlayingId = player.nowPlayingId(viewModelScope)

    fun onQueryChange(q: String) {
        _query.value = q
        _tag.value = null
        filter.value = q
    }

    fun selectTag(tag: String?) {
        _tag.value = tag
        _query.value = ""
        filter.value = tag?.let { TAG_PREFIX + it } ?: ""
    }

    fun retry() { retry.value++ }

    fun play(station: RadioStation) {
        player.playMedia(listOf(station.toPlayable()), 0)
        viewModelScope.launch { repository.reportPlay(station) }
    }

    fun toggleFavorite(station: RadioStation) = viewModelScope.launch {
        repository.setFavorite(station, favorite = favorites.value.none { it.id == station.id })
    }

    companion object {
        private const val TAG_PREFIX = "tag:"
        val TAGS = listOf("pop", "rock", "news", "jazz", "dance", "chillout", "hiphop", "classical", "opm", "talk")
    }
}
