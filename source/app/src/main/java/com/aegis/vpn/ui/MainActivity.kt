@file:OptIn(ExperimentalMaterial3Api::class)

package com.aegis.vpn.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.vpn.AppGraph
import com.aegis.vpn.R
import com.aegis.vpn.config.ConfigParser
import com.aegis.vpn.config.Subscription
import com.aegis.vpn.core.AutoConnectController
import com.aegis.vpn.core.VpnController
import com.aegis.vpn.core.VpnStateHolder
import com.aegis.vpn.core.VpnTileService
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.SettingsStore
import com.aegis.vpn.data.VpnStatus
import com.aegis.vpn.ui.components.AegisBottomBar
import com.aegis.vpn.ui.components.AppTab
import com.aegis.vpn.ui.screens.AppsScreen
import com.aegis.vpn.ui.screens.HomeScreen
import com.aegis.vpn.ui.screens.ServersScreen
import com.aegis.vpn.ui.screens.SettingsScreen
import com.aegis.vpn.ui.screens.StatsScreen
import com.aegis.vpn.ui.theme.AegisTheme
import com.aegis.vpn.ui.theme.AppColors
import com.aegis.vpn.update.AppUpdater
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var connectRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectRequested = intent?.getBooleanExtra(VpnTileService.EXTRA_CONNECT_REQUEST, false) == true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val settings by AppGraph.store.settings.collectAsStateWithLifecycle()
            AegisTheme(accentTheme = settings.accentTheme) {
                AegisApp(
                    connectRequested = connectRequested,
                    onConnectRequestConsumed = { connectRequested = false }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(VpnTileService.EXTRA_CONNECT_REQUEST, false)) {
            connectRequested = true
        }
    }
}

