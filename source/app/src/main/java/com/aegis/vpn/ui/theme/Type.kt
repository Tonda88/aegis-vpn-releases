package com.aegis.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aegis.vpn.R

/**
 * Manrope встроен в приложение (OFL), без загрузки из сети.
 * Веса: 400, 500, 600, 700, 800.
 */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold)
)

/**
 * Табличные цифры: ширина каждого знака одинаковая, поэтому трафик,
 * скорость и таймер не «дёргают» вёрстку при обновлении.
 */
private const val TABULAR = "tnum"

private fun style(
    weight: FontWeight,
    size: Float,
    lineHeight: Float,
    letterSpacing: Float = 0f
) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    fontFeatureSettings = TABULAR
)

val AegisTypography = Typography(
    displayLarge = style(FontWeight.ExtraBold, 56f, 60f, -1.5f),
    headlineLarge = style(FontWeight.ExtraBold, 30f, 36f, -0.5f),
    headlineMedium = style(FontWeight.SemiBold, 23f, 30f),
    titleLarge = style(FontWeight.SemiBold, 19f, 26f),
    titleMedium = style(FontWeight.SemiBold, 16f, 22f),
    bodyLarge = style(FontWeight.Normal, 15f, 22f),
    bodyMedium = style(FontWeight.Normal, 13.5f, 19f),
    labelLarge = style(FontWeight.SemiBold, 14f, 18f),
    labelMedium = style(FontWeight.Medium, 11.5f, 15f, 0.4f)
)
