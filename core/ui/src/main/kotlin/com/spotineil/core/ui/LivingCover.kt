package com.spotineil.core.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.rememberDominantColor
import com.spotineil.core.player.AudioLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Settings → Appearance → Animated cover. Provided by the app. */
val LocalAnimatedCovers = staticCompositionLocalOf { true }

/**
 * The cover on the Now playing screen, brought to life while the song plays: it slowly zooms and
 * drifts, pulses with the music (its loudness, see [AudioLevel]) and glows in its own colours. When
 * paused it rests where it is. A still cover when turned off in Settings, or when the phone's
 * "Remove animations" is on.
 */
@Composable
fun LivingCover(
    imageUrl: String?,
    contentDescription: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val context = LocalContext.current
    val reducedMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    if (!LocalAnimatedCovers.current || reducedMotion) {
        CoverImage(imageUrl, contentDescription, modifier, shape = shape)
        return
    }
    val glowColor by rememberDominantColor(imageUrl, Color.Transparent)
    var seconds by remember { mutableFloatStateOf(0f) } // motion time: only runs while playing
    var pulse by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        var last = 0L
        var fast = 0f
        var slow = 0f
        while (true) {
            withFrameNanos { frame ->
                val dt = if (last == 0L) 0f else ((frame - last) / 1e9f).coerceAtMost(0.1f)
                last = frame
                if (isPlaying) {
                    seconds += dt
                    val level = AudioLevel.at()
                    // Quick to rise, slower to fall; the difference from the longer average is the beat.
                    fast += (level - fast) * (if (level > fast) 0.45f else 0.12f)
                    slow += (level - slow) * 0.02f
                    pulse = ((fast - slow) * 2.5f + fast * 0.25f).coerceIn(0f, 1f)
                } else {
                    pulse *= 0.9f
                }
            }
            if (!isPlaying && pulse < 0.001f) break
        }
    }

    Box(
        modifier.drawBehind {
            // A soft glow in the cover's own colours, breathing with the music.
            val radius = size.minDimension * (0.66f + 0.08f * pulse)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glowColor.copy(alpha = (0.32f + 0.38f * pulse) * glowColor.alpha), Color.Transparent),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = radius,
                ),
                radius = radius,
            )
        },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = 1f + 0.025f * pulse
                    scaleX = s
                    scaleY = s
                }
                .clip(shape),
        ) {
            CoverImage(
                imageUrl,
                contentDescription,
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        // Slow zoom and drift; always zoomed in enough that the edges never show.
                        val zoom = 1.09f + 0.04f * sin(seconds * 2 * PI.toFloat() / 23f)
                        scaleX = zoom
                        scaleY = zoom
                        translationX = size.width * 0.022f * sin(seconds * 2 * PI.toFloat() / 31f)
                        translationY = size.height * 0.022f * cos(seconds * 2 * PI.toFloat() / 37f)
                    },
                shape = RectangleShape,
            )
        }
    }
}
