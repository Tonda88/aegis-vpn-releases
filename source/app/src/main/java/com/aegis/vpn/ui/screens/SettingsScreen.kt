package com.aegis.vpn.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vpn.BuildConfig
import com.aegis.vpn.R
import com.aegis.vpn.data.AccentTheme
import com.aegis.vpn.data.AppSettings
import com.aegis.vpn.ui.components.GlassCard
import com.aegis.vpn.ui.components.ScreenTitle
import com.aegis.vpn.ui.components.SectionLabel
import com.aegis.vpn.ui.components.ToggleRow
import com.aegis.vpn.ui.theme.AppColors
import com.aegis.vpn.ui.theme.paletteFor
import com.aegis.vpn.update.AppUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var foundUpdate by remember { mutableStateOf<AppUpdater.UpdateAvailable?>(null) }
    var showUpToDate by remember { mutableStateOf(false) }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        when (val result = AppUpdater.tryInstall(context)) {
            AppUpdater.InstallResult.Started -> {
                AppUpdater.clearPending(context)
                statusText = "Открываем установку…"
            }
            AppUpdater.InstallResult.NeedPermission -> Unit
            is AppUpdater.InstallResult.Failed -> statusText = "Не удалось открыть установщик: ${result.message}"
            AppUpdater.InstallResult.NoFile -> Unit
        }
    }

    fun startInstall(update: AppUpdater.UpdateAvailable, file: java.io.File) {
        AppUpdater.rememberPending(context, file, update.info.versionName)
        when (val result = AppUpdater.tryInstall(context)) {
            AppUpdater.InstallResult.Started -> {
                AppUpdater.clearPending(context)
                statusText = "Открываем установку…"
            }
            AppUpdater.InstallResult.NeedPermission -> {
                statusText = "Разрешите установку из этого приложения, затем вернитесь"
                runCatching { installPermissionLauncher.launch(AppUpdater.unknownAppSourcesIntent(context)) }
                    .onFailure { statusText = "Не удалось открыть настройки установки: ${it.message}" }
            }
            is AppUpdater.InstallResult.Failed -> {
                statusText = "Не удалось открыть установщик: ${result.message}"
            }
            AppUpdater.InstallResult.NoFile -> Unit
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 30.dp)
    ) {
        item {
            ScreenTitle(
                title = "Настройки",
                subtitle = "Поведение VPN, оформление и параметры системы"
            )
        }

        item { Spacer(Modifier.height(22.dp)) }

        item {
            SectionLabel("Поведение")
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                ToggleRow(
                    title = "Автозапуск",
                    subtitle = "Подготовка автоподключения при загрузке",
                    checked = settings.autoStart,
                    onCheckedChange = { onSettingsChange(settings.copy(autoStart = it)) },
                    icon = R.drawable.ic_power
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 18.dp))
                ToggleRow(
                    title = "Автопереподключение",
                    subtitle = "Пытаться восстановить туннель при обрыве сети",
                    checked = settings.autoReconnect,
                    onCheckedChange = { onSettingsChange(settings.copy(autoReconnect = it)) },
                    icon = R.drawable.ic_refresh
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 18.dp))
                ToggleRow(
                    title = "Лучший сервер по пингу",
                    subtitle = "При подключении измерять задержку всех серверов и выбирать самый быстрый",
                    checked = settings.autoBestServer,
                    onCheckedChange = { onSettingsChange(settings.copy(autoBestServer = it)) },
                    icon = R.drawable.ic_bolt
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(horizontal = 18.dp))
                ToggleRow(
                    title = "Не отключать при выключении экрана",
                    subtitle = "Авто-режим: VPN остаётся активным, если экран погас",
                    checked = settings.keepVpnOnScreenOff,
                    onCheckedChange = { onSettingsChange(settings.copy(keepVpnOnScreenOff = it)) },
                    icon = R.drawable.ic_lock
                )
            }
        }

        item { Spacer(Modifier.height(22.dp)) }

        item {
            SectionLabel("Оформление")
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Цвет темы",
                    color = AppColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Выберите акцентный цвет приложения",
                    color = AppColors.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AccentTheme.entries.forEach { theme ->
                        val p = paletteFor(theme)
                        val selected = settings.accentTheme == theme
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSettingsChange(settings.copy(accentTheme = theme)) }
                                .padding(vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(p.accent)
                                    .then(
                                        if (selected) Modifier.border(2.dp, AppColors.textPrimary.copy(alpha = 0.9f), RoundedCornerShape(9.dp))
                                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                                    )
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = theme.label,
                                color = if (selected) AppColors.textPrimary else AppColors.textSecondary,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }

        item {
            SectionLabel("Обновления")
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), borderColor = AppColors.blue.copy(alpha = 0.16f)) {
                Text(
                    text = "Aegis VPN ${BuildConfig.VERSION_NAME}",
                    color = AppColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Проверка новых версий в публичном репозитории релизов.",
                    color = AppColors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                if (statusText != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = statusText ?: "",
                        color = AppColors.textSecondary,
                        fontSize = 12.sp
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (downloadProgress > 0) downloadProgress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.accent,
                        trackColor = AppColors.surfaceHigh
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Загрузка: ${if (downloadProgress > 0) "$downloadProgress%" else "…"}",
                        color = AppColors.textSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (checking || downloading) return@Button
                        checking = true
                        statusText = "Проверка обновлений…"
                        scope.launch {
                            val result = AppUpdater.checkForUpdate()
                            checking = false
                            result.onSuccess { update ->
                                if (update == null) {
                                    statusText = "Установлена последняя версия"
                                    showUpToDate = true
                                } else {
                                    statusText = "Доступна версия ${update.info.versionName}"
                                    foundUpdate = update
                                }
                            }.onFailure { e ->
                                statusText = "Не удалось проверить: ${e.message ?: "ошибка сети"}"
                            }
                        }
                    },
                    enabled = !checking && !downloading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.blue,
                        contentColor = Color.White,
                        disabledContainerColor = AppColors.surfaceHigh,
                        disabledContentColor = AppColors.textSecondary
                    )
                ) {
                    Text(if (checking) "Проверка…" else "Проверить обновления", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }

        item {
            SectionLabel("Защита")
            Spacer(Modifier.height(10.dp))
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
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Kill switch",
                            color = AppColors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Включается в настройках Android, а не внутри приложения.",
                            color = AppColors.textSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                Spacer(Modifier.height(13.dp))
                Text(
                    text = "Чтобы блокировать сеть без VPN, включите для Aegis VPN опции «Постоянная VPN» и «Блокировать соединения без VPN» в системных настройках.",
                    color = AppColors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure {
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
                    Text("Открыть настройки VPN", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }

        item {
            SectionLabel("О приложении")
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Brush.linearGradient(listOf(AppColors.accent, AppColors.blue))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield),
                            contentDescription = null,
                            tint = Color(0xFF06100E),
                            modifier = Modifier.size(29.dp)
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Aegis VPN",
                            color = AppColors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Версия ${BuildConfig.VERSION_NAME}",
                            color = AppColors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }
                Spacer(Modifier.height(15.dp))
                InfoLine("Ядро", "sing-box 1.14.4")
                InfoLine("Протоколы", "VLESS · Shadowsocks · WireGuard")
                InfoLine("Мин. Android", "7.0")
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Ядро sing-box распространяется по лицензии GPLv3.",
                    color = AppColors.textSecondary.copy(alpha = 0.75f),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )
            }
        }

        item { Spacer(Modifier.height(20.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Сделано для личного пользования",
                    color = AppColors.textSecondary.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        }
    }

    val update = foundUpdate
    if (update != null) {
        AlertDialog(
            onDismissRequest = { if (!downloading) foundUpdate = null },
            title = { Text("Доступно обновление ${update.info.versionName}") },
            text = {
                Column {
                    if (update.info.notes.isNotBlank()) {
                        Text(update.info.notes, fontSize = 13.sp, lineHeight = 18.sp)
                    } else {
                        Text("Новая версия готова к установке.", fontSize = 13.sp)
                    }
                    if (downloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (downloadProgress > 0) downloadProgress / 100f else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (downloading) return@TextButton
                        downloading = true
                        downloadProgress = 0
                        statusText = "Загрузка обновления…"
                        scope.launch {
                            val res = AppUpdater.downloadApk(context, update) { p ->
                                downloadProgress = p
                            }
                            downloading = false
                            res.onSuccess { file ->
                                foundUpdate = null
                                statusText = "Загрузка завершена, запуск установки…"
                                startInstall(update, file)
                            }.onFailure { e ->
                                statusText = "Ошибка загрузки: ${e.message ?: "ошибка"}"
                            }
                        }
                    },
                    enabled = !downloading
                ) { Text(if (downloading) "Загрузка…" else "Скачать и установить") }
            },
            dismissButton = {
                TextButton(
                    onClick = { foundUpdate = null },
                    enabled = !downloading
                ) { Text("Позже") }
            }
        )
    }

    if (showUpToDate) {
        AlertDialog(
            onDismissRequest = { showUpToDate = false },
            title = { Text("Обновлений нет") },
            text = { Text("Установлена последняя версия ${BuildConfig.VERSION_NAME}.") },
            confirmButton = {
                TextButton(onClick = { showUpToDate = false }) { Text("ОК") }
            }
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = AppColors.textSecondary,
            fontSize = 11.5.sp,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            color = AppColors.textPrimary.copy(alpha = 0.88f),
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
