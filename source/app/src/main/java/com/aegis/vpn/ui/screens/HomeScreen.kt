package com.aegis.vpn.ui.screens

import com.aegis.vpn.ui.theme.AppColors

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vpn.R
import com.aegis.vpn.data.AppSettings
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnSnapshot
import com.aegis.vpn.data.VpnStatus
import com.aegis.vpn.ui.components.GlassCard
import com.aegis.vpn.ui.components.Pill
import com.aegis.vpn.ui.components.SectionLabel
import com.aegis.vpn.ui.components.StatBlock
import com.aegis.vpn.util.formatBytes
import com.aegis.vpn.util.formatDuration
import com.aegis.vpn.util.formatPing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

@Composable
fun HomeScreen(
    server: ServerProfile?,
    settings: AppSettings,
    vpnState: VpnSnapshot,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenApps: () -> Unit,
    onDismissError: () -> Unit
) {
    var ping by remember(server?.id) { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val status = vpnState.status

    LaunchedEffect(server?.id) {
        ping = null
        while (server != null) {
            ping = measureTcpPing(server.host, server.port)
            delay(20_000)
        }
    }

    LaunchedEffect(status, vpnState.connectedSince) {
        while (status == VpnStatus.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val durationSeconds = if (status == VpnStatus.CONNECTED && vpnState.connectedSince > 0) {
        ((now - vpnState.connectedSince) / 1000).coerceAtLeast(0)
    } else 0L

    val excludedCount = settings.selectedPackages.size
    val activeColor = when (status) {
        VpnStatus.CONNECTED -> AppColors.accent
        VpnStatus.CONNECTING -> AppColors.violet
        VpnStatus.STOPPING -> AppColors.blue
        VpnStatus.ERROR -> AppColors.danger
        VpnStatus.DISCONNECTED -> AppColors.textSecondary
    }
    val statusText = when (status) {
        VpnStatus.CONNECTED -> "ЗАЩИЩЕНО"
        VpnStatus.CONNECTING -> "ПОДКЛЮЧЕНИЕ"
        VpnStatus.STOPPING -> "ОТКЛЮЧЕНИЕ"
        VpnStatus.ERROR -> "ОШИБКА"
        VpnStatus.DISCONNECTED -> "НЕ ПОДКЛЮЧЕНО"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(AppColors.accent, AppColors.blue))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = Color(0xFF06100E),
                        modifier = Modifier.size(27.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "AEGIS VPN",
                        color = AppColors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp
                    )
                    Text(
                        text = "приватность без компромиссов",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp,
                        letterSpacing = 0.3.sp
                    )
                }
                Pill(statusText, activeColor, dot = status == VpnStatus.CONNECTED)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                PowerButton(
                    status = status,
                    hasServer = server != null,
                    onClick = {
                        when (status) {
                            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> onDisconnect()
                            VpnStatus.STOPPING -> Unit
                            else -> onConnect()
                        }
                    }
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (status) {
                        VpnStatus.CONNECTED -> "Соединение защищено"
                        VpnStatus.CONNECTING -> "Устанавливаем туннель…"
                        VpnStatus.STOPPING -> "Отключаемся…"
                        VpnStatus.ERROR -> "Не удалось подключиться"
                        VpnStatus.DISCONNECTED -> if (server == null) "Добавьте первый сервер" else "VPN отключён"
                    },
                    color = AppColors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = when (status) {
                        VpnStatus.CONNECTED -> "Сеанс ${formatDuration(durationSeconds)}"
                        VpnStatus.ERROR -> "Проверьте ключ или смените сервер"
                        else -> "Нажмите кнопку, чтобы ${if (status == VpnStatus.CONNECTING) "отменить подключение" else "включить защиту"}"
                    },
                    color = if (status == VpnStatus.ERROR) AppColors.danger else AppColors.textSecondary,
                    fontSize = 13.sp
                )
            }
        }

        if (status == VpnStatus.ERROR && vpnState.error != null) {
            item {
                Spacer(Modifier.height(18.dp))
                ErrorCard(
                    message = vpnState.error,
                    onRetry = onConnect,
                    onDismiss = onDismissError
                )
            }
        }

        item { Spacer(Modifier.height(28.dp)) }

        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenServers)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AppColors.accent.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_server),
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Текущий сервер")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = server?.name ?: "Сервер не выбран",
                            color = AppColors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = server?.let { "${it.protocol.shortLabel} · ${it.endpointLabel}" }
                                ?: "Нажмите, чтобы добавить",
                            color = AppColors.textSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 1
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron),
                        contentDescription = "Открыть серверы",
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(14.dp)) }

        // Auto-connect badge — shown when at least one app rule is configured.
        if (settings.autoApps.isNotEmpty()) {
            item {
                val autoActive = status == VpnStatus.CONNECTED
                val autoError = status == VpnStatus.ERROR
                val badgeColor = when {
                    autoError -> AppColors.danger
                    autoActive -> AppColors.accent
                    else -> AppColors.textSecondary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(badgeColor.copy(alpha = 0.08f))
                        .clickable(onClick = onOpenApps)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = when {
                            autoError -> "Авто-режим: ошибка подключения"
                            autoActive -> "Авто-режим активен · ${settings.autoApps.size} прил."
                            else -> "Авто-режим ожидает · ${settings.autoApps.size} прил."
                        },
                        color = badgeColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = badgeColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBlock(
                    icon = R.drawable.ic_bolt,
                    label = "Пинг",
                    value = formatPing(ping),
                    accent = AppColors.accent,
                    modifier = Modifier.weight(1f)
                )
                StatBlock(
                    icon = R.drawable.ic_download,
                    label = "Принято",
                    value = formatBytes(vpnState.rxBytes),
                    accent = AppColors.blue,
                    modifier = Modifier.weight(1f)
                )
                StatBlock(
                    icon = R.drawable.ic_upload,
                    label = "Отдано",
                    value = formatBytes(vpnState.txBytes),
                    accent = AppColors.violet,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { Spacer(Modifier.height(14.dp)) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppColors.surfaceHigh.copy(alpha = 0.45f))
                    .clickable(onClick = onOpenApps)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = AppColors.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (settings.includeMode) {
                            "Через VPN: $excludedCount прил."
                        } else {
                            "В обход VPN: $excludedCount прил."
                        },
                        color = AppColors.textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Настроить раздельное туннелирование",
                        color = AppColors.textSecondary,
                        fontSize = 10.5.sp
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Kill switch включается в системных настройках: VPN → «Постоянная VPN» и «Блокировать соединения без VPN».",
                color = AppColors.textSecondary.copy(alpha = 0.8f),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@Composable
private fun PowerButton(
    status: VpnStatus,
    hasServer: Boolean,
    onClick: () -> Unit
) {
    val accent = when (status) {
        VpnStatus.CONNECTED -> AppColors.accent
        VpnStatus.CONNECTING -> AppColors.violet
        VpnStatus.STOPPING -> AppColors.blue
        VpnStatus.ERROR -> AppColors.danger
        VpnStatus.DISCONNECTED -> if (hasServer) AppColors.accent else AppColors.blue
    }
    val active = status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING
    val infinite = rememberInfiniteTransition(label = "connection-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.22f,
        targetValue = if (active) 0.95f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 1600 else 2600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier.size(268.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = this.center
            val outerRadius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                    center = center,
                    radius = outerRadius
                ),
                radius = outerRadius,
                center = center
            )
            drawCircle(
                color = accent.copy(alpha = 0.18f + 0.12f * (1f - pulse)),
                radius = outerRadius * (0.72f + 0.23f * pulse),
                center = center,
                style = Stroke(width = 1.3.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.045f),
                radius = outerRadius * 0.77f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = outerRadius * 0.77f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Box(
            modifier = Modifier
                .size(164.dp)
                .shadow(
                    elevation = if (active) 42.dp else 22.dp,
                    shape = CircleShape,
                    ambientColor = accent.copy(alpha = 0.55f),
                    spotColor = accent.copy(alpha = 0.55f)
                )
                .clip(CircleShape)
                .background(
                    if (active) {
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.95f), AppColors.blue.copy(alpha = 0.9f)),
                            start = Offset.Zero,
                            end = Offset.Infinite
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(AppColors.surfaceHigh, Color(0xFF131D2F)),
                            start = Offset.Zero,
                            end = Offset.Infinite
                        )
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (active) Color.White.copy(alpha = 0.34f) else accent.copy(alpha = 0.55f),
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING) {
                    CircularProgressIndicator(
                        color = if (active) Color(0xFF07100E) else accent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_power),
                        contentDescription = null,
                        tint = if (active) Color(0xFF06100E) else accent,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when (status) {
                        VpnStatus.CONNECTED -> "ОТКЛЮЧИТЬ"
                        VpnStatus.CONNECTING -> "ОТМЕНА"
                        VpnStatus.STOPPING -> "ЖДИТЕ"
                        VpnStatus.ERROR -> "ПОВТОРИТЬ"
                        VpnStatus.DISCONNECTED -> if (hasServer) "ПОДКЛЮЧИТЬ" else "НАЧАТЬ"
                    },
                    color = if (active) Color(0xFF06100E) else AppColors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = AppColors.danger.copy(alpha = 0.28f),
        padding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = AppColors.danger,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = message,
                    color = AppColors.textPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        text = "Повторить",
                        color = AppColors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onRetry)
                    )
                    Text(
                        text = "Скрыть",
                        color = AppColors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(onClick = onDismiss)
                    )
                }
            }
        }
    }
}

private suspend fun measureTcpPing(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
    if (host.isBlank() || port !in 1..65535) return@withContext null
    runCatching {
        val started = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2500)
        }
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
    }.getOrNull()
}
