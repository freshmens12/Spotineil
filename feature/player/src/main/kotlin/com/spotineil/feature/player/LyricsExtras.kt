package com.spotineil.feature.player

import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.foundation.layout.size
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.spotineil.core.designsystem.component.rememberDominantColor
import com.spotineil.core.model.Lyrics
import java.io.File

/**
 * Full-screen karaoke: the current line large in the middle, the next one below it, the line
 * before above it, over the song's colours. Back or ✕ closes it.
 */
@Composable
fun KaraokeScreen(
    lyrics: Lyrics,
    positionMs: Long,
    durationMs: Long,
    title: String,
    artist: String,
    imageUrl: String?,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val dominant by rememberDominantColor(imageUrl, Color(0xFF3A2A6A))
    val top = lerp(dominant.copy(alpha = 1f), Color.Black, 0.25f)
    val bottom = lerp(dominant.copy(alpha = 1f), Color.Black, 0.8f)
    val lines = lyrics.lines.filter { it.text.isNotBlank() }
    val current = if (lyrics.isSynced) lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0) else 0

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(artist, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.player_collapse), tint = Color.White) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                lines.getOrNull(current - 1)?.let {
                    Text(it.text, color = Color.White.copy(alpha = 0.35f), fontSize = 22.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
                    Spacer(Modifier.height(24.dp))
                }
                AnimatedContent(
                    targetState = lines.getOrNull(current)?.text.orEmpty(),
                    transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                    label = "karaokeLine",
                ) { text ->
                    Text(text, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 44.sp)
                }
                lines.getOrNull(current + 1)?.let {
                    Spacer(Modifier.height(24.dp))
                    Text(it.text, color = Color.White.copy(alpha = 0.55f), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 30.sp)
                }
            }
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** Draws a lyric line on a card (with the song and artist) and opens the share sheet with it. */
fun shareLyricLine(context: Context, line: String, title: String, artist: String) {
    val w = 1080
    val h = 1080
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF1DB954.toInt(), 0xFF191414.toInt(), Shader.TileMode.CLAMP)
    })
    val pad = 90f
    val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 76f; typeface = Typeface.DEFAULT_BOLD }
    val layout = StaticLayout.Builder.obtain("“$line”", 0, line.length + 2, big, (w - 2 * pad).toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(10f, 1f).setMaxLines(7).build()
    c.save()
    c.translate(pad, (h - layout.height) / 2f - 60f)
    layout.draw(c)
    c.restore()
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 42f; typeface = Typeface.DEFAULT_BOLD }
    c.drawText(title.take(34), pad, h - 190f, small)
    small.typeface = Typeface.DEFAULT
    small.alpha = 210
    c.drawText(artist.take(40), pad, h - 135f, small)
    small.textSize = 30f
    small.alpha = 170
    c.drawText("Spotineil", pad, h - 70f, small)

    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "spotineil-lyric.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "“$line” — $title, $artist")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.player_share_lyric)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Car mode: just the song and very large controls, easy to hit while driving. */
@Composable
fun CarModeScreen(
    title: String,
    artist: String,
    imageUrl: String?,
    isPlaying: Boolean,
    isLiked: Boolean?,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val dominant by rememberDominantColor(imageUrl, Color(0xFF202020))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(dominant.copy(alpha = 1f), Color.Black, 0.5f), Color.Black)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_car_mode), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.player_collapse), tint = Color.White, modifier = Modifier.padding(4.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 46.sp, maxLines = 2)
                Text(artist, color = Color.White.copy(alpha = 0.75f), fontSize = 26.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CarButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), onPrevious)
                CarButton(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (isPlaying) R.string.player_pause else R.string.player_play), onTogglePlay, big = true)
                CarButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next), onNext)
            }
            if (isLiked != null) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CarButton(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (isLiked) R.string.player_unlike else R.string.player_like), onLike)
                }
            }
        }
    }
}

@Composable
private fun CarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, big: Boolean = false) {
    val size = if (big) 120.dp else 96.dp
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
            containerColor = if (big) Color.White else Color.White.copy(alpha = 0.14f),
            contentColor = if (big) Color.Black else Color.White,
        ),
    ) {
        Icon(icon, label, Modifier.size(if (big) 64.dp else 48.dp))
    }
}
