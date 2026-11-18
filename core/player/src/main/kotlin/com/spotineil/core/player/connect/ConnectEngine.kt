package com.spotineil.core.player.connect

import com.spotineil.core.common.ApplicationScope
import com.spotineil.core.data.repository.AuthRepository
import com.spotineil.core.data.repository.ConnectRepository
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.model.SpotineilException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mirrors Spotify Connect state from the Web API by polling `/me/player` while the app is
 * in the foreground and App Remote isn't available (e.g. controlling a speaker or desktop).
 */
@Singleton
class ConnectEngine @Inject constructor(
    private val repository: ConnectRepository,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling() {
        // Without a Spotify login (the free app) there is nothing to poll.
        if (pollJob?.isActive == true || !auth.isLoggedInNow()) return
        pollJob = scope.launch {
            while (isActive) {
                val ok = refresh()
                delay(if (ok) POLL_MS else BACKOFF_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Re-reads state shortly after a command so the UI reflects it. */
    fun refreshSoon() {
        scope.launch { delay(400); refresh() }
    }

    suspend fun refresh(): Boolean = if (!auth.isLoggedInNow()) false else try {
        _state.value = repository.playbackState()
        true
    } catch (e: SpotineilException.Unauthorized) {
        stopPolling(); false
    } catch (e: SpotineilException) {
        false
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val BACKOFF_MS = 30_000L
    }
}
