package com.spotineil.app.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface UpdateUi {
    data object Hidden : UpdateUi
    data class Available(val update: AppUpdate) : UpdateUi
    /** The user has to allow installing updates once in Settings. */
    data class NeedsPermission(val update: AppUpdate) : UpdateUi
    /** [progress] is 0f..1f, or -1f when the size is not known. */
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateUi
    data class Failed(val update: AppUpdate) : UpdateUi
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Hidden)
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    /** Screens to open: Settings (allow installs) or Android's installer. */
    private val _launch = Channel<Intent>(Channel.BUFFERED)
    val launch = _launch.receiveAsFlow()

    private var job: Job? = null

    init {
        // Once each time the app is opened. A failed check (offline) is silently ignored.
        viewModelScope.launch {
            val update = runCatching { repository.checkForUpdate() }.getOrNull()
            if (update != null) _state.value = UpdateUi.Available(update)
        }
    }

    /** The user tapped "Update". */
    fun accept() {
        val update = currentUpdate() ?: return
        if (!repository.canInstall()) {
            _state.value = UpdateUi.NeedsPermission(update)
            return
        }
        download(update)
    }

    fun openInstallSettings() {
        _launch.trySend(repository.allowInstallIntent())
    }

    /** Called when the app comes back to the front, e.g. after the Settings page. */
    fun onResumed() {
        val s = _state.value
        if (s is UpdateUi.NeedsPermission && repository.canInstall()) download(s.update)
    }

    /** "Later" / cancel: nothing is downloaded; the question comes back the next time the app opens. */
    fun dismiss() {
        job?.cancel()
        _state.value = UpdateUi.Hidden
    }

    private fun currentUpdate(): AppUpdate? = when (val s = _state.value) {
        is UpdateUi.Available -> s.update
        is UpdateUi.NeedsPermission -> s.update
        is UpdateUi.Downloading -> s.update
        is UpdateUi.Failed -> s.update
        UpdateUi.Hidden -> null
    }

    private fun download(update: AppUpdate) {
        job?.cancel()
        _state.value = UpdateUi.Downloading(update, 0f)
        job = viewModelScope.launch {
            try {
                val apk = repository.download(update) { p -> _state.value = UpdateUi.Downloading(update, p) }
                _launch.trySend(repository.installIntent(apk))
                _state.value = UpdateUi.Hidden
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUi.Failed(update)
            }
        }
    }
}
