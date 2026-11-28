package com.spotineil.core.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remembers which way the last skip went (Next or Previous), so the cover slides the right way.
 * Screens that know the queue position pass it to [NowPlayingCover] as well; this hint wins.
 */
@Stable
class SkipDirection {
    private var hint: Boolean? = null
    private var hintAt = 0L

    fun next() = set(true)

    fun previous() = set(false)

    private fun set(forward: Boolean) {
        hint = forward
        hintAt = SystemClock.uptimeMillis()
    }

    /** Which way the song just changed: forward unless Previous was pressed or the position went back. */
    internal fun forward(fromIndex: Int?, toIndex: Int?): Boolean {
        val pressed = hint?.takeIf { SystemClock.uptimeMillis() - hintAt < HINT_MS }
        hint = null
        if (pressed != null) return pressed
        if (fromIndex != null && toIndex != null && fromIndex != toIndex) return toIndex > fromIndex
        return true
    }

    private companion object {
        // Previous within the first seconds of a song only restarts it: the hint then goes stale.
        const val HINT_MS = 4_000L
    }
}

@Composable
fun rememberSkipDirection(): SkipDirection = remember { SkipDirection() }

/**
 * The Now playing cover ([LivingCover]) that slides to the side when the song changes: the old
 * cover leaves one way while the new one comes in from the other, following Next or Previous.
 * Swiping the cover sideways skips too, and touching and holding it picks a [CoverStyleSheet].
 */
@Composable
fun NowPlayingCover(
    songKey: Any?,
    imageUrl: String?,
    contentDescription: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    index: Int? = null,
    direction: SkipDirection = rememberSkipDirection(),
    onSwipeNext: (() -> Unit)? = null,
    onSwipePrevious: (() -> Unit)? = null,
    /** The song, to find Apple Music motion artwork for its album (when turned on). */
    title: String? = null,
    artist: String? = null,
    album: String? = null,
) {
    val motion = rememberMotionArtwork(title, artist, album)
    val reducedMotion = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val swipeNext by rememberUpdatedState(onSwipeNext)
    val swipePrevious by rememberUpdatedState(onSwipePrevious)

    var shownKey by remember { mutableStateOf(songKey) }
    var shownIndex by remember { mutableStateOf(index) }
    // The picture of the song on show, kept for when it has to slide away.
    val shown = remember { arrayOf(imageUrl, contentDescription) }
    SideEffect {
        if (songKey == shownKey) {
            shown[0] = imageUrl
            shown[1] = contentDescription
        }
    }
    var leaving by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var forward by remember { mutableStateOf(true) }
    var leavingFrom by remember { mutableFloatStateOf(0f) }
    val slide = remember { Animatable(1f) } // 0 → 1 while the new cover comes in
    val drag = remember { Animatable(0f) } // where the finger has moved the cover, in px
    var width by remember { mutableIntStateOf(0) }
    var pickStyle by remember { mutableStateOf(false) }

    LaunchedEffect(songKey) {
        if (songKey == shownKey) return@LaunchedEffect
        if (reducedMotion) {
            shownKey = songKey
            shownIndex = index
            drag.snapTo(0f)
            return@LaunchedEffect
        }
        forward = direction.forward(shownIndex, index)
        leavingFrom = drag.value
        leaving = shown[0] to shown[1]
        drag.snapTo(0f)
        slide.snapTo(0f)
        shownKey = songKey
        shownIndex = index
        try {
            slide.animateTo(1f, tween(SLIDE_MS, easing = FastOutSlowInEasing))
        } finally {
            leaving = null
        }
    }

    // Between the song changing and the slide starting (one frame): the old cover, where it was.
    val pending = songKey != shownKey && !reducedMotion
    val outgoing = if (pending) shown[0] to shown[1] else leaving

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        pickStyle = true
                    },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val d = drag.value
                        val goNext = swipeNext
                        val goPrevious = swipePrevious
                        val keyAtSwipe = shownKey
                        val action = when {
                            d < -width * SWIPE_FRACTION && goNext != null -> { direction.next(); goNext }
                            d > width * SWIPE_FRACTION && goPrevious != null -> { direction.previous(); goPrevious }
                            else -> null
                        }
                        scope.launch {
                            if (action == null) {
                                drag.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                                return@launch
                            }
                            action()
                            // Keep it moving out while the next song loads; back if the song stays (Previous restarts it).
                            launch { drag.animateTo(if (d < 0) -width * 0.6f else width * 0.6f, tween(250)) }
                            delay(SWIPE_WAIT_MS)
                            if (shownKey == keyAtSwipe) drag.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    val towardNext = drag.value + amount < 0
                    val allowed = if (towardNext) swipeNext != null else swipePrevious != null
                    scope.launch { drag.snapTo(drag.value + amount * if (allowed) 1f else 0.25f) }
                }
            },
    ) {
        outgoing?.let { (url, description) ->
            LivingCover(
                url,
                description,
                isPlaying = false,
                shape = shape,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val p = if (pending) 0f else slide.value
                        val from = if (pending) drag.value else leavingFrom
                        val to = (if (forward) -1f else 1f) * width * 1.15f
                        translationX = from + (to - from) * p
                        alpha = 1f - 0.6f * p
                        val s = 1f - 0.12f * p
                        scaleX = s
                        scaleY = s
                    },
            )
        }
        LivingCover(
            imageUrl,
            contentDescription,
            isPlaying = isPlaying,
            shape = shape,
            motionUrl = motion.takeUnless { pending },
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    if (pending) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val p = slide.value
                    translationX = if (p < 1f) (if (forward) 1f else -1f) * width * 1.15f * (1f - p) else drag.value
                    val s = 0.88f + 0.12f * p
                    scaleX = s
                    scaleY = s
                },
        )
    }

    if (pickStyle) CoverStyleSheet(previewImageUrl = imageUrl, onDismiss = { pickStyle = false })
}

private const val SLIDE_MS = 420
private const val SWIPE_FRACTION = 0.22f
private const val SWIPE_WAIT_MS = 1_500L
