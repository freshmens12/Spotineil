package com.spotineil.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.spotineil.app.R

/** Four big buttons that open Search, Liked songs, History and Your Library. */
class ShortcutsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { ShortcutsContent(context) } }
    }
}

private class Shortcut(val icon: Int, val label: Int, val destination: String)

private val shortcuts = listOf(
    Shortcut(R.drawable.ic_widget_search, R.string.nav_search, WidgetLink.SEARCH),
    Shortcut(R.drawable.ic_widget_liked, R.string.widget_liked, WidgetLink.LIKED),
    Shortcut(R.drawable.ic_widget_history, R.string.widget_history, WidgetLink.HISTORY),
    Shortcut(R.drawable.ic_widget_library, R.string.widget_library, WidgetLink.LIBRARY),
)

@Composable
private fun ShortcutsContent(context: Context) {
    val showLabels = LocalSize.current.height >= 64.dp
    Row(
        GlanceModifier.fillMaxSize().background(CardBackground).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shortcuts.forEach { s ->
            Tile(context.getString(s.label), s.icon, context.openApp(s.destination), showLabels, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun Tile(label: String, icon: Int, action: Action, showLabel: Boolean, modifier: GlanceModifier) {
    Column(
        modifier.fillMaxHeight().clickable(action),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(40.dp).background(ImageProvider(R.drawable.bg_widget_circle_dark)), contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), label, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(Fg))
        }
        if (showLabel) {
            Spacer(GlanceModifier.height(3.dp))
            Text(label, style = TextStyle(color = Sub, fontSize = 11.sp, textAlign = TextAlign.Center), maxLines = 1)
        }
    }
}

class ShortcutsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutsWidget()
}
