package com.spotineil.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.spotineil.app.MainActivity
import com.spotineil.app.R
import com.spotineil.core.data.repository.RecentsRepository
import com.spotineil.core.model.PlayableMedia
import com.spotineil.core.model.RecentItem
import com.spotineil.core.model.toPlayable
import com.spotineil.core.model.toYouTubeVideo
import com.spotineil.core.player.PlaybackController
import com.spotineil.feature.discover.free.FreePlayer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ---- Look: the same dark card and green accent as the app ----

internal val Fg = ColorProvider(Color.White)
internal val Sub = ColorProvider(Color(0xFFB3B3B3))
internal val Black = ColorProvider(Color.Black)

internal val CardBackground get() = ImageProvider(R.drawable.bg_widget_card)

internal val titleStyle = TextStyle(color = Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
internal val subtitleStyle = TextStyle(color = Sub, fontSize = 12.sp)

// ---- Access to the app's singletons from a widget ----

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun playbackController(): PlaybackController
    fun freePlayer(): FreePlayer
    fun recents(): RecentsRepository
    fun liked(): com.spotineil.core.data.repository.LikedMediaRepository
    fun userPlaylists(): com.spotineil.core.data.repository.UserPlaylistRepository
}

internal fun Context.entryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

internal fun Context.controller(): PlaybackController = entryPoint().playbackController()

// ---- Opening the app on a given screen ----

/** Screens a widget can open. MainActivity reads them from [EXTRA_OPEN]. */
object WidgetLink {
    const val EXTRA_OPEN = "com.spotineil.app.OPEN"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val LIKED = "liked"
    const val HISTORY = "history"
    const val PLAYER = "player"

    /** Open the full-screen player of the mini player (for songs that are not on the YouTube screen). */
    const val EXPAND = "expand"

    /** Play what was playing last (the saved session), then show the player. */
    const val RESUME = "resume"
}

/** Opens Spotineil on [destination] (null = just open it). */
internal fun Context.openApp(destination: String? = null): Action {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (destination != null) {
            putExtra(WidgetLink.EXTRA_OPEN, destination)
            // Different data keeps the system from merging the buttons of one widget into one PendingIntent.
            data = Uri.parse("spotineil://widget/$destination")
        }
    }
    return actionStartActivity(intent)
}

// ---- What is playing ----

internal class NowPlaying(val title: String?, val artist: String?, val isPlaying: Boolean, val art: Bitmap?) {
    val hasTrack get() = title != null
}

/**
 * What is playing, read inside the widget's composition. A widget that was just tapped keeps a live
 * session, and Glance does not call provideGlance again for it: reading the state here is what makes it
 * redraw when the song or play / pause changes.
 */
@Composable
internal fun rememberNowPlaying(context: Context, artPx: Int): NowPlaying {
    val state by context.controller().state.collectAsState()
    val url = state.track?.imageUrl
    val art by produceState(initialValue = url?.let { artCache.get(cacheKey(it, artPx)) }, url) {
        value = url?.let { context.loadArtwork(it, artPx) }
    }
    val track = state.track
    return NowPlaying(track?.name, track?.artists, state.isPlaying, art)
}

/** Loads the cover of the current song into the cache, so the widget's first picture already has it. */
internal suspend fun Context.warmUpNowPlaying(artPx: Int) {
    controller().state.value.track?.imageUrl?.let { loadArtwork(it, artPx) }
}

private val artCache = LruCache<String, Bitmap>(24)
private fun cacheKey(url: String, px: Int) = "$url@$px"

/** The cover as a bitmap with rounded corners (widgets can't clip images themselves on older Android). */
internal suspend fun Context.loadArtwork(url: String, px: Int, rounding: Float = 0.12f): Bitmap? {
    artCache.get(cacheKey(url, px))?.let { return it }
    return runCatching {
        val request = ImageRequest.Builder(this).data(url).size(px).allowHardware(false).build()
        val bitmap = (imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap(px, px)?.rounded(rounding)
        bitmap?.also { artCache.put(cacheKey(url, px), it) }
    }.getOrNull()
}

private fun Bitmap.rounded(fraction: Float): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), width * fraction, height * fraction, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, 0f, 0f, paint)
    return out
}

/** The last songs played, newest first (the same list as History), updated as songs are played. */
internal fun Context.recentSongsFlow(limit: Int): Flow<List<PlayableMedia>> =
    entryPoint().recents().recent(limit * 3).map { items ->
        items.filterIsInstance<RecentItem.Song>()
            .map { m -> m.media.toYouTubeVideo()?.toPlayable() ?: m.media }
            .take(limit)
    }

internal suspend fun Context.recentSongs(limit: Int): List<PlayableMedia> = recentSongsFlow(limit).first()

/** A cover that was loaded before (null if not yet). */
internal fun cachedArtwork(url: String, px: Int): Bitmap? = artCache.get(cacheKey(url, px))

// ---- Pieces shared by the widgets ----

@Composable
internal fun Cover(art: Bitmap?, size: Dp, modifier: GlanceModifier = GlanceModifier) {
    Image(
        provider = if (art != null) ImageProvider(art) else ImageProvider(R.drawable.ic_spotineil_mark),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/** A plain icon button (previous / next). */
@Composable
internal fun IconAction(icon: Int, description: String, action: Action, size: Dp = 40.dp) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = description,
        modifier = GlanceModifier.size(size).padding(8.dp).clickable(action),
    )
}

/** The big green play / pause button. With nothing playing it opens the app and continues the last session. */
@Composable
internal fun PlayButton(context: Context, now: NowPlaying, size: Dp = 44.dp) {
    val action = if (now.hasTrack) actionRunCallback<TogglePlayAction>() else context.openApp(WidgetLink.RESUME)
    Box(
        modifier = GlanceModifier.size(size).background(ImageProvider(R.drawable.bg_widget_circle_green)).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (now.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            contentDescription = context.getString(R.string.widget_play_pause),
            modifier = GlanceModifier.size(size * 0.55f),
            colorFilter = ColorFilter.tint(Black),
        )
    }
}

// ---- Button actions (they run in the app's process, which keeps playing in the background) ----

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

/** A row of the "Recently played" widget: plays that song and the ones after it. */
class PlayRecentAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[RecentIndex] ?: return
        context.entryPoint().freePlayer().playMixed(context.recentSongs(RECENT_LIMIT), index)
    }
}

internal val RecentIndex = ActionParameters.Key<Int>("recent_index")
internal const val RECENT_LIMIT = 8
