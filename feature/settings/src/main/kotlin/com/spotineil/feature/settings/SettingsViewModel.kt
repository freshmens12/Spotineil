package com.spotineil.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.spotineil.core.common.SpotifyAppConfig
import com.spotineil.core.data.maintenance.CacheManager
import com.spotineil.core.data.repository.AuthRepository
import com.spotineil.core.data.repository.SettingsRepository
import com.spotineil.core.data.spotifyweb.SpotifyWebAuth
import com.spotineil.core.model.AccentColor
import com.spotineil.core.model.AppLanguage
import com.spotineil.core.model.ThemeMode
import com.spotineil.core.model.TransitionMode
import com.spotineil.core.model.UserSettings
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val spotifyWeb: SpotifyWebAuth,
    private val backup: com.spotineil.core.data.repository.BackupRepository,
    private val auth: AuthRepository,
    private val cacheManager: CacheManager,
    private val player: PlaybackController,
    config: SpotifyAppConfig,
) : ViewModel() {

    val appVersion: String = config.appVersion

    val spotifyConnected: StateFlow<Boolean> = spotifyWeb.connected
    val spotifyName: StateFlow<String?> = spotifyWeb.accountName
    fun disconnectSpotify() = spotifyWeb.disconnect()

    private val _backupMessage = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** Result of a backup or restore, shown as a message. */
    val backupMessage: SharedFlow<String> = _backupMessage.asSharedFlow()

    fun exportBackup(target: android.net.Uri) = viewModelScope.launch {
        _backupMessage.tryEmit(
            runCatching { backup.export(target, appVersion) }
                .fold({ context.getString(R.string.settings_backup_done) }, { context.getString(R.string.settings_backup_failed) }),
        )
    }

    fun restoreBackup(source: android.net.Uri) = viewModelScope.launch {
        _backupMessage.tryEmit(
            runCatching { backup.restore(source) }
                .fold({ context.getString(R.string.settings_restore_done, it.liked, it.playlists) }, { context.getString(R.string.settings_restore_failed) }),
        )
    }

    val state: StateFlow<UserSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    private val _cacheCleared = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheCleared: SharedFlow<Unit> = _cacheCleared.asSharedFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAccent(accent: AccentColor) = viewModelScope.launch { settings.setAccent(accent) }
    fun setDataSaver(enabled: Boolean) = viewModelScope.launch { settings.setDataSaver(enabled) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settings.setLanguage(language) }
    fun setTransition(mode: TransitionMode) = viewModelScope.launch { settings.setTransition(mode) }
    fun setAudio(audio: com.spotineil.core.model.AudioSettings) = viewModelScope.launch { settings.setAudio(audio) }
    fun setDownloads(downloads: com.spotineil.core.model.DownloadSettings) = viewModelScope.launch { settings.setDownloads(downloads) }

    @OptIn(ExperimentalCoilApi::class)
    fun clearCache() = viewModelScope.launch {
        cacheManager.clearAll(includeUserData = false)
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
        _cacheCleared.tryEmit(Unit)
    }

    val isLoggedIn: StateFlow<Boolean> = auth.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), auth.isLoggedInNow())

    fun logout() = viewModelScope.launch {
        player.disconnectSpotify()
        // Back to the free app (no account needed).
        settings.setGuestMode(true)
        auth.logout()
    }
}
