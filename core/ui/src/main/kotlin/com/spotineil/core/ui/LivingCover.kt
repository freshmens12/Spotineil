package com.spotineil.core.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.spotineil.core.designsystem.component.CoverImage
import com.spotineil.core.designsystem.component.rememberDominantColor
import com.spotineil.core.model.CoverStyle
import com.spotineil.core.player.AudioLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Settings → Appearance → Cover style. Provided by the app. */
val LocalCoverStyle = staticCompositionLocalOf { CoverStyle.Living }

/** True when the phone's "Remove animations" is on: covers then stay still. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/**
 * The cover on the Now playing screen, brought to life in the chosen [CoverStyle] while the song
 * plays: it follows how loud the music is right now (see [AudioLevel]) and rests when paused.
 * A still cover for [CoverStyle.Still] and when the phone's "Remove animations" is on.
 */
@Composable
fun LivingCover(
    imageUrl: String?,
    contentDescription: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    style: CoverStyle = LocalCoverStyle.current,
    /** Apple Music motion artwork for this song (see [rememberMotionArtwork]), played over the picture. */
    motionUrl: String? = null,
) {
    val reducedMotion = rememberReducedMotion()
    if (style == CoverStyle.Still || reducedMotion) {
        CoverArt(imageUrl, contentDescription, modifier, shape, motionUrl.takeUnless { reducedMotion }, isPlaying)
        return
    }
    val art: CoverArtSlot = { m, s -> CoverArt(imageUrl, contentDescription, m, s, motionUrl, isPlaying) }
    val glow by rememberDominantColor(imageUrl, Color.Transparent)
    val motion = rememberCoverMotion(
        isPlaying,
        spinDegreesPerSecond = when (style) {
            CoverStyle.Vinyl -> 200f // 33⅓ rpm, like a real record
            CoverStyle.Disc -> 72f
            else -> 0f
        },
    )
    when (style) {
        CoverStyle.Vinyl -> VinylCover(art, isPlaying, motion, glow, modifier)
        CoverStyle.Disc -> DiscCover(art, motion, glow, modifier)
        CoverStyle.Beat -> BeatCover(art, motion, glow, modifier, shape)
        CoverStyle.Tilt -> TiltCover(art, motion, modifier, shape)
        CoverStyle.Ripples -> RippleCover(art, motion, glow, modifier, shape)
        CoverStyle.Living, CoverStyle.Still -> DriftCover(art, motion, glow, modifier, shape)
    }
}

// ---- The music, turned into motion ----

/** Time, loudness, beats and spin shared by the styles. Only changes while something moves. */
@Stable
internal class CoverMotion {
    /** Seconds of playing (stands still while paused). */
    var seconds by mutableFloatStateOf(0f)

    /** How strongly the music hits right now, 0..1. */
    var pulse by mutableFloatStateOf(0f)

    /** 1 right on a beat, fading to 0 shortly after. */
    var beat by mutableFloatStateOf(0f)

    /** How many beats so far (each one flips the Beat style's wobble). */
    var beats by mutableIntStateOf(0)

    /** Turn of a record or CD, in degrees. Speeds up and slows down smoothly, like a turntable. */
    var spin by mutableFloatStateOf(0f)

    /** Keeps running after a pause, so rings already on their way fade out. */
    var ringClock by mutableFloatStateOf(0f)

    /** When the last few rings started (on [ringClock]), for the Ripples style. */
    val rings = FloatArray(RINGS) { -100f }
    var ringCount by mutableIntStateOf(0)

    internal var spinSpeed = 0f
    internal var lastBeatAt = -10f
    internal var lastRingAt = -10f

    fun addRing() {
        rings[ringCount % RINGS] = ringClock
        lastRingAt = ringClock
        ringCount++
    }

    companion object {
        const val RINGS = 6
    }
}