@Composable
private fun AegisApp(
    connectRequested: Boolean = false,
    onConnectRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { AppGraph.store }
    val servers by store.servers.collectAsStateWithLifecycle()
    val selectedId by store.selectedId.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val vpnState by VpnStateHolder.state.collectAsStateWithLifecycle()

    val selectedServer = remember(servers, selectedId) {
        servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    }

    val tabs = AppTab.entries
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentTab = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var autoUpdate by remember { mutableStateOf<AppUpdater.UpdateAvailable?>(null) }
    var autoUpdateChecked by rememberSaveable { mutableStateOf(false) }
    var autoDownloading by remember { mutableStateOf(false) }
    var autoDownloadProgress by remember { mutableIntStateOf(0) }

    val notify: (String) -> Unit = remember(scope, snackbarHostState) {
        { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    }

    val importRaw: (String) -> Boolean = { rawInput ->
        val raw = rawInput.trim().removePrefix("\uFEFF")
        if (raw.isBlank()) {
            notify("Вставьте ключ, ссылку или выберите файл")
            false
        } else {
            ConfigParser.parse(raw).fold(
                onSuccess = { parsed ->
                    val profile = ServerProfile(
                        name = parsed.name,
                        protocol = parsed.protocol,
                        host = parsed.host,
                        port = parsed.port,
                        raw = raw
                    )
                    when (store.addServer(profile)) {
                        SettingsStore.AddServerResult.Added -> {
                            if (vpnState.status != VpnStatus.CONNECTED &&
                                vpnState.status != VpnStatus.CONNECTING
                            ) {
                                store.selectServer(profile.id)
                            }
                            notify("Сервер «${profile.name}» добавлен")
                            true
                        }
                        SettingsStore.AddServerResult.Duplicate -> {
                            notify("Этот ключ уже есть в списке")
                            false
                        }
                    }
                },
                onFailure = { error ->
                    notify(error.message?.take(180) ?: "Не удалось разобрать ключ")
                    false
                }
            )
        }
    }

    val importKeys: (List<String>) -> Unit = { keys ->
        var added = 0
        var skipped = 0
        var firstId: String? = null
        keys.forEach { key ->
            ConfigParser.parse(key).fold(
                onSuccess = { parsed ->
                    val profile = ServerProfile(
                        name = parsed.name,
                        protocol = parsed.protocol,
                        host = parsed.host,
                        port = parsed.port,
                        raw = key
                    )
                    when (store.addServer(profile)) {
                        SettingsStore.AddServerResult.Added -> {
                            added++
                            if (firstId == null) firstId = profile.id
                        }
                        SettingsStore.AddServerResult.Duplicate -> skipped++
                    }
                },
                onFailure = { skipped++ }
            )
        }
        if (added == 0) {
            notify("Не удалось добавить серверы: все уже есть или формат не распознан")
        } else {
            if (vpnState.status != VpnStatus.CONNECTED &&
                vpnState.status != VpnStatus.CONNECTING
            ) {
                firstId?.let { store.selectServer(it) }
            }
            val tail = if (skipped > 0) ", пропущено $skipped" else ""
            notify("Добавлено серверов: $added$tail")
        }
    }

    val importInput: (String) -> Unit = { rawInput ->
        val raw = rawInput.trim().removePrefix("\uFEFF")
        when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> {
                scope.launch {
                    notify("Загрузка подписки…")
                    val result = withContext(Dispatchers.IO) {
                        runCatching { Subscription.fetch(raw, store.subscriptionHwid) }
                    }
                    result.fold(
                        onSuccess = { body ->
                            val keys = Subscription.decode(body)
                            if (keys.isEmpty()) {
                                notify("В подписке нет подходящих серверов")
                            } else {
                                importKeys(keys)
                            }
                        },
                        onFailure = { error ->
                            notify("Не удалось загрузить подписку: ${error.message?.take(140) ?: "ошибка сети"}")
                        }
                    )
                }
            }

            Subscription.decode(raw).size > 1 -> importKeys(Subscription.decode(raw))

            else -> importRaw(raw)
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = readTextFromUri(context, uri)
            if (text.isNullOrBlank()) {
                notify("Не удалось прочитать файл")
            } else {
                importInput(text)
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(importInput)
    }

    val launchScan: () -> Unit = {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Наведите камеру на QR-код сервера")
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (pendingConnect) {
            pendingConnect = false
            if (result.resultCode == Activity.RESULT_OK) {
                VpnController.connect(context)
            } else {
                notify("Без разрешения VPN подключение невозможно")
            }
        }
    }

    val connect: () -> Unit = {
        val server = selectedServer
        if (server == null) {
            showAddSheet = true
            notify("Сначала добавьте сервер")
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                pendingConnect = true
                runCatching { vpnPermissionLauncher.launch(prepareIntent) }
                    .onFailure {
                        pendingConnect = false
                        notify("Не удалось открыть системный запрос VPN")
                    }
            } else {
                VpnController.connect(context)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(connectRequested) {
        if (connectRequested) {
            onConnectRequestConsumed()
            if (vpnState.status == VpnStatus.DISCONNECTED || vpnState.status == VpnStatus.ERROR) {
                connect()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (!autoUpdateChecked) {
            autoUpdateChecked = true
            delay(2500)
            val res = AppUpdater.checkForUpdate()
            res.onSuccess { update ->
                if (update != null) autoUpdate = update
            }
        }
    }

    // Keep the background watcher in sync with the per-application rules.
    LaunchedEffect(settings.autoApps) {
        AutoConnectController.sync(context)
    }

    // Recreate the tunnel after split-tunneling changes, with a debounce.
    var splitKey by remember { mutableStateOf(settings.includeMode to settings.selectedPackages) }
    LaunchedEffect(settings.includeMode, settings.selectedPackages) {
        val newKey = settings.includeMode to settings.selectedPackages
        if (newKey == splitKey) return@LaunchedEffect
        splitKey = newKey
        delay(1200)
        if (vpnState.status == VpnStatus.CONNECTED) {
            val server = selectedServer ?: return@LaunchedEffect
            VpnController.connect(context, server.id)
            notify("Настройки обновлены, переподключение…")
        }
    }

    BackHandler(enabled = tabIndex != 0) {
        tabIndex = 0
    }

    Scaffold(
        containerColor = AppColors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = AppColors.surfaceHigh,
                    contentColor = AppColors.textPrimary,
                    actionColor = AppColors.accent
                )
            }
        },
        bottomBar = {
            AegisBottomBar(
                selected = currentTab,
                onSelect = { tabIndex = it.ordinal }
            )
        }
    ) { innerPadding ->
        Crossfade(
            targetState = currentTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "tabs"
        ) { tab ->
            when (tab) {
                AppTab.HOME -> HomeScreen(
                    server = selectedServer,
                    settings = settings,
                    vpnState = vpnState,
                    onConnect = connect,
                    onDisconnect = { VpnController.disconnect(context) },
                    onOpenServers = { tabIndex = AppTab.SERVERS.ordinal },
                    onOpenApps = { tabIndex = AppTab.APPS.ordinal },
                    onDismissError = { VpnStateHolder.disconnected() }
                )

                AppTab.SERVERS -> ServersScreen(
                    servers = servers,
                    selectedId = selectedServer?.id,
                    onSelect = { profile ->
                        store.selectServer(profile.id)
                        if (vpnState.status == VpnStatus.CONNECTED ||
                            vpnState.status == VpnStatus.CONNECTING
                        ) {
                            VpnController.connect(context, profile.id)
                            notify("Переключение на «${profile.name}»")
                        }
                    },
                    onDelete = { profile ->
                        val wasActive = profile.id == vpnState.serverId
                        store.deleteServer(profile.id)
                        if (wasActive && vpnState.status == VpnStatus.CONNECTED) {
                            VpnController.disconnect(context)
                            notify("Сервер удалён, VPN отключён")
                        } else {
                            notify("Сервер удалён")
                        }
                    },
                    onDeleteAll = {
                        val wasConnected = vpnState.status == VpnStatus.CONNECTED ||
                            vpnState.status == VpnStatus.CONNECTING ||
                            vpnState.status == VpnStatus.STOPPING
                        store.deleteAllServers()
                        if (wasConnected) {
                            VpnController.disconnect(context)
                            notify("Все серверы удалены, VPN отключён")
                        } else {
                            notify("Все серверы удалены")
                        }
                    },
                    onRename = { profile, name ->
                        val clean = name.trim()
                        if (clean.isNotBlank()) {
                            store.renameServer(profile.id, clean)
                            notify("Имя сервера обновлено")
                        }
                    },
                    onAdd = { showAddSheet = true },
                    onScan = launchScan
                )

                AppTab.STATS -> StatsScreen()

                AppTab.APPS -> AppsScreen(
                    settings = settings,
                    onSettingsChange = { updated -> store.updateSettings { updated } }
                )

                AppTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = { updated -> store.updateSettings { updated } }
                )
            }
        }
    }

    val updateDialog = autoUpdate
    if (updateDialog != null) {
        AlertDialog(
            onDismissRequest = { if (!autoDownloading) autoUpdate = null },
            title = { Text("Доступно обновление ${updateDialog.info.versionName}") },
            text = {
                Column {
                    if (updateDialog.info.notes.isNotBlank()) {
                        Text(updateDialog.info.notes, fontSize = 13.sp, lineHeight = 18.sp)
                    } else {
                        Text("Доступна новая версия Aegis VPN. Установить сейчас?", fontSize = 13.sp)
                    }
                    if (autoDownloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (autoDownloadProgress > 0) autoDownloadProgress / 100f else 0f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Загрузка: ${if (autoDownloadProgress > 0) "$autoDownloadProgress%" else "…"}",
                            fontSize = 11.5.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (autoDownloading) return@TextButton
                        autoDownloading = true
                        autoDownloadProgress = 0
                        scope.launch {
                            val res = AppUpdater.downloadApk(context, updateDialog) { p ->
                                autoDownloadProgress = p
                            }
                            autoDownloading = false
                            res.onSuccess { file ->
                                autoUpdate = null
                                if (!AppUpdater.canRequestPackageInstalls(context)) {
                                    notify("Разрешите установку из этого приложения")
                                    runCatching { context.startActivity(AppUpdater.unknownAppSourcesIntent(context)) }
                                } else {
                                    runCatching { context.startActivity(AppUpdater.installIntent(context, file)) }
                                        .onFailure { notify("Не удалось открыть установщик: ${it.message}") }
                                }
                            }.onFailure { e ->
                                notify("Ошибка загрузки: ${e.message ?: "ошибка"}")
                            }
                        }
                    },
                    enabled = !autoDownloading
                ) { Text(if (autoDownloading) "Загрузка…" else "Обновить") }
            },
            dismissButton = {
                TextButton(onClick = { autoUpdate = null }, enabled = !autoDownloading) { Text("Позже") }
            }
        )
    }

    if (showAddSheet) {
        AddServerSheet(
            onDismiss = { showAddSheet = false },
            onScan = { launchScan() },
            onFile = { fileLauncher.launch(arrayOf("*/*")) },
            onSubmit = { raw ->
                showAddSheet = false
                importInput(raw)
            }
        )
    }
}

@Composable
private fun AddServerSheet(
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onFile: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val context = LocalContext.current
    var value by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surface,
        contentColor = AppColors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp)
        ) {
            Text(
                text = "Добавить сервер",
                color = AppColors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Вставьте ключ vless://, ss://, wg://, конфигурацию WireGuard, ссылку на файл или подписку https://…",
                color = AppColors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = {
                    Text("vless://… или https://…", color = AppColors.textSecondary, fontSize = 13.sp)
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AppColors.surfaceHigh.copy(alpha = 0.5f),
                    unfocusedContainerColor = AppColors.surfaceHigh.copy(alpha = 0.5f),
                    focusedBorderColor = AppColors.accent.copy(alpha = 0.55f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                    focusedTextColor = AppColors.textPrimary,
                    unfocusedTextColor = AppColors.textPrimary,
                    cursorColor = AppColors.accent
                )
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("QR-код", fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = onFile,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Файл", fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip
                        val text = if (clip != null && clip.itemCount > 0) {
                            clip.getItemAt(0).coerceToText(context).toString()
                        } else ""
                        if (text.isBlank()) {
                            Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                        } else {
                            value = text.trim()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_paste),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Вставить", fontSize = 11.5.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onSubmit(value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.accent,
                    contentColor = Color(0xFF06100E),
                    disabledContainerColor = AppColors.surfaceHigh,
                    disabledContentColor = AppColors.textSecondary
                )
            ) {
                Text(
                    text = "Добавить",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        val builder = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
            if (builder.length > 1_000_000) break
        }
        builder.toString()
    }
}.getOrNull()
