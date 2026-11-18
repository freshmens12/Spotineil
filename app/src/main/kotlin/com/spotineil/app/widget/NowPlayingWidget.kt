package com.spotineil.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.spotineil.app.MainActivity
import com.spotineil.app.R
import com.spotineil.core.common.ApplicationScope
import com.spotineil.core.model.PlaybackState
import com.spotineil.core.player.PlaybackController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playbackController(): PlaybackController
}

private fun Context.controller(): PlaybackController =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java).playbackController()

/** Home-screen "Now playing" widget with previous / play-pause / next. */
class NowPlayingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = context.controller().state.value
        val art = state.track?.imageUrl?.let { loadArtwork(context, it) }
        provideContent { GlanceTheme { WidgetContent(context, state, art) } }
    }

    private suspend fun loadArtwork(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build()
        (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap(256, 256)
    }.getOrNull()
}

private val Bg = ColorProvider(Color(0xFF181818))
private val Fg = ColorProvider(Color.White)
private val Sub = ColorProvider(Color(0xFFB3B3B3))

@Composable
private fun WidgetContent(context: Context, state: PlaybackState, art: Bitmap?) {
    val track = state.track
    Row(
        GlanceModifier.fillMaxSize().background(Bg).cornerRadius(16.dp).padding(10.dp).clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = if (art != null) ImageProvider(art) else ImageProvider(R.drawable.ic_spotineil_mark),
            contentDescription = null,
            modifier = GlanceModifier.size(56.dp).cornerRadius(8.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                track?.name ?: context.getString(R.string.widget_nothing_playing),
                style = TextStyle(color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(track?.artists ?: context.getString(R.string.app_name), style = TextStyle(color = Sub, fontSize = 12.sp), maxLines = 1)
        }
        WidgetButton(R.drawable.ic_widget_previous, context.getString(R.string.widget_previous), actionRunCallback<PreviousAction>())
        WidgetButton(
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            context.getString(R.string.widget_play_pause),
            actionRunCallback<TogglePlayAction>(),
        )
        WidgetButton(R.drawable.ic_widget_next, context.getString(R.string.widget_next), actionRunCallback<NextAction>())
    }
}

@Composable
private fun WidgetButton(icon: Int, description: String, action: androidx.glance.action.Action) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        modifier = GlanceModifier.size(40.dp).padding(8.dp).clickable(action),
    )
}

class TogglePlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.controller().togglePlayPause()
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.controller().next()
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        context.controller().previous()
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** Refreshes the widget when the track or play/pause state changes. */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: PlaybackController,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            controller.state
                .map { Triple(it.track?.uri, it.track?.imageUrl, it.isPlaying) }
                .distinctUntilChanged()
                .debounce(300)
                .collect { runCatching { NowPlayingWidget().updateAll(context) } }
        }
    }
}
