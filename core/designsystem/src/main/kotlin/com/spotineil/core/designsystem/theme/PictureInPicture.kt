package com.spotineil.core.designsystem.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Picture-in-picture bridge between the activity (which owns the PiP window) and the screen that
 * can use it (YouTube's player). A screen sets [eligible] while a video is on screen; the activity
 * then shrinks into a floating window when the user presses Home / opens Recents, and reports back
 * through [isInPip]. The window's ⏯ / ⏭ buttons call [onPlayPause] / [onNext].
 */
class PipState {
    /** True while a screen wants PiP on leaving the app (a YouTube video is showing). */
    var eligible by mutableStateOf(false)

    /** Mirrors the player, to show ⏯ correctly in the PiP window. */
    var isPlaying by mutableStateOf(false)

    var hasNext by mutableStateOf(false)

    /** Set by the activity: the app is currently shown as a floating PiP window. */
    var isInPip by mutableStateOf(false)

    var onPlayPause: () -> Unit = {}
    var onNext: () -> Unit = {}
}

/** Default instance does nothing (previews, tests, screens outside the app). */
val LocalPipState = staticCompositionLocalOf { PipState() }
