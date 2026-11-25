@file:OptIn(ExperimentalMaterial3Api::class)

package com.aegis.vpn.ui.components

import com.aegis.vpn.ui.theme.AppColors

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vpn.R

enum class AppTab(val label: String, @DrawableRes val icon: Int) {
    HOME("Главная", R.drawable.ic_shield),
    SERVERS("Серверы", R.drawable.ic_server),
    STATS("Статистика", R.drawable.ic_stats),
    APPS("Приложения", R.drawable.ic_apps),
    SETTINGS("Настройки", R.drawable.ic_settings)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    borderColor: Color = Color.White.copy(alpha = 0.07f),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        AppColors.surfaceHigh.copy(alpha = 0.88f),
                        AppColors.surface.copy(alpha = 0.72f)
                    )
                )
            )
            .border(1.dp, borderColor, shape)
            .padding(padding),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = AppColors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        modifier = modifier
    )
}

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun ScreenTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = AppColors.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = AppColors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        actions()
    }
}

@Composable
fun IconCircleButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AppColors.textPrimary,
    background: Color = AppColors.surfaceHigh
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.07f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
fun StatBlock(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(AppColors.surface.copy(alpha = 0.74f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(vertical = 15.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            color = AppColors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = AppColors.textSecondary,
            fontSize = 10.5.sp,
            maxLines = 1
        )
    }
}

@Composable
fun AegisBottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit
) {
    Column(Modifier.background(AppColors.surface.copy(alpha = 0.97f))) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        NavigationBar(
            containerColor = AppColors.surface.copy(alpha = 0.97f),
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding()
        ) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            painter = painterResource(tab.icon),
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppColors.accent,
                        selectedTextColor = AppColors.accent,
                        unselectedIconColor = AppColors.textSecondary,
                        unselectedTextColor = AppColors.textSecondary,
                        indicatorColor = AppColors.accent.copy(alpha = 0.12f)
                    )
                )
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.accent.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = AppColors.accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = AppColors.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = AppColors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04100D),
                checkedTrackColor = AppColors.accent,
                checkedBorderColor = AppColors.accent,
                uncheckedThumbColor = AppColors.textSecondary,
                uncheckedTrackColor = AppColors.surfaceHigh,
                uncheckedBorderColor = Color.White.copy(alpha = 0.12f)
            )
        )
    }
}

@Composable
fun VerticalGlowDivider(padding: Dp = 0.dp) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = padding)
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent
                    )
                )
            )
    )
}
