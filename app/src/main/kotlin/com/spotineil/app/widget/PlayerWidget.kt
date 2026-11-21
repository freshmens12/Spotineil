package com.spotineil.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.spotineil.app.R

/**
 * Square player: big cover, title, artist and the controls. When it is made wide and short it
 * turns into cover on the left, text and controls on the right.
 */
class PlayerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        context.warmUpNowPlaying(artPx = 512)
        provideContent { GlanceTheme { PlayerContent(context, rememberNowPlaying(context, artPx = 512)) } }
    }
}

@Composable
private fun PlayerContent(context: Context, now: NowPlaying) {
    val size = LocalSize.current
    val open = context.openApp(if (now.hasTrack) WidgetLink.PLAYER else null)
    val title = now.title ?: context.getString(R.string.widget_nothing_playing)
    val artist = now.artist ?: context.getString(R.string.widget_tap_to_play)
    val center = TextStyle(color = Fg, fontSize = titleStyle.fontSize, fontWeight = titleStyle.fontWeight, textAlign = TextAlign.Center)
    val centerSub = TextStyle(color = Sub, fontSize = subtitleStyle.fontSize, textAlign = TextAlign.Center)

    if (size.height < 150.dp) {
        // Short: cover | text + controls.
        Row(GlanceModifier.fillMaxSize().background(CardBackground).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(now.art, minOf(size.height - 20.dp, 96.dp), GlanceModifier.clickable(open))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().clickable(open)) {
                Text(title, style = titleStyle, maxLines = 1)
                Text(artist, style = subtitleStyle, maxLines = 1)
                Spacer(GlanceModifier.height(4.dp))
                Controls(context, now)
            }
        }
    } else {
        // Square: cover on top, then text and controls, centred.
        val cover: Dp = (minOf(size.width - 24.dp, size.height - 112.dp)).coerceAtLeast(56.dp)
        Column(
            GlanceModifier.fillMaxSize().background(CardBackground).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Cover(now.art, cover, GlanceModifier.clickable(open))
            Spacer(GlanceModifier.height(6.dp))
            Text(title, GlanceModifier.fillMaxWidth(), style = center, maxLines = 1)
            Text(artist, GlanceModifier.fillMaxWidth(), style = centerSub, maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Controls(context, now)
        }
    }
}

@Composable
private fun Controls(context: Context, now: NowPlaying) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(R.drawable.ic_widget_previous, context.getString(R.string.widget_previous), actionRunCallback<PreviousAction>(), size = 36.dp)
        PlayButton(context, now, size = 40.dp)
        IconAction(R.drawable.ic_widget_next, context.getString(R.string.widget_next), actionRunCallback<NextAction>(), size = 36.dp)
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}