@Composable
internal fun rememberCoverMotion(isPlaying: Boolean, spinDegreesPerSecond: Float): CoverMotion {
    val m = remember { CoverMotion() }
    LaunchedEffect(isPlaying, spinDegreesPerSecond) {
        var last = 0L
        var fast = 0f
        var slow = 0f
        var armed = true
        while (true) {
            withFrameNanos { frame ->
                val dt = if (last == 0L) 0f else ((frame - last) / 1e9f).coerceAtMost(0.1f)
                last = frame
                m.ringClock += dt
                if (isPlaying) {
                    m.seconds += dt
                    val level = AudioLevel.at()
                    // Quick to rise, slower to fall; the difference from the longer average is the beat.
                    fast += (level - fast) * (if (level > fast) 0.45f else 0.12f)
                    slow += (level - slow) * 0.02f
                    val onset = fast - slow
                    m.pulse = (onset * 2.5f + fast * 0.25f).coerceIn(0f, 1f)
                    if (armed && onset > 0.09f && m.ringClock - m.lastBeatAt > 0.28f) {
                        armed = false
                        m.lastBeatAt = m.ringClock
                        m.beat = 1f
                        m.beats++
                        m.addRing()
                    } else if (onset < 0.04f) {
                        armed = true
                    }
                    // Quiet songs still get a ring now and then.
                    if (m.ringClock - m.lastRingAt > 3f) m.addRing()
                } else {
                    m.pulse *= 0.9f
                }
                m.beat *= exp(-dt * 5f)
                // A turntable takes a moment to get up to speed, and to come to rest.
                val target = if (isPlaying) spinDegreesPerSecond else 0f
                m.spinSpeed += (target - m.spinSpeed) * min(1f, dt * if (isPlaying) 1.8f else 1.4f)
                if (m.spinSpeed < 0.3f && !isPlaying) m.spinSpeed = 0f
                m.spin = (m.spin + m.spinSpeed * dt) % 360f
            }
            val ringsDone = m.ringClock - m.lastRingAt > RING_SECONDS
            if (!isPlaying && m.pulse < 0.001f && m.beat < 0.001f && m.spinSpeed == 0f && ringsDone) break
        }
    }
    return m
}

/** A soft glow in the cover's own colours behind it, breathing with the music. */
private fun Modifier.coverGlow(color: Color, strength: () -> Float): Modifier = drawBehind {
    if (color.alpha == 0f) return@drawBehind
    val s = strength()
    val radius = size.minDimension * (0.66f + 0.1f * s)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = (0.32f + 0.45f * s) * color.alpha), Color.Transparent),
            center = Offset(size.width / 2, size.height / 2),
            radius = radius,
        ),
        radius = radius,
    )
}

// ---- Living: slow zoom and drift, pulse and glow ----

@Composable
private fun DriftCover(art: CoverArtSlot, m: CoverMotion, glow: Color, modifier: Modifier, shape: Shape) {
    Box(modifier.coverGlow(glow) { m.pulse }) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = 1f + 0.03f * m.pulse
                    scaleX = s
                    scaleY = s
                }
                .clip(shape),
        ) {
            art(Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        // Slow zoom and drift; always zoomed in enough that the edges never show.
                        val t = m.seconds
                        val zoom = 1.09f + 0.04f * sin(t * TAU / 23f)
                        scaleX = zoom
                        scaleY = zoom
                        translationX = size.width * 0.022f * sin(t * TAU / 31f)
                        translationY = size.height * 0.022f * cos(t * TAU / 37f)
                    }, RectangleShape
            )
        }
    }
}

// ---- Vinyl: a spinning record with the cover as its label, and a tonearm ----

@Composable
private fun VinylCover(art: CoverArtSlot, isPlaying: Boolean, m: CoverMotion, glow: Color, modifier: Modifier) {
    // The arm swings onto the record when the song plays and lifts off when paused.
    val arm by animateFloatAsState(if (isPlaying) ARM_ON_RECORD else ARM_AT_REST, tween(800, easing = FastOutSlowInEasing), label = "tonearm")
    Box(modifier.coverGlow(glow) { m.pulse }) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.94f)
                .graphicsLayer {
                    val s = 1f + 0.012f * m.pulse
                    scaleX = s
                    scaleY = s
                },
        ) {
            Canvas(Modifier.matchParentSize()) { drawRecord() }
            art(Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(LABEL_FRACTION)
                    .graphicsLayer { rotationZ = m.spin }
                    .clip(CircleShape), CircleShape
            )
            Canvas(Modifier.matchParentSize()) { drawSpindle() }
        }
        Canvas(Modifier.matchParentSize()) { drawTonearm(arm) }
    }
}

