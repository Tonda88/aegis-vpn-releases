package com.aegis.vpn.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    // Brand
    val accent: Color = Color(0xFF873CFF),
    val accentSoft: Color = Color(0xFFA970FF),
    val accentGlow: Color = Color(0xFFA970FF),
    val gradientStart: Color = Color(0xFF873CFF),
    val gradientEnd: Color = Color(0xFF6E2BE0),

    // Backgrounds
    val background: Color = Color(0xFF090914),
    val surface: Color = Color(0xFF111120),
    val surfaceHigh: Color = Color(0xFF171729),
    val surfaceBorder: Color = Color(0xFF25243A),

    // Text
    val textPrimary: Color = Color(0xFFF3F0FF),
    val textSecondary: Color = Color(0xFF9290AA),
    val textMuted: Color = Color(0xFF6B6980),

    // Status
    val connected: Color = Color(0xFF28DFA0),
    val connecting: Color = Color(0xFFA970FF),
    val error: Color = Color(0xFFFF526A),
    val warning: Color = Color(0xFFFFB35C),

    // Glass
    val glassBg: Color = Color(0x0FFFFFFF),
    val glassBorder: Color = Color(0x1AFFFFFF),
    val glassHighlight: Color = Color(0x08FFFFFF),
)

val LocalPalette = staticCompositionLocalOf { Palette() }

object C {
    val accent: Color @Composable get() = LocalPalette.current.accent
    val accentSoft: Color @Composable get() = LocalPalette.current.accentSoft
    val accentGlow: Color @Composable get() = LocalPalette.current.accentGlow
    val gradientStart: Color @Composable get() = LocalPalette.current.gradientStart
    val gradientEnd: Color @Composable get() = LocalPalette.current.gradientEnd

    val bg: Color @Composable get() = LocalPalette.current.background
    val surface: Color @Composable get() = LocalPalette.current.surface
    val surfaceHigh: Color @Composable get() = LocalPalette.current.surfaceHigh
    val surfaceBorder: Color @Composable get() = LocalPalette.current.surfaceBorder

    val text: Color @Composable get() = LocalPalette.current.textPrimary
    val subtext: Color @Composable get() = LocalPalette.current.textSecondary
    val muted: Color @Composable get() = LocalPalette.current.textMuted

    val connected: Color @Composable get() = LocalPalette.current.connected
    val connecting: Color @Composable get() = LocalPalette.current.connecting
    val danger: Color @Composable get() = LocalPalette.current.error
    val warning: Color @Composable get() = LocalPalette.current.warning

    val glassBg: Color @Composable get() = LocalPalette.current.glassBg
    val glassBorder: Color @Composable get() = LocalPalette.current.glassBorder
    val glassHighlight: Color @Composable get() = LocalPalette.current.glassHighlight

    val primaryGradient: Brush @Composable get() =
        Brush.linearGradient(listOf(LocalPalette.current.gradientStart, LocalPalette.current.gradientEnd))

    val glow: Brush @Composable get() =
        Brush.radialGradient(listOf(LocalPalette.current.accentGlow.copy(alpha = 0.30f), Color.Transparent))
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = Palette()
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = Color(0xFF0B0518),
        secondary = p.accentSoft, tertiary = p.accentGlow,
        background = p.background, onBackground = p.textPrimary,
        surface = p.surface, onSurface = p.textPrimary,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textSecondary,
        error = p.error, outline = p.surfaceBorder
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
