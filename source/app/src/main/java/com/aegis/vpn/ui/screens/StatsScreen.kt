package com.aegis.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.vpn.AppGraph
import com.aegis.vpn.R
import com.aegis.vpn.data.TrafficStore
import com.aegis.vpn.ui.components.GlassCard
import com.aegis.vpn.ui.components.ScreenTitle
import com.aegis.vpn.ui.components.SectionLabel
import com.aegis.vpn.ui.theme.AppColors
import com.aegis.vpn.util.formatBytes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val WEEKDAYS = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")

@Composable
fun StatsScreen() {
    val traffic = remember { AppGraph.traffic }
    val daily by traffic.daily.collectAsStateWithLifecycle()
    val summary = remember(daily) { traffic.summary }
    val week = remember(daily) { traffic.lastDays(7) }
    var confirmReset by remember { mutableStateOf(false) }

    val hasData = summary.allTime.total > 0
    val todayKey = remember { DAY_FORMAT.format(java.util.Date()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenTitle(
                title = "Статистика",
                subtitle = "Трафик, прошедший через Aegis VPN"
            )
        }

        item {
            TodayCard(today = summary.today)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PeriodCard("Неделя", summary.week.total, Modifier.weight(1f))
                PeriodCard("Месяц", summary.month.total, Modifier.weight(1f))
                PeriodCard("Всё время", summary.allTime.total, Modifier.weight(1f))
            }
        }

        item {
            WeekChart(days = week, todayKey = todayKey)
        }

        item {
            TotalsCard(rx = summary.allTime.rx, tx = summary.allTime.tx)
        }

        if (!hasData) {
            item {
                Text(
                    text = "Статистика начнёт заполняться, как только вы подключитесь к VPN. " +
                        "Данные хранятся только на этом устройстве.",
                    color = AppColors.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                )
            }
        }

        item {
            TextButton(
                onClick = { confirmReset = true },
                enabled = hasData,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Сбросить статистику",
                    color = if (hasData) AppColors.danger else AppColors.textSecondary.copy(alpha = 0.45f),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = AppColors.surfaceHigh,
            title = { Text("Сбросить статистику?", color = AppColors.textPrimary) },
            text = {
                Text(
                    text = "История трафика по дням будет удалена без возможности восстановления.",
                    color = AppColors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    traffic.reset()
                    confirmReset = false
                }) {
                    Text("Сбросить", color = AppColors.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("Отмена", color = AppColors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun TodayCard(today: TrafficStore.PeriodTotals) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Сегодня")
        Spacer(Modifier.height(10.dp))
        Text(
            text = formatBytes(today.total),
            color = AppColors.textPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrafficPill(
                icon = R.drawable.ic_download,
                label = "Принято",
                value = formatBytes(today.rx),
                color = AppColors.blue,
                modifier = Modifier.weight(1f)
            )
            TrafficPill(
                icon = R.drawable.ic_upload,
                label = "Отдано",
                value = formatBytes(today.tx),
                color = AppColors.violet,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TrafficPill(
    icon: Int,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                text = label,
                color = AppColors.textSecondary,
                fontSize = 9.5.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = value,
                color = color,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PeriodCard(label: String, total: Long, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(AppColors.surface.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatBytes(total),
            color = AppColors.textPrimary,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = AppColors.textSecondary,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun WeekChart(days: List<TrafficStore.DayStat>, todayKey: String) {
    val max = days.maxOfOrNull { it.total } ?: 0L

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Последние 7 дней")
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEach { day ->
                val isToday = day.key == todayKey
                val fraction = if (max <= 0L) 0f else (day.total.toFloat() / max).coerceIn(0f, 1f)
                val barHeight = if (day.total > 0L) (fraction * 92f).coerceAtLeast(6f) else 2f
                val barTop = if (isToday) AppColors.accent else AppColors.accent.copy(alpha = 0.42f)
                val barBottom = if (isToday) AppColors.blue else AppColors.blue.copy(alpha = 0.20f)

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (day.total > 0L) formatBytes(day.total) else "—",
                        color = if (isToday) AppColors.textPrimary else AppColors.textSecondary,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .height(92.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.58f)
                                .height(barHeight.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Brush.verticalGradient(listOf(barTop, barBottom)))
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = weekday(day.key),
                        color = if (isToday) AppColors.textPrimary else AppColors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(rx: Long, tx: Long) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Всего через VPN")
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TotalColumn(
                icon = R.drawable.ic_download,
                label = "Принято",
                value = formatBytes(rx),
                color = AppColors.blue,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(38.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            TotalColumn(
                icon = R.drawable.ic_upload,
                label = "Отдано",
                value = formatBytes(tx),
                color = AppColors.violet,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun TotalColumn(
    icon: Int,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                color = AppColors.textSecondary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = AppColors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun weekday(key: String): String = runCatching {
    val cal = Calendar.getInstance()
    cal.time = DAY_FORMAT.parse(key) ?: return@runCatching ""
    WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
}.getOrDefault("")
