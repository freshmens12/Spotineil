package com.spotineil.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.spotineil.app.R

/** Wide "Now playing" strip: cover, title, and previous / play-pause / next. */
class NowPlayingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.warmUpNowPlaying(artPx = 192)
        provideContent { GlanceTheme { NowPlayingContent(context, rememberNowPlaying(context, artPx = 192)) } }
    }
}

@Composable
private fun NowPlayingContent(context: Context, now: NowPlaying) {
    val narrow = LocalSize.current.width < 220.dp
    Row(
        GlanceModifier.fillMaxSize()
            .background(CardBackground)
            .padding(10.dp)
            .clickable(context.openApp(if (now.hasTrack) WidgetLink.PLAYER else null)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(now.art, 56.dp)
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(now.title ?: context.getString(R.string.widget_nothing_playing), style = titleStyle, maxLines = 1)
            Text(now.artist ?: context.getString(R.string.app_name), style = subtitleStyle, maxLines = 1)
        }
        if (!narrow) IconAction(R.drawable.ic_widget_previous, context.getString(R.string.widget_previous), actionRunCallback<PreviousAction>())
        PlayButton(context, now, size = 44.dp)
        IconAction(R.drawable.ic_widget_next, context.getString(R.string.widget_next), actionRunCallback<NextAction>())
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
