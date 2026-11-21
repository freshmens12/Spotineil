package com.spotineil.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.actionParametersOf
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.spotineil.app.R
import com.spotineil.core.model.PlayableMedia
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** "Recently played": your last songs with covers; tap one to play it. */
class RecentWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // First picture: the list and its covers are ready. After that the widget follows the history by itself.
        val initial = context.recentSongs(RECENT_LIMIT)
        coroutineScope {
            initial.map { s -> async { s.artworkUrl?.let { context.loadArtwork(it, COVER_PX) } } }.forEach { it.await() }
        }
        provideContent { GlanceTheme { RecentContent(context, initial) } }
    }
}

private const val COVER_PX = 128
private val ROW_HEIGHT = 52.dp
private val HEADER_HEIGHT = 30.dp

@Composable
private fun RecentContent(context: Context, initial: List<PlayableMedia>) {
    val songs by remember { context.recentSongsFlow(RECENT_LIMIT) }.collectAsState(initial)
    val covers by produceState(initialValue = songs.map { it.artworkUrl?.let { u -> cachedArtwork(u, COVER_PX) } }, songs) {
        value = coroutineScope {
            songs.map { s -> async { s.artworkUrl?.let { context.loadArtwork(it, COVER_PX) } } }.map { it.await() }
        }
    }
    val size = LocalSize.current
    val rows = ((size.height - HEADER_HEIGHT - 16.dp).value / ROW_HEIGHT.value).toInt().coerceIn(1, RECENT_LIMIT)
    Column(GlanceModifier.fillMaxSize().background(CardBackground).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.widget_recent_title),
                GlanceModifier.defaultWeight(),
                style = TextStyle(color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Image(
                ImageProvider(R.drawable.ic_widget_history),
                context.getString(R.string.widget_history),
                GlanceModifier.size(22.dp).clickable(context.openApp(WidgetLink.HISTORY)),
                colorFilter = androidx.glance.ColorFilter.tint(Sub),
            )
        }
        if (songs.isEmpty()) {
            Text(context.getString(R.string.widget_recent_empty), style = subtitleStyle, modifier = GlanceModifier.padding(top = 6.dp))
        }
        songs.take(rows).forEachIndexed { i, song ->
            Row(
                GlanceModifier.fillMaxWidth().height(ROW_HEIGHT).clickable(actionRunCallback<PlayRecentAction>(actionParametersOf(RecentIndex to i))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(covers.getOrNull(i), 40.dp)
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(song.title, style = titleStyle, maxLines = 1)
                    Text(song.artist, style = subtitleStyle, maxLines = 1)
                }
            }
        }
    }
}

class RecentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentWidget()
}
