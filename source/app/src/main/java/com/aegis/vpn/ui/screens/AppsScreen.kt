@file:OptIn(ExperimentalMaterial3Api::class)

package com.aegis.vpn.ui.screens

import com.aegis.vpn.ui.theme.AppColors

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aegis.vpn.R
import com.aegis.vpn.core.UsageAccess
import com.aegis.vpn.data.AppListMode
import com.aegis.vpn.data.AppSettings
import com.aegis.vpn.data.PresetCategory
import com.aegis.vpn.data.PresetServices
import com.aegis.vpn.ui.components.GlassCard
import com.aegis.vpn.ui.components.ScreenTitle
import com.aegis.vpn.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val isSystem: Boolean
)

private data class AppListData(
    val apps: List<InstalledApp>,
    val installedPackages: Set<String>
)

@Composable
fun AppsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<AppListData?>(null) }
    var query by remember { mutableStateOf("") }
    var usageGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        data = loadInstalledApps(context)
    }

    LifecycleResumeEffect(Unit) {
        usageGranted = UsageAccess.isGranted(context)
        onPauseOrDispose { }
    }

    val loaded = data
    val selected = settings.selectedPackages
    val installed = loaded?.installedPackages.orEmpty()
    val selectedInstalled = selected.count { it in installed }
    val autoApps = settings.autoApps
    val autoMode = settings.appListMode == AppListMode.AUTO
    val filteredApps = remember(loaded, query) {
        val all = loaded?.apps.orEmpty()
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) all
        else all.filter {
            it.label.lowercase().contains(normalized) ||
                it.packageName.lowercase().contains(normalized)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp)
    ) {
        item {
            ScreenTitle(
                title = "Приложения",
                subtitle = "Раздельное туннелирование и автоподключение"
            )
        }

        item { Spacer(Modifier.height(18.dp)) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.surface)
                    .padding(4.dp)
            ) {
                ModeOption(
                    title = "Туннелирование",
                    selected = !autoMode,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (autoMode) {
                            onSettingsChange(settings.copy(appListMode = AppListMode.TUNNEL))
                        }
                    }
                )
                ModeOption(
                    title = "Автоподключение",
                    selected = autoMode,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!autoMode) {
                            onSettingsChange(settings.copy(appListMode = AppListMode.AUTO))
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(18.dp)) }

        if (autoMode) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = AppColors.accent.copy(alpha = 0.16f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(AppColors.accent.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = null,
                                tint = AppColors.accent,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Автоподключение по приложениям",
                                color = AppColors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Пока выбранное приложение на экране — VPN включается. При выходе из приложения VPN отключается.",
                                color = AppColors.textSecondary,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    if (!usageGranted) {
                        Spacer(Modifier.height(13.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = AppColors.danger,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Нужен доступ к статистике использования, чтобы определять активное приложение.",
                                color = AppColors.danger,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(UsageAccess.settingsIntent(context))
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.accent,
                                contentColor = Color(0xFF06100E)
                            )
                        ) {
                            Text(
                                "Выдать доступ к статистике",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (autoApps.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Правил настроено: ${autoApps.size}. VPN включится, когда выбранное приложение окажется на экране.",
                            color = AppColors.textSecondary.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        } else {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionLabel("Режим работы")
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppColors.surface)
                            .padding(4.dp)
                    ) {
                        ModeOption(
                            title = "Исключать",
                            selected = !settings.includeMode,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (settings.includeMode) {
                                    onSettingsChange(settings.copy(includeMode = false))
                                }
                            }
                        )
                        ModeOption(
                            title = "Только выбранные",
                            selected = settings.includeMode,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!settings.includeMode) {
                                    onSettingsChange(settings.copy(includeMode = true))
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(11.dp))
                    Text(
                        text = if (settings.includeMode) {
                            "Через VPN работают только выбранные приложения. Остальные подключаются напрямую."
                        } else {
                            "Все приложения работают через VPN, кроме выбранных. Они подключаются напрямую."
                        },
                        color = AppColors.textSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp
                    )
                    if (settings.includeMode && selectedInstalled == 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = AppColors.danger,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Пока ничего не выбрано — VPN не будет применяться к приложениям.",
                                color = AppColors.danger,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(22.dp)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("Быстрые наборы")
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Выбрано: $selectedInstalled",
                        color = AppColors.textSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }

            item { Spacer(Modifier.height(10.dp)) }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PresetServices.categories, key = { it.id }) { category ->
                        val installedForPreset = category.packages.filter { it in installed }
                        PresetChip(
                            category = category,
                            installedCount = installedForPreset.size,
                            selectedCount = installedForPreset.count { it in selected },
                            onClick = {
                                val updated = selected.toMutableSet()
                                val allSelected = installedForPreset.isNotEmpty() &&
                                    installedForPreset.all { it in selected }
                                if (allSelected) updated.removeAll(installedForPreset.toSet())
                                else updated.addAll(installedForPreset)
                                onSettingsChange(settings.copy(selectedPackages = updated))
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(22.dp)) }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text("Найти приложение", color = AppColors.textSecondary, fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AppColors.surface.copy(alpha = 0.7f),
                    unfocusedContainerColor = AppColors.surface.copy(alpha = 0.7f),
                    focusedBorderColor = AppColors.accent.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.07f),
                    focusedTextColor = AppColors.textPrimary,
                    unfocusedTextColor = AppColors.textPrimary,
                    cursorColor = AppColors.accent
                )
            )
        }

        item { Spacer(Modifier.height(12.dp)) }

        if (loaded == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 50.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppColors.accent, strokeWidth = 2.dp)
                }
            }
        } else if (filteredApps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ничего не найдено",
                        color = AppColors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(filteredApps, key = { it.packageName }) { app ->
                if (autoMode) {
                    AutoAppRow(
                        app = app,
                        enabled = app.packageName in autoApps,
                        onToggle = { enabled ->
                            val updated = autoApps.toMutableSet()
                            if (enabled) updated.add(app.packageName) else updated.remove(app.packageName)
                            onSettingsChange(settings.copy(autoApps = updated))
                        }
                    )
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.045f),
                        modifier = Modifier.padding(start = 56.dp)
                    )
                } else {
                    AppRow(
                        app = app,
                        checked = app.packageName in selected,
                        includeMode = settings.includeMode,
                        onToggle = { enabled ->
                            val updated = selected.toMutableSet()
                            if (enabled) updated.add(app.packageName) else updated.remove(app.packageName)
                            onSettingsChange(settings.copy(selectedPackages = updated))
                        }
                    )
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.045f),
                        modifier = Modifier.padding(start = 56.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppColors.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) AppColors.accent.copy(alpha = 0.3f) else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) AppColors.accent else AppColors.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun PresetChip(
    category: PresetCategory,
    installedCount: Int,
    selectedCount: Int,
    onClick: () -> Unit
) {
    val available = installedCount > 0
    val active = selectedCount > 0
    Row(
        modifier = Modifier
            .alpha(if (available) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .background(if (active) AppColors.accent.copy(alpha = 0.1f) else AppColors.surface.copy(alpha = 0.8f))
            .border(
                1.dp,
                if (active) AppColors.accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(50)
            )
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AppColors.accent)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = category.title,
            color = if (active) AppColors.textPrimary else AppColors.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
        if (available) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (selectedCount > 0) "$selectedCount/$installedCount" else "$installedCount",
                color = if (active) AppColors.accent else AppColors.textSecondary.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun AutoAppRow(
    app: InstalledApp,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = AppColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.packageName,
                    color = AppColors.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (enabled) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "АВТО",
                        color = AppColors.accent,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04100D),
                checkedTrackColor = AppColors.accent,
                checkedBorderColor = AppColors.accent,
                uncheckedThumbColor = AppColors.textSecondary,
                uncheckedTrackColor = AppColors.surfaceHigh,
                uncheckedBorderColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

@Composable
private fun AppIcon(app: InstalledApp) {
    if (app.icon != null) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.surfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.label.take(1).uppercase(),
                color = AppColors.accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    includeMode: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = AppColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.packageName,
                    color = AppColors.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (checked) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (includeMode) "ЧЕРЕЗ VPN" else "БЕЗ VPN",
                        color = AppColors.blue,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF04100D),
                checkedTrackColor = AppColors.accent,
                checkedBorderColor = AppColors.accent,
                uncheckedThumbColor = AppColors.textSecondary,
                uncheckedTrackColor = AppColors.surfaceHigh,
                uncheckedBorderColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

private suspend fun loadInstalledApps(context: Context): AppListData = withContext(Dispatchers.IO) {
    val packageManager = context.packageManager
    val installedPackages = packageManager.getInstalledPackages(0)
    val installedNames = installedPackages.mapTo(mutableSetOf()) { it.packageName }
    val importantPackages = PresetServices.categories.flatMap { it.packages }.toSet()
    val apps = installedPackages.mapNotNull { packageInfo ->
        val packageName = packageInfo.packageName
        if (packageName == context.packageName) return@mapNotNull null
        val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
        val launchable = packageManager.getLaunchIntentForPackage(packageName) != null
        if (!launchable && packageName !in importantPackages) return@mapNotNull null
        val icon = runCatching {
            appInfo.loadIcon(packageManager).toBitmap(width = 96, height = 96).asImageBitmap()
        }.getOrNull()
        InstalledApp(
            packageName = packageName,
            label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                .getOrDefault(packageName),
            icon = icon,
            isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        )
    }.sortedBy { it.label.lowercase() }

    AppListData(apps, installedNames)
}
