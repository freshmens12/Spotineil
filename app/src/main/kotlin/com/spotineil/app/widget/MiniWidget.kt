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

/** Small 2x1 widget: cover, the song title and a play / pause button. */
class MiniWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.warmUpNowPlaying(artPx = 128)
        provideContent { GlanceTheme { MiniContent(context, rememberNowPlaying(context, artPx = 128)) } }
    }
}

@Composable
private fun MiniContent(context: Context, now: NowPlaying) {
    val width = LocalSize.current.width
    Row(
        GlanceModifier.fillMaxSize()
            .background(CardBackground)
            .padding(8.dp)
            .clickable(context.openApp(if (now.hasTrack) WidgetLink.PLAYER else null)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(now.art, 44.dp)
        if (width >= 150.dp) {
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(now.title ?: context.getString(R.string.widget_nothing_playing), style = titleStyle, maxLines = 1)
                Text(now.artist ?: context.getString(R.string.widget_tap_to_play), style = subtitleStyle, maxLines = 1)
            }
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        PlayButton(context, now, size = 40.dp)
        if (width >= 200.dp) IconAction(R.drawable.ic_widget_next, context.getString(R.string.widget_next), actionRunCallback<NextAction>(), size = 36.dp)
    }
}

class MiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MiniWidget()
}