private fun DrawScope.drawRecord() {
    val r = size.minDimension / 2
    val c = center
    drawCircle(Brush.radialGradient(listOf(Color(0xFF262626), Color(0xFF0D0D0D)), c, r), r, c)
    // Grooves: fine rings, a little brighter every few, and the quiet gaps between tracks.
    var groove = r * (LABEL_FRACTION + 0.02f)
    var i = 0
    while (groove < r * 0.97f) {
        drawCircle(Color.White.copy(alpha = if (i % 6 == 0) 0.075f else 0.035f), groove, c, style = Stroke(1f))
        groove += r * 0.017f
        i++
    }
    for (gap in floatArrayOf(0.6f, 0.73f, 0.86f)) {
        drawCircle(Color.Black.copy(alpha = 0.45f), r * gap, c, style = Stroke(r * 0.012f))
    }
    // Light catching the grooves. It stays put while the record turns, as on a real turntable.
    drawCircle(
        Brush.sweepGradient(
            0f to Color.Transparent,
            0.08f to Color.White.copy(alpha = 0.12f),
            0.17f to Color.Transparent,
            0.55f to Color.Transparent,
            0.62f to Color.White.copy(alpha = 0.09f),
            0.7f to Color.Transparent,
            1f to Color.Transparent,
            center = c,
        ),
        r,
        c,
    )
    drawCircle(Color.White.copy(alpha = 0.08f), r - 1f, c, style = Stroke(1.5f))
    drawCircle(Color.Black.copy(alpha = 0.6f), r * LABEL_FRACTION + 1f, c, style = Stroke(r * 0.02f))
}

private fun DrawScope.drawSpindle() {
    val r = size.minDimension / 2
    drawCircle(Color(0xFF2A2A2A), r * 0.045f, center)
    drawCircle(Color(0xFFC8C8C8), r * 0.026f, center)
}

private fun DrawScope.drawTonearm(angle: Float) {
    val w = size.width
    val pivot = Offset(w * 0.93f, size.height * 0.1f)
    val end = Offset(pivot.x, pivot.y + w * 0.6f)
    rotate(angle, pivot) {
        // Shadow, arm, and a highlight along it.
        drawLine(Color.Black.copy(alpha = 0.35f), pivot + Offset(w * 0.012f, w * 0.012f), end + Offset(w * 0.012f, w * 0.012f), w * 0.02f, StrokeCap.Round)
        drawLine(Color(0xFFA6A6A6), pivot, end, w * 0.018f, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.4f), pivot + Offset(-w * 0.004f, 0f), end + Offset(-w * 0.004f, 0f), w * 0.005f, StrokeCap.Round)
        // Head shell with the needle, and the counterweight behind the pivot.
        drawRoundRect(Color(0xFF6E6E6E), Offset(end.x - w * 0.03f, end.y - w * 0.01f), Size(w * 0.06f, w * 0.09f), CornerRadius(w * 0.012f))
        drawRoundRect(Color(0xFF505050), Offset(pivot.x - w * 0.032f, pivot.y - w * 0.085f), Size(w * 0.064f, w * 0.065f), CornerRadius(w * 0.014f))
    }
    drawCircle(Color(0xFF3A3A3A), w * 0.048f, pivot)
    drawCircle(Color(0xFFB5B5B5), w * 0.024f, pivot)
}

// ---- CD: the cover printed on a spinning disc, with a rainbow sheen and a hole ----

@Composable
private fun DiscCover(art: CoverArtSlot, m: CoverMotion, glow: Color, modifier: Modifier) {
    Box(modifier.coverGlow(glow) { m.pulse }) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.96f)
                .graphicsLayer {
                    val s = 1f + 0.015f * m.pulse
                    scaleX = s
                    scaleY = s
                    // Its own layer, so the hole in the middle can be cut out.
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            art(Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = m.spin }
                    .clip(CircleShape), CircleShape
            )
            Canvas(Modifier.matchParentSize()) { drawDiscShine() }
        }
    }
}

private fun DrawScope.drawDiscShine() {
    val r = size.minDimension / 2
    val c = center
    val rainbow = listOf(
        Color(0xFFFF5A5A), Color(0xFFFFD45A), Color(0xFF6BFF8A), Color(0xFF5AD8FF),
        Color(0xFF7A6BFF), Color(0xFFFF6BE0), Color(0xFFFF5A5A),
    ).map { it.copy(alpha = 0.2f) }
    drawCircle(Brush.sweepGradient(rainbow, c), r, c)
    drawCircle(
        Brush.sweepGradient(
            0f to Color.Transparent,
            0.11f to Color.White.copy(alpha = 0.28f),
            0.15f to Color.Transparent,
            0.61f to Color.Transparent,
            0.65f to Color.White.copy(alpha = 0.2f),
            0.69f to Color.Transparent,
            1f to Color.Transparent,
            center = c,
        ),
        r,
        c,
    )
    // The clear plastic ring in the middle, then the hole.
    drawCircle(Color(0xFFE6E6E6).copy(alpha = 0.6f), r * 0.3f, c)
    drawCircle(Color.White.copy(alpha = 0.45f), r * 0.3f, c, style = Stroke(r * 0.012f))
    drawCircle(Color.Black.copy(alpha = 0.2f), r * 0.19f, c, style = Stroke(r * 0.01f))
    drawCircle(Color.Black, r * 0.12f, c, blendMode = BlendMode.Clear)
    drawCircle(Color.White.copy(alpha = 0.3f), r * 0.99f, c, style = Stroke(r * 0.018f))
}

