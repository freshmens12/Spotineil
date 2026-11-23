package com.spotineil.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import com.spotineil.core.data.repository.UserPlaylist
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** One row of the widget: Liked Songs or one of "Your playlists". */
private data class PlaylistEntry(val id: String, val name: String, val count: Int, val coverUrl: String?)

private const val LIKED = "liked"
private const val MAX_ROWS = 8
private const val COVER_PX = 128
private val ROW_HEIGHT = 52.dp
private val HEADER_HEIGHT = 30.dp

private fun Context.entriesFlow(): Flow<List<PlaylistEntry>> {
    val ep = entryPoint()
    return combine(ep.liked().liked, ep.userPlaylists().playlists) { liked, lists ->
        listOf(PlaylistEntry(LIKED, getString(R.string.widget_liked_songs), liked.size, liked.firstOrNull()?.artworkUrl)) +
            lists.map { p: UserPlaylist -> PlaylistEntry(p.id, p.name, p.songCount, p.coverUrl) }
    }
}

/** "Your playlists": Liked Songs and your playlists; one tap shuffles one of them. */
class PlaylistsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = context.entriesFlow().first().take(MAX_ROWS)
        coroutineScope {
            initial.map { e -> async { e.coverUrl?.let { context.loadArtwork(it, COVER_PX) } } }.forEach { it.await() }
        }
        provideContent { GlanceTheme { PlaylistsContent(context, initial) } }
    }
}

@Composable
private fun PlaylistsContent(context: Context, initial: List<PlaylistEntry>) {
    val all by remember { context.entriesFlow() }.collectAsState(initial)
    val entries = all.take(MAX_ROWS)
    val covers by produceState(initialValue = entries.map { it.coverUrl?.let { u -> cachedArtwork(u, COVER_PX) } }, entries) {
        value = coroutineScope { entries.map { e -> async { e.coverUrl?.let { context.loadArtwork(it, COVER_PX) } } }.map { it.await() } }
    }
    val size = LocalSize.current
    val rows = ((size.height - HEADER_HEIGHT - 16.dp).value / ROW_HEIGHT.value).toInt().coerceIn(1, MAX_ROWS)
    Column(GlanceModifier.fillMaxSize().background(CardBackground).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.widget_playlists_title),
                GlanceModifier.defaultWeight(),
                style = TextStyle(color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Image(
                ImageProvider(R.drawable.ic_widget_library),
                context.getString(R.string.widget_library),
                GlanceModifier.size(22.dp).clickable(context.openApp(WidgetLink.LIBRARY)),
                colorFilter = ColorFilter.tint(Sub),
            )
        }
        entries.take(rows).forEachIndexed { i, e ->
            Row(
                GlanceModifier.fillMaxWidth().height(ROW_HEIGHT)
                    .clickable(actionRunCallback<ShufflePlaylistAction>(actionParametersOf(PlaylistKey to e.id))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (e.id == LIKED) {
                    Box(
                        GlanceModifier.size(40.dp).cornerRadius(6.dp).background(androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color(0xFF5038A0))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(ImageProvider(R.drawable.ic_widget_liked), null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(Fg))
                    }
                } else {
                    Cover(covers.getOrNull(i), 40.dp)
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(e.name, style = titleStyle, maxLines = 1)
                    Text(context.resources.getQuantityString(R.plurals.widget_song_count, e.count, e.count), style = subtitleStyle, maxLines = 1)
                }
                Image(ImageProvider(R.drawable.ic_widget_shuffle), context.getString(R.string.widget_shuffle), GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(Sub))
            }
        }
    }
}

/** Shuffles Liked Songs or a playlist. */
class ShufflePlaylistAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[PlaylistKey] ?: return
        val ep = context.entryPoint()
        val songs = if (id == LIKED) ep.liked().liked.first() else ep.userPlaylists().songs(id).first()
        if (songs.isNotEmpty()) ep.freePlayer().playMixed(songs.shuffled(), 0)
    }
}

private val PlaylistKey = ActionParameters.Key<String>("playlist_id")

class PlaylistsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistsWidget()
}
