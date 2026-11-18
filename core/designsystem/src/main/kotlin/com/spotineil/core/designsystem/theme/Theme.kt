package com.spotineil.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
    ThemeMode.Dark, ThemeMode.Amoled -> true
}

@Composable
fun SpotineilTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    accent: AccentColor = AccentColor.Green,
    dataSaver: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accentColor = Color(accent.argb)
    val dark = isAppInDarkTheme(themeMode)
    val scheme = if (dark) darkScheme(accentColor, amoled = themeMode == ThemeMode.Amoled) else lightScheme(accentColor)
    CompositionLocalProvider(LocalDataSaver provides dataSaver) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SpotineilTypography,
            shapes = SpotineilShapes,
            content = content,
        )
    }
}
