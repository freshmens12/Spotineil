package com.spotineil.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.spotineil.core.common.ApplicationScope
import com.spotineil.core.data.repository.RecentsRepository
import com.spotineil.core.player.PlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Redraws the home-screen widgets when the song or play / pause changes, and when the history grows. */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: PlaybackController,
    private val recents: RecentsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            controller.state
                .map { Triple(it.track?.uri, it.track?.imageUrl, it.isPlaying) }
                .distinctUntilChanged()
                .debounce(300)
                .collect {
                    runCatching { NowPlayingWidget().updateAll(context) }
                    runCatching { PlayerWidget().updateAll(context) }
                    runCatching { MiniWidget().updateAll(context) }
                }
        }
        scope.launch {
            recents.recent(RECENT_LIMIT)
                .map { items -> items.map { it.toString() } }
                .distinctUntilChanged()
                .debounce(500)
                .collect { runCatching { RecentWidget().updateAll(context) } }
        }
    }
}
