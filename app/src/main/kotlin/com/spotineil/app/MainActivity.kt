package com.spotineil.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import com.spotineil.core.designsystem.theme.LocalPipState
import com.spotineil.core.designsystem.theme.PipState
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spotineil.app.ui.SpotineilApp
import com.spotineil.core.designsystem.theme.SpotineilTheme
import com.spotineil.core.model.AppLanguage
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var playbackController: PlaybackController

    private val viewModel: MainViewModel by viewModels()

    /** Picture-in-picture for YouTube's player (the only source that can't play in the background). */
    private val pip = PipState()

    private val pipActions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_PLAY_PAUSE -> pip.onPlayPause()
                ACTION_PIP_NEXT -> pip.onNext()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }

        addOnPictureInPictureModeChangedListener { info ->
            pip.isInPip = info.isInPictureInPictureMode
        }
        ContextCompat.registerReceiver(
            this,
            pipActions,
            IntentFilter().apply { addAction(ACTION_PIP_PLAY_PAUSE); addAction(ACTION_PIP_NEXT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Keep the PiP window's settings (auto-enter, ⏯ / ⏭) in sync with the YouTube screen.
        lifecycleScope.launch {
            snapshotFlow { Triple(pip.eligible, pip.isPlaying, pip.hasNext) }
                .distinctUntilChanged()
                .collect { runCatching { setPictureInPictureParams(pipParams()) } }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.uiState.filterIsInstance<MainUiState.Ready>()
                    .map { it.settings.language }
                    .distinctUntilChanged()
                    .collect(::applyLanguage)
            }
        }

        setContent {
            val state = viewModel.uiState.collectAsStateValue()
            val ready = state as? MainUiState.Ready
            SpotineilTheme(
                themeMode = ready?.settings?.themeMode ?: com.spotineil.core.model.ThemeMode.Dark,
                accent = ready?.settings?.accent ?: com.spotineil.core.model.AccentColor.Green,
                dataSaver = ready?.settings?.dataSaver ?: false,
            ) {
                // Root Surface gives every screen (incl. login, outside any Scaffold) the theme's
                // background and onBackground content color.
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    if (ready != null) {
                        CompositionLocalProvider(LocalPipState provides pip) {
                            SpotineilApp(isLoggedIn = ready.isLoggedIn, guestMode = ready.settings.guestMode, mainViewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    /** Android 10–11: no auto-enter, so enter PiP manually when the user presses Home / Recents. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pip.eligible && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActions) }
        super.onDestroy()
    }

    private fun pipParams(): PictureInPictureParams {
        fun action(icon: Int, title: String, action: String, code: Int, enabled: Boolean = true) = RemoteAction(
            Icon.createWithResource(this, icon),
            title,
            title,
            PendingIntent.getBroadcast(
                this, code, Intent(action).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).apply { isEnabled = enabled }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(
                listOf(
                    if (pip.isPlaying) {
                        action(android.R.drawable.ic_media_pause, getString(R.string.pip_pause), ACTION_PIP_PLAY_PAUSE, 1)
                    } else {
                        action(android.R.drawable.ic_media_play, getString(R.string.pip_play), ACTION_PIP_PLAY_PAUSE, 1)
                    },
                    action(android.R.drawable.ic_media_next, getString(R.string.pip_next), ACTION_PIP_NEXT, 2, enabled = pip.hasNext),
                ),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pip.eligible).setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    override fun onStart() {
        super.onStart()
        // Connect to the Spotify app (App Remote) while we're visible; it shows Spotify's own
        // authorization UI the first time.
        if (viewModel.isLoggedInNow()) playbackController.connectSpotify(this)
    }

    private fun applyLanguage(language: AppLanguage) {
        val desired = if (language == AppLanguage.System) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(language.tag)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != desired.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }
}

private const val ACTION_PIP_PLAY_PAUSE = "com.spotineil.app.PIP_PLAY_PAUSE"
private const val ACTION_PIP_NEXT = "com.spotineil.app.PIP_NEXT"
