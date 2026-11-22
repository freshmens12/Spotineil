package com.spotineil.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotineil.core.model.AccentColor
import com.spotineil.core.model.ThemeMode

/** When true, components request smaller images (Settings → Data saver). */
val LocalDataSaver = staticCompositionLocalOf { false }

/** Extra bottom padding scrolling content needs so it isn't hidden by the mini player / nav bar. */
val LocalBottomContentPadding = staticCompositionLocalOf<Dp> { 0.dp }

val SpotineilShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun darkScheme(accent: Color, amoled: Boolean): ColorScheme {
    val bg = if (amoled) Color.Black else Color(0xFF121212)
    return darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        primaryContainer = accent.copy(alpha = 0.24f).compositeOver(bg),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFB3B3B3),
        onSecondary = Color.Black,
        secondaryContainer = if (amoled) Color(0xFF1A1A1A) else Color(0xFF2A2A2A),
        onSecondaryContainer = Color.White,
        tertiary = accent,
        background = bg,
        onBackground = Color.White,
        surface = bg,
        onSurface = Color.White,
        surfaceVariant = if (amoled) Color(0xFF121212) else Color(0xFF242424),
        onSurfaceVariant = Color(0xFFB3B3B3),
        surfaceContainerLowest = bg,
        surfaceContainerLow = if (amoled) Color(0xFF0A0A0A) else Color(0xFF181818),
        surfaceContainer = if (amoled) Color(0xFF101010) else Color(0xFF1E1E1E),
        surfaceContainerHigh = if (amoled) Color(0xFF161616) else Color(0xFF282828),
        surfaceContainerHighest = if (amoled) Color(0xFF1E1E1E) else Color(0xFF323232),
        outline = Color(0xFF535353),
        outlineVariant = Color(0xFF3A3A3A),
        error = Color(0xFFF15E6C),
        onError = Color.Black,
    )
}

private fun lightScheme(accent: Color): ColorScheme {
    // Darken the accent for sufficient contrast on light surfaces.
    val primary = if (accent.luminance() > 0.4f) accent.darken(0.35f) else accent
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color.White),
        onPrimaryContainer = Color(0xFF0B2915),
        secondary = Color(0xFF5E5E5E),
        secondaryContainer = Color(0xFFEDEDED),
        onSecondaryContainer = Color(0xFF121212),
        tertiary = primary,
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF121212),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF121212),
        surfaceVariant = Color(0xFFEFEFEF),
        onSurfaceVariant = Color(0xFF5E5E5E),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF5F5F5),
        surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerHigh = Color(0xFFEAEAEA),
        surfaceContainerHighest = Color(0xFFE2E2E2),
        outline = Color(0xFFB0B0B0),
        outlineVariant = Color(0xFFDADADA),
    )
}

// ---------------------------------------------------------------------------------------------
// Soft glass: a dark, softly coloured backdrop with translucent, lightly outlined panels.
// ---------------------------------------------------------------------------------------------

/** True in the Soft glass theme (panels are translucent and sit on the coloured backdrop). */
val LocalGlass = staticCompositionLocalOf { false }

private val GlassTint = Color.White
private val GlassBase = Color(0xFF15152E)

private fun glassScheme(accent: Color): ColorScheme {
    val base = GlassBase
    return darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        primaryContainer = accent.copy(alpha = 0.30f).compositeOver(base),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD0D0EC),
        onSecondary = Color.Black,
        secondaryContainer = GlassTint.copy(alpha = 0.14f),
        onSecondaryContainer = Color.White,
        tertiary = accent,
        // Transparent, so the backdrop shows through every screen.
        background = Color.Transparent,
        onBackground = Color.White,
        surface = Color.Transparent,
        onSurface = Color.White,
        surfaceVariant = GlassTint.copy(alpha = 0.10f),
        onSurfaceVariant = Color(0xFFCFCFE8),
        surfaceContainerLowest = Color.Transparent,
        surfaceContainerLow = GlassTint.copy(alpha = 0.06f),
        surfaceContainer = GlassTint.copy(alpha = 0.09f),
        surfaceContainerHigh = GlassTint.copy(alpha = 0.12f),
        surfaceContainerHighest = GlassTint.copy(alpha = 0.16f),
        inverseSurface = Color.White.copy(alpha = 0.92f),
        inverseOnSurface = base,
        outline = GlassTint.copy(alpha = 0.38f),
        outlineVariant = GlassTint.copy(alpha = 0.18f),
        error = Color(0xFFFF8A95),
        onError = Color.Black,
    )
}

