package com.aegis.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aegis.vpn.data.VpnStatus
import com.aegis.vpn.ui.theme.AppColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Живой фон: три мягких световых пятна, которые медленно дрейфуют.
 * Цвет и яркость зависят от состояния VPN, поэтому экран «дышит» вместе
 * с подключением. Рисуется в фазе draw, без рекомпозиции на каждый кадр.
 */
@Composable
fun AuroraBackground(
    status: VpnStatus,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val accent = AppColors.accent
    val blue = AppColors.blue
    val violet = AppColors.violet
    val danger = AppColors.danger

    val colors = when (status) {
        VpnStatus.CONNECTED -> listOf(accent, blue, accent)
        VpnStatus.CONNECTING -> listOf(violet, blue, accent)
        VpnStatus.STOPPING -> listOf(blue, violet, blue)
        VpnStatus.ERROR -> listOf(danger, Color(0xFFB03C5A), Color(0xFFFF8C5A))
        VpnStatus.DISCONNECTED -> listOf(Color(0xFF7E8CAC), Color(0xFF606C8A), Color(0xFF505C78))
    }
    val intensity = when (status) {
        VpnStatus.CONNECTED -> 0.34f
        VpnStatus.CONNECTING -> 0.30f
        VpnStatus.STOPPING -> 0.26f
        VpnStatus.ERROR -> 0.26f
        VpnStatus.DISCONNECTED -> 0.16f
    }

    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 34_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aurora-phase"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val base = minOf(w, h)
            val turn = (phase * 2f * PI).toFloat()

            fun blob(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
                val center = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }

            blob(
                cx = w * (0.16f + 0.09f * sin(turn)),
                cy = h * (0.24f + 0.07f * cos(turn)),
                radius = base * 0.72f,
                color = colors[0],
                alpha = intensity
            )
            blob(
                cx = w * (0.88f + 0.07f * cos(turn + 1.9f)),
                cy = h * (0.44f + 0.09f * sin(turn + 1.2f)),
                radius = base * 0.66f,
                color = colors[1],
                alpha = intensity * 0.85f
            )
            blob(
                cx = w * (0.42f + 0.10f * sin(turn + 3.4f)),
                cy = h * (0.86f + 0.05f * cos(turn + 0.7f)),
                radius = base * 0.58f,
                color = colors[2],
                alpha = intensity * 0.60f
            )
        }
        content()
    }
}
