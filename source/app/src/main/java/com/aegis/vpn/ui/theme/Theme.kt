package com.aegis.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aegis.vpn.data.AccentTheme

@Immutable
data class AegisPalette(
    val accent: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val danger: Color
)

fun paletteFor(accent: AccentTheme): AegisPalette = when (accent) {
    AccentTheme.MINT -> AegisPalette(
        accent = Color(0xFF54E6C1),
        secondary = Color(0xFF6C8CFF),
        tertiary = Color(0xFF9C7CFF),
        background = Color(0xFF070A12),
        surface = Color(0xFF101625),
        surfaceHigh = Color(0xFF171F31),
        textPrimary = Color(0xFFF2F6FF),
        textSecondary = Color(0xFF8D9AB4),
        danger = Color(0xFFFF6B81)
    )
    AccentTheme.BLUE -> AegisPalette(
        accent = Color(0xFF6C8CFF),
        secondary = Color(0xFF54E6C1),
        tertiary = Color(0xFF9C7CFF),
        background = Color(0xFF070A12),
        surface = Color(0xFF101625),
        surfaceHigh = Color(0xFF171F31),
        textPrimary = Color(0xFFF2F6FF),
        textSecondary = Color(0xFF8D9AB4),
        danger = Color(0xFFFF6B81)
    )
    AccentTheme.VIOLET -> AegisPalette(
        accent = Color(0xFF9C7CFF),
        secondary = Color(0xFF6C8CFF),
        tertiary = Color(0xFF54E6C1),
        background = Color(0xFF070A12),
        surface = Color(0xFF101625),
        surfaceHigh = Color(0xFF171F31),
        textPrimary = Color(0xFFF2F6FF),
        textSecondary = Color(0xFF8D9AB4),
        danger = Color(0xFFFF6B81)
    )
    AccentTheme.ORANGE -> AegisPalette(
        accent = Color(0xFFFFB020),
        secondary = Color(0xFFFF7A45),
        tertiary = Color(0xFF6C8CFF),
        background = Color(0xFF0B0907),
        surface = Color(0xFF1A1510),
        surfaceHigh = Color(0xFF241D16),
        textPrimary = Color(0xFFFFF6EC),
        textSecondary = Color(0xFFB8A894),
        danger = Color(0xFFFF6B81)
    )
    AccentTheme.PINK -> AegisPalette(
        accent = Color(0xFFFF6FAE),
        secondary = Color(0xFF9C7CFF),
        tertiary = Color(0xFF6C8CFF),
        background = Color(0xFF0B070A),
        surface = Color(0xFF191018),
        surfaceHigh = Color(0xFF241724),
        textPrimary = Color(0xFFFFF2F8),
        textSecondary = Color(0xFFB89AAC),
        danger = Color(0xFFFF6B81)
    )
    AccentTheme.CYAN -> AegisPalette(
        accent = Color(0xFF38D9FF),
        secondary = Color(0xFF54E6C1),
        tertiary = Color(0xFF6C8CFF),
        background = Color(0xFF060A0C),
        surface = Color(0xFF0E171B),
        surfaceHigh = Color(0xFF152127),
        textPrimary = Color(0xFFF0FBFF),
        textSecondary = Color(0xFF93ADB8),
        danger = Color(0xFFFF6B81)
    )
}

val LocalAegisPalette = staticCompositionLocalOf { paletteFor(AccentTheme.MINT) }

// Backward-compatible static defaults (fallbacks for rare non-composable usage)
val AegisMint = Color(0xFF54E6C1)
val AegisBlue = Color(0xFF6C8CFF)
val AegisViolet = Color(0xFF9C7CFF)
val AegisBackground = Color(0xFF070A12)
val AegisSurface = Color(0xFF101625)
val AegisSurfaceHigh = Color(0xFF171F31)
val AegisTextPrimary = Color(0xFFF2F6FF)
val AegisTextSecondary = Color(0xFF8D9AB4)
val AegisDanger = Color(0xFFFF6B81)

object AppColors {
    val accent: Color @Composable get() = LocalAegisPalette.current.accent
    val blue: Color @Composable get() = LocalAegisPalette.current.secondary
    val violet: Color @Composable get() = LocalAegisPalette.current.tertiary
    val background: Color @Composable get() = LocalAegisPalette.current.background
    val surface: Color @Composable get() = LocalAegisPalette.current.surface
    val surfaceHigh: Color @Composable get() = LocalAegisPalette.current.surfaceHigh
    val textPrimary: Color @Composable get() = LocalAegisPalette.current.textPrimary
    val textSecondary: Color @Composable get() = LocalAegisPalette.current.textSecondary
    val danger: Color @Composable get() = LocalAegisPalette.current.danger
}

@Composable
fun AegisTheme(
    accentTheme: AccentTheme = AccentTheme.MINT,
    content: @Composable () -> Unit
) {
    val palette = paletteFor(accentTheme)
    val colors = darkColorScheme(
        primary = palette.accent,
        onPrimary = Color(0xFF04100D),
        secondary = palette.secondary,
        onSecondary = Color.White,
        tertiary = palette.tertiary,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceHigh,
        onSurfaceVariant = palette.textSecondary,
        error = palette.danger,
        outline = Color(0xFF2B354A)
    )
    CompositionLocalProvider(LocalAegisPalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            typography = AegisTypography,
            content = content
        )
    }
}