/** The glass backdrop: deep navy with soft blobs of the accent, violet and cyan. */
private fun Modifier.glassBackdrop(accent: Color): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF0D1230), Color(0xFF1A1745), Color(0xFF0B2E3E)),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        ),
    )
    fun blob(color: Color, cx: Float, cy: Float, radius: Float) = drawRect(
        Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(w * cx, h * cy), radius = radius),
    )
    blob(accent.copy(alpha = 0.34f), 0.12f, 0.10f, w * 0.95f)
    blob(Color(0xFF8B6CFF).copy(alpha = 0.30f), 0.95f, 0.45f, w * 0.95f)
    blob(Color(0xFF22D3EE).copy(alpha = 0.20f), 0.15f, 0.95f, w * 0.85f)
}

/** A frosted panel: translucent white with a soft light edge. Falls back to a plain tint outside the glass theme. */
@Composable
fun Modifier.glassPanel(shape: Shape, fill: Float = 0.12f, scrim: Float = 0f): Modifier =
    if (!LocalGlass.current) this
    else this
        .clip(shape)
        .background(GlassBase.copy(alpha = scrim))
        .background(GlassTint.copy(alpha = fill))
        .border(1.dp, Brush.verticalGradient(listOf(GlassTint.copy(alpha = 0.38f), GlassTint.copy(alpha = 0.08f))), shape)

/**
 * The colour to paint under a full-screen layer that must hide what is behind it (the full player).
 * In Soft glass the theme's own background is transparent, so a screen drawn on top would show the
 * page underneath through it; this is the solid base colour of the glass backdrop instead.
 */
@Composable
fun opaqueBackground(): Color = if (LocalGlass.current) GlassBase else MaterialTheme.colorScheme.background

// Dialogs, sheets and menus sit over content, so in the glass theme they get a dark, mostly opaque glass.
private val GlassOverlay = Color(0xFF22224A).copy(alpha = 0.94f)

@Composable
fun dialogContainer(): Color = if (LocalGlass.current) GlassOverlay else MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
fun sheetContainer(): Color = if (LocalGlass.current) GlassOverlay else MaterialTheme.colorScheme.surfaceContainerLow

@Composable
fun menuContainer(): Color = if (LocalGlass.current) GlassOverlay else MaterialTheme.colorScheme.surfaceContainer

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

private fun Color.darken(fraction: Float) = Color(red * (1 - fraction), green * (1 - fraction), blue * (1 - fraction), alpha)

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark, ThemeMode.Amoled, ThemeMode.Glass -> true
}

@Composable
fun SpotineilTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    accent: AccentColor = AccentColor.Green,
    dataSaver: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accentColor = Color(accent.argb)
    val glass = themeMode == ThemeMode.Glass
    val dark = isAppInDarkTheme(themeMode)
    val scheme = when {
        glass -> glassScheme(accentColor)
        dark -> darkScheme(accentColor, amoled = themeMode == ThemeMode.Amoled)
        else -> lightScheme(accentColor)
    }
    CompositionLocalProvider(LocalDataSaver provides dataSaver, LocalGlass provides glass) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SpotineilTypography,
            shapes = SpotineilShapes,
        ) {
            // Always the same Box: wrapping the content only for one theme would rebuild the whole app
            // (and send it back to Home) every time the theme is switched to or from Soft glass.
            Box(Modifier.fillMaxSize().then(if (glass) Modifier.glassBackdrop(accentColor) else Modifier)) { content() }
        }
    }
}