// ---- Beat: bounces on the beat ----

@Composable
private fun BeatCover(art: CoverArtSlot, m: CoverMotion, glow: Color, modifier: Modifier, shape: Shape) {
    val ring = if (glow.alpha == 0f) Color.White else glow
    Box(
        modifier.drawBehind {
            val b = m.beat
            val radius = size.minDimension * (0.64f + 0.16f * b)
            drawCircle(
                Brush.radialGradient(
                    listOf(ring.copy(alpha = 0.22f + 0.5f * b), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius,
            )
            if (b > 0.02f) {
                drawCircle(ring.copy(alpha = 0.55f * b), size.minDimension * (0.52f + 0.2f * (1f - b)), style = Stroke(3.dp.toPx() * b + 1f))
            }
        },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = 1f + 0.07f * m.beat + 0.02f * m.pulse
                    scaleX = s
                    scaleY = s
                    rotationZ = (if (m.beats % 2 == 0) 1f else -1f) * 2.2f * m.beat
                }
                .clip(shape),
        ) {
            art(Modifier.matchParentSize(), RectangleShape)
            Box(Modifier.matchParentSize().graphicsLayer { alpha = m.beat }.background(Color.White.copy(alpha = 0.12f)))
        }
    }
}

// ---- 3D tilt: floats and tilts, with a moving shine ----

@Composable
private fun TiltCover(art: CoverArtSlot, m: CoverMotion, modifier: Modifier, shape: Shape) {
    val density = LocalDensity.current.density
    Box(
        modifier.drawBehind {
            // Its shadow on the "floor", smaller while the cover floats higher.
            val lift = 0.5f + 0.5f * sin(m.seconds * TAU / 5f)
            val w = size.width * (0.82f - 0.08f * lift)
            drawOval(
                Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = 0.45f - 0.15f * lift), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 1.03f),
                    radius = w / 2,
                ),
                topLeft = Offset((size.width - w) / 2, size.height * 0.98f),
                size = Size(w, size.height * 0.1f),
            )
        },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val t = m.seconds
                    rotationX = 8f * sin(t * TAU / 9f)
                    rotationY = 11f * sin(t * TAU / 13f + 1f)
                    translationY = -size.height * 0.018f * (1f + sin(t * TAU / 5f))
                    val s = 0.96f + 0.02f * m.pulse
                    scaleX = s
                    scaleY = s
                    cameraDistance = 14f * density
                }
                .clip(shape),
        ) {
            art(Modifier.matchParentSize(), RectangleShape)
            Canvas(Modifier.matchParentSize()) {
                // A glossy shine that slides across as it tilts.
                val x = size.width * (-0.35f + 0.5f * (1f + sin(m.seconds * TAU / 13f + 1f)) / 2f * 1.6f)
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + size.width * 0.5f, size.height),
                    ),
                )
            }
        }
    }
}

// ---- Ripples: rings ripple out from the cover on the beat ----

@Composable
private fun RippleCover(art: CoverArtSlot, m: CoverMotion, glow: Color, modifier: Modifier, shape: Shape) {
    val color = if (glow.alpha == 0f) Color.White else glow
    Box(
        modifier.drawBehind {
            val corner = 10.dp.toPx()
            val now = m.ringClock
            for (born in m.rings) {
                val age = now - born
                if (age < 0f || age > RING_SECONDS) continue
                val p = age / RING_SECONDS
                val eased = 1f - (1f - p).pow(3)
                val grow = size.minDimension * 0.3f * eased
                drawRoundRect(
                    color.copy(alpha = 0.6f * (1f - p).pow(1.5f)),
                    topLeft = Offset(-grow, -grow),
                    size = Size(size.width + 2 * grow, size.height + 2 * grow),
                    cornerRadius = CornerRadius(corner + grow),
                    style = Stroke((3.dp.toPx() * (1f - p)).coerceAtLeast(1f)),
                )
            }
        },
    ) {
        art(Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = 1f + 0.03f * m.pulse + 0.02f * m.beat
                    scaleX = s
                    scaleY = s
                }, shape
        )
    }
}

/** Draws the cover (picture or moving artwork) with a modifier and shape. */
private typealias CoverArtSlot = @Composable (Modifier, Shape) -> Unit

private const val TAU = (2 * PI).toFloat()
private const val LABEL_FRACTION = 0.4f
private const val ARM_ON_RECORD = 14f
private const val ARM_AT_REST = -8f
private const val RING_SECONDS = 1.6f
