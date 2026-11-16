package com.aegis.vpn.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnSnapshot
import com.aegis.vpn.data.VpnStatus
import com.aegis.vpn.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.streams.asSequence

fun main() = application {
    val store = remember { Store() }
    val engine = remember { VpnEngine() }
    var windowVisible by remember { mutableStateOf(true) }
    val trayIcon = painterResource("tray.png")
    val appIcon = painterResource("app-icon.png")

    fun requestExit() {
        engine.disconnect()
        exitApplication()
    }

    Tray(
        icon = trayIcon,
        tooltip = "Aegis VPN",
        onAction = { windowVisible = true },
        menu = {
            Item("Открыть Aegis VPN", onClick = { windowVisible = true })
            val vpn = engine.state.collectAsState().value
            val canToggle = vpn.status != VpnStatus.CONNECTING && vpn.status != VpnStatus.STOPPING
            if (vpn.status == VpnStatus.CONNECTED || vpn.status == VpnStatus.CONNECTING) {
                Item("Отключить", enabled = canToggle, onClick = { engine.disconnect() })
            } else {
                Item("Подключить", enabled = canToggle, onClick = { store.selected()?.let { engine.connect(it) } })
            }
            Separator()
            Item("Выход", onClick = { requestExit() })
        }
    )

    if (windowVisible) {
        Window(
            onCloseRequest = { windowVisible = false }, // hide to tray
            title = "Aegis VPN",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
            icon = appIcon
        ) {
            LaunchedEffect(Unit) {
                runCatching { window.minimumSize = java.awt.Dimension(850, 600) }
                polishNativeFrame(window)
            }
            AppTheme {
                App(store, engine, onHideToTray = { windowVisible = false }, onExit = { requestExit() })
            }
        }
    }
}

/**
 * Removes the light native frame around the window.
 *
 * Windows always paints a 1px light border (and a light strip above the content
 * when the title bar is dark) around a standard window; against the dark UI of
 * the app it reads as a white line along the edge. The DWM border/caption
 * attributes remove it and paint the title bar with the app background colour.
 *
 * Implemented by tools\polish-window.ps1 (Win32 + DWM) and launched with
 * -ExecutionPolicy Bypass so it also works on machines with a restricted
 * execution policy. Every failure is ignored: the window simply keeps its
 * native frame.
 */
private fun polishNativeFrame(window: java.awt.Window) {
    val script = findPolishScript() ?: return
    val title = runCatching { (window as? java.awt.Frame)?.title }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "Aegis VPN"

    fun apply() {
        runCatching {
            val exe = File(System.getenv("SystemRoot").orEmpty(), "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
                .takeIf { it.isFile } ?: File("powershell.exe")
            ProcessBuilder(
                exe.absolutePath,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath,
                "-WindowTitle", title
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // The HWND does not exist yet when the composable starts, so reapply after
    // the window is mapped and whenever it is activated (minimise/restore and
    // display changes can reset the DWM attributes).
    javax.swing.SwingUtilities.invokeLater { apply() }
    runCatching {
        window.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowActivated(e: java.awt.event.WindowEvent?) = apply()
        })
    }
}

private fun findPolishScript(): File? {
    val candidates = mutableListOf<File>()
    runCatching {
        val codeSrc = File(
            VpnEngine::class.java.protectionDomain.codeSource.location.toURI()
        ).parentFile
        if (codeSrc != null) {
            candidates += File(codeSrc, "tools/polish-window.ps1")
            candidates += File(codeSrc, "polish-window.ps1")
        }
    }
    val cwd = File(System.getProperty("user.dir"))
    candidates += File(cwd, "tools/polish-window.ps1")
    candidates += File(cwd, "polish-window.ps1")
    candidates += File(cwd, "desktop/tools/polish-window.ps1")
    candidates.firstOrNull { it.isFile }?.let { return it }
    // Installed builds (MSI/EXE) ship the helper inside the jar: unpack it once
    // next to the app data so the frame polish also works there.
    return runCatching {
        val target = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AegisVPN-Desktop/polish-window.ps1")
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            VpnEngine::class.java.getResourceAsStream("/polish-window.ps1")?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.takeIf { it.isFile }
    }.getOrNull()
}

private fun isProcessRunning(triggers: Set<String>): Boolean {
    if (triggers.isEmpty()) return false
    val needles = triggers.mapNotNull { it.trim().lowercase(Locale.ROOT).takeIf { s -> s.isNotEmpty() } }
    if (needles.isEmpty()) return false
    return ProcessHandle.allProcesses().asSequence().any { ph ->
        val info = ph.info()
        val cmd = info.command().orElse(null)
        val name = cmd?.let { File(it).name.lowercase(Locale.ROOT) } ?: ""
        val firstArg = try { info.arguments().orElse(emptyArray()).firstOrNull()?.lowercase(Locale.ROOT) ?: "" } catch (_: Throwable) { "" }
        needles.any { n ->
            name == n || name == "$n.exe" || name.contains(n) || firstArg.contains(n)
        }
    }
}

// ---------------------------------------------------------------- helpers

private enum class Page(val title: String) {
    Home("Главная"),
    Servers("Серверы"),
    Stats("Статистика"),
    Settings("Настройки")
}

private val Ink = Color(0xFF150826)

private fun displayName(raw: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        val len = Character.charCount(cp)
        if (cp in 0x1F1E6..0x1F1FF) {
            i += len
            continue
        }
        sb.appendCodePoint(cp)
        i += len
    }
    val withoutFlag = sb.toString().trim()
    val withoutTransport = withoutFlag.replace(
        Regex("\\s*[·•\\-–]\\s*(TCP|gRPC)\\s*$", RegexOption.IGNORE_CASE),
        ""
    ).trim()
    return withoutTransport.ifBlank { withoutFlag }
}

private fun countryCode(name: String): String {
    val n = name.lowercase(Locale.ROOT)
    return when {
        n.contains("польш") || n.contains("poland") -> "PL"
        n.contains("франц") || n.contains("france") -> "FR"
        n.contains("нидерланд") || n.contains("netherland") || n.contains("holland") -> "NL"
        n.contains("герман") || n.contains("german") -> "DE"
        n.contains("росси") || n.contains("russia") -> "RU"
        n.contains("сша") || n.contains("united states") || n.contains("usa") -> "US"
        n.contains("великобрит") || n.contains("britain") || n.contains("england") -> "GB"
        n.contains("швейцар") || n.contains("switzerland") -> "CH"
        n.contains("япони") || n.contains("japan") -> "JP"
        n.contains("сингапур") || n.contains("singapore") -> "SG"
        n.contains("гонконг") || n.contains("hong kong") -> "HK"
        n.contains("канад") || n.contains("canada") -> "CA"
        n.contains("чехи") || n.contains("czech") -> "CZ"
        n.contains("австри") || n.contains("austria") -> "AT"
        n.contains("эстони") || n.contains("estonia") -> "EE"
        n.contains("швеци") || n.contains("sweden") -> "SE"
        n.contains("финлянди") || n.contains("finland") -> "FI"
        else -> ""
    }
}

private fun transportLabel(profile: ServerProfile): String {
    val raw = profile.raw.lowercase(Locale.ROOT)
    return if (raw.contains("grpc")) "gRPC" else "TCP"
}

private fun measurePing(host: String, port: Int, timeoutMs: Int = 1200): Int? {
    if (host.isBlank() || port <= 0) return null
    return runCatching {
        val started = System.nanoTime()
        java.net.Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        }
        ((System.nanoTime() - started) / 1_000_000L).toInt().coerceIn(1, 9999)
    }.getOrNull()
}

private fun formatSpeed(mbps: Double): String = when {
    mbps <= 0.0 -> "0.0"
    mbps < 10.0 -> String.format(Locale.US, "%.1f", mbps)
    mbps < 100.0 -> String.format(Locale.US, "%.1f", mbps)
    else -> String.format(Locale.US, "%.0f", mbps)
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < 1024L -> "$bytes Б"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f КБ", bytes / kb)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", bytes / (kb * kb))
        else -> String.format(Locale.US, "%.2f ГБ", bytes / (kb * kb * kb))
    }
}

private fun statusColor(status: VpnStatus): Color = when (status) {
    VpnStatus.CONNECTED -> Color(0xFF28DFA0)
    VpnStatus.CONNECTING, VpnStatus.STOPPING -> Color(0xFFA970FF)
    VpnStatus.ERROR -> Color(0xFFFF526A)
    else -> Color(0xFF9290AA)
}

private fun statusTitle(status: VpnStatus): String = when (status) {
    VpnStatus.CONNECTED -> "ЗАЩИЩЕНО"
    VpnStatus.CONNECTING -> "ПОДКЛЮЧЕНИЕ"
    VpnStatus.STOPPING -> "ОТКЛЮЧЕНИЕ"
    VpnStatus.ERROR -> "ОШИБКА"
    else -> "НЕ ПОДКЛЮЧЕНО"
}

// ---------------------------------------------------------------- app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store, engine: VpnEngine, onHideToTray: () -> Unit, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val vpn by engine.state.collectAsState()
    var servers by remember { mutableStateOf(store.state.servers.toList()) }
    var selectedId by remember { mutableStateOf(store.state.selectedId) }
    var showAdd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var page by remember { mutableStateOf(Page.Home) }

    var autoEnabled by remember { mutableStateOf(store.state.autoConnectEnabled) }
    var autoProcesses by remember { mutableStateOf(store.state.autoProcesses.toSet()) }
    var autoDomains by remember { mutableStateOf(store.state.autoDomains.toSet()) }

    var updateAvailable by remember { mutableStateOf<DesktopUpdater.UpdateAvailable?>(null) }
    var updateChecked by rememberSaveable { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(0) }

    val meter = remember { NetMeter() }
    DisposableEffect(Unit) {
        meter.start()
        onDispose { meter.stop() }
    }

    var downloadMbps by remember { mutableStateOf(0.0) }
    var uploadMbps by remember { mutableStateOf(0.0) }
    var rxTotal by remember { mutableStateOf(0L) }
    var txTotal by remember { mutableStateOf(0L) }
    val dlHistory = remember { mutableStateListOf<Float>() }
    val ulHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            downloadMbps = meter.downloadMbps
            uploadMbps = meter.uploadMbps
            rxTotal = meter.rxTotal
            txTotal = meter.txTotal
            dlHistory.add(downloadMbps.toFloat())
            if (dlHistory.size > 60) dlHistory.removeAt(0)
            ulHistory.add(uploadMbps.toFloat())
            if (ulHistory.size > 60) ulHistory.removeAt(0)
        }
    }

    LaunchedEffect(vpn.status, vpn.connectedSince) {
        if (vpn.status == VpnStatus.CONNECTED && vpn.connectedSince > 0) {
            meter.resetTotals()
            meter.accumulate = true
        } else {
            meter.accumulate = false
        }
    }

    val pings = remember { mutableStateMapOf<String, Int?>() }
    LaunchedEffect(servers) {
        while (servers.isNotEmpty()) {
            for (s in servers) {
                val p = withContext(Dispatchers.IO) { measurePing(s.host, s.port) }
                pings[s.id] = p
            }
            delay(15000)
        }
    }

    // keep engine domains in sync
    LaunchedEffect(autoDomains) { engine.proxyDomains = autoDomains }

    // Reconnect when another server is picked while the tunnel is up or broken:
    // the running sing-box keeps the previous profile, so switching the list
    // selection alone used to leave the app connected to a dead endpoint.
    var reconnectFromId by remember { mutableStateOf(selectedId) }
    LaunchedEffect(selectedId) {
        val target = selectedId
        if (target == null || target == reconnectFromId) {
            reconnectFromId = target
            return@LaunchedEffect
        }
        reconnectFromId = target
        val st = engine.state.value.status
        if (st == VpnStatus.CONNECTED || st == VpnStatus.CONNECTING || st == VpnStatus.ERROR) {
            val profile = servers.firstOrNull { it.id == target } ?: return@LaunchedEffect
            engine.disconnect()
            delay(350)
            engine.proxyDomains = autoDomains
            engine.connect(profile)
            msg = "Переключено на «${displayName(profile.name)}» — переподключаюсь"
        }
    }

    fun checkUpdates(manual: Boolean = false) {
        if (updateChecking) return
        updateChecking = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { DesktopUpdater.checkForUpdate() }
            updateChecking = false
            res.onSuccess { upd ->
                updateAvailable = upd
                if (manual) msg = if (upd != null) "Доступно обновление ${upd.info.version}" else "Установлена актуальная версия"
            }.onFailure { e -> if (manual) msg = "Не удалось проверить обновления: ${e.message}" }
        }
    }

    LaunchedEffect(Unit) {
        if (!updateChecked) {
            updateChecked = true
            delay(2500)
            checkUpdates(false)
        }
    }

    // stop DPI-bypass tools (zapret/winws) so they do not conflict with the TUN adapter
    LaunchedEffect(Unit) {
        val conflict = withContext(Dispatchers.IO) { ConflictManager.stopDpiBypass() }
        conflict.summary()?.let { msg = it }
    }

    // auto-connect on startup when automation is enabled
    LaunchedEffect(Unit) {
        delay(1500)
        if (autoEnabled) {
            val sel = store.selected() ?: return@LaunchedEffect
            val hasTrigger = withContext(Dispatchers.IO) { isProcessRunning(autoProcesses) }
            val st = engine.state.value.status
            // connect on start if a trigger app is running, or just bring VPN up so domain routing/protection is active
            if ((hasTrigger || autoDomains.isNotEmpty()) && (st == VpnStatus.DISCONNECTED || st == VpnStatus.ERROR)) {
                engine.proxyDomains = store.state.autoDomains.toSet()
                engine.connect(sel)
            }
        }
    }

    // auto-connect watcher (by running processes/apps).
    // Closing Discord/Telegram must NOT drop the tunnel: the VPN stays up until the user stops it.
    LaunchedEffect(autoEnabled, autoProcesses, servers, selectedId) {
        var triggerWasRunning = false
        while (autoEnabled) {
            val hasTrigger = withContext(Dispatchers.IO) { isProcessRunning(autoProcesses) }
            val sel = store.selected()
            if (hasTrigger && sel != null) {
                val st = engine.state.value.status
                // connect when a trigger app just appeared, or retry after a failed connection;
                // never re-connect after the user pressed "Отключить"
                if ((!triggerWasRunning || st == VpnStatus.ERROR) &&
                    (st == VpnStatus.DISCONNECTED || st == VpnStatus.ERROR)
                ) {
                    engine.proxyDomains = store.state.autoDomains.toSet()
                    engine.connect(sel)
                }
            }
            triggerWasRunning = hasTrigger
            delay(4000)
        }
    }

    LaunchedEffect(vpn.status, vpn.connectedSince) {
        while (vpn.status == VpnStatus.CONNECTED && vpn.connectedSince > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(msg) {
        if (msg != null) {
            delay(4500)
            msg = null
        }
    }

    fun refresh() {
        servers = store.state.servers.toList()
        selectedId = store.state.selectedId
    }

    val selected = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
    val selectedPing = selected?.let { pings[it.id] }
    val sessionSecs = if (vpn.connectedSince > 0) ((now - vpn.connectedSince) / 1000).coerceAtLeast(0) else 0L
    val toggle: () -> Unit = {
        when (vpn.status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> engine.disconnect()
            VpnStatus.STOPPING -> Unit
            else -> selected?.let {
                engine.proxyDomains = autoDomains
                engine.connect(it)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val compact = maxWidth < 980.dp

        // ambient background glows
        val glowAccent = C.accent
        val glowAccentSoft = C.accentSoft
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glowAccent.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.70f, -size.height * 0.05f),
                    radius = size.width * 0.62f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glowAccentSoft.copy(alpha = 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 1.05f),
                    radius = size.width * 0.55f
                )
            )
        }

        Row(Modifier.fillMaxSize()) {
            Sidebar(
                width = if (compact) 288.dp else 322.dp,
                servers = servers,
                selected = selected,
                pings = pings,
                status = vpn.status,
                autoEnabled = autoEnabled,
                version = DesktopUpdater.APP_VERSION,
                updateAvailable = updateAvailable != null,
                updateChecking = updateChecking,
                onSelect = { id -> store.select(id); refresh() },
                onAdd = { showAdd = true },
                onAuto = { page = Page.Settings },
                onDelete = { selected?.let { store.delete(it.id); refresh() } },
                onUpdate = { checkUpdates(true) },
                onTray = onHideToTray
            )

            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(
                    page = page,
                    compact = compact,
                    status = vpn.status,
                    onPage = { page = it }
                )
                Crossfade(targetState = page, animationSpec = tween(260, easing = FastOutSlowInEasing), label = "pages") { p ->
                    when (p) {
                        Page.Home -> HomePage(
                            vpn = vpn,
                            selected = selected,
                            ping = selectedPing,
                            autoEnabled = autoEnabled,
                            autoApps = autoProcesses.size,
                            autoDomains = autoDomains.size,
                            downloadMbps = downloadMbps,
                            uploadMbps = uploadMbps,
                            dlHistory = dlHistory,
                            ulHistory = ulHistory,
                            sessionSecs = sessionSecs,
                            onToggle = toggle,
                            onOpenServers = { page = Page.Servers }
                        )
                        Page.Servers -> ServersPage(
                            servers = servers,
                            selected = selected,
                            pings = pings,
                            status = vpn.status,
                            onAdd = { showAdd = true },
                            onSelect = { id -> store.select(id); refresh() },
                            onDelete = { id -> store.delete(id); refresh() }
                        )
                        Page.Stats -> StatsPage(
                            vpn = vpn,
                            selected = selected,
                            ping = selectedPing,
                            downloadMbps = downloadMbps,
                            uploadMbps = uploadMbps,
                            dlHistory = dlHistory,
                            ulHistory = ulHistory,
                            rxTotal = rxTotal,
                            txTotal = txTotal,
                            sessionSecs = sessionSecs
                        )
                        Page.Settings -> SettingsPage(
                            autoEnabled = autoEnabled,
                            processes = autoProcesses,
                            domains = autoDomains,
                            onAutoEnabled = {
                                autoEnabled = it
                                store.state.autoConnectEnabled = it
                                store.save()
                            },
                            onProcessesChange = {
                                autoProcesses = it
                                store.state.autoProcesses = it.toMutableSet()
                                store.save()
                            },
                            onDomainsChange = {
                                autoDomains = it
                                store.state.autoDomains = it.toMutableSet()
                                store.save()
                                engine.proxyDomains = it
                                if (engine.isActive) msg = "Домены обновлены — переподключите VPN для применения правил"
                            },
                            updateAvailable = updateAvailable,
                            updateChecking = updateChecking,
                            onCheckUpdates = { checkUpdates(true) }
                        )
                    }
                }
            }
        }

        // toast
        msg?.let { text ->
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.surfaceHigh.copy(alpha = 0.96f))
                    .border(1.dp, C.accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(C.accent))
                    Spacer(Modifier.width(10.dp))
                    Text(text, color = C.text, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }

    val upd = updateAvailable
    if (upd != null) {
        AlertDialog(
            onDismissRequest = { if (!updateDownloading) updateAvailable = null },
            containerColor = C.surfaceHigh,
            shape = RoundedCornerShape(20.dp),
            titleContentColor = C.text,
            textContentColor = C.subtext,
            title = { Text("Доступно обновление ${upd.info.version}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(if (upd.info.notes.isNotBlank()) upd.info.notes else "Доступна новая версия Aegis VPN Desktop. Скачать и открыть папку с обновлением?", fontSize = 13.sp, lineHeight = 19.sp)
                    if (updateDownloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (updateProgress > 0) updateProgress / 100f else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = C.accent,
                            trackColor = C.accent.copy(alpha = 0.15f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Загрузка: ${if (updateProgress > 0) "$updateProgress%" else "…"}", fontSize = 11.sp, color = C.subtext)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (updateDownloading) return@TextButton
                    updateDownloading = true; updateProgress = 0
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { DesktopUpdater.downloadUpdate(upd.info) { p -> updateProgress = p } }
                        updateDownloading = false
                        res.onSuccess { file ->
                            updateAvailable = null
                            msg = "Обновление скачано: ${file.name}. Закройте приложение и установите/распакуйте обновление."
                            DesktopUpdater.openDownloaded(file)
                        }.onFailure { e -> msg = "Ошибка загрузки: ${e.message}" }
                    }
                }, enabled = !updateDownloading) { Text(if (updateDownloading) "Загрузка…" else "Скачать", color = C.accentSoft) }
            },
            dismissButton = { TextButton(onClick = { updateAvailable = null }, enabled = !updateDownloading) { Text("Позже", color = C.subtext) } }
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!busy) showAdd = false },
            containerColor = C.surfaceHigh,
            shape = RoundedCornerShape(20.dp),
            titleContentColor = C.text,
            textContentColor = C.subtext,
            title = { Text("Добавить сервер / подписку", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("vless://…  ss://…  wg://…  .conf  или https://подписка", fontSize = 12.sp, color = C.muted) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = C.accent.copy(alpha = 0.7f), unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                            focusedTextColor = C.text, unfocusedTextColor = C.text, cursorColor = C.accent
                        )
                    )
                    if (busy) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = C.accent, trackColor = C.accent.copy(alpha = 0.15f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = input.trim()
                    if (raw.isBlank()) return@TextButton
                    busy = true
                    scope.launch {
                        val resultMsg = withContext(Dispatchers.IO) {
                            runCatching {
                                if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                                    val keys = store.fetchSubscription(raw)
                                    if (keys.isEmpty()) "В подписке нет подходящих серверов"
                                    else { val (a, s) = store.importKeys(keys); "Добавлено: $a, пропущено: $s" }
                                } else { store.addRaw(raw).getOrThrow(); "Сервер добавлен" }
                            }.getOrElse { it.message ?: "Ошибка" }
                        }
                        busy = false; msg = resultMsg; input = ""; showAdd = false; refresh()
                    }
                }, enabled = !busy) { Text("Добавить", color = C.accentSoft) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }, enabled = !busy) { Text("Отмена", color = C.subtext) } }
        )
    }
}

// ---------------------------------------------------------------- shell

@Composable
private fun Sidebar(
    width: Dp,
    servers: List<ServerProfile>,
    selected: ServerProfile?,
    pings: Map<String, Int?>,
    status: VpnStatus,
    autoEnabled: Boolean,
    version: String,
    updateAvailable: Boolean,
    updateChecking: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onAuto: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit,
    onTray: () -> Unit
) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(C.surface.copy(alpha = 0.92f), C.bg.copy(alpha = 0.96f))
                )
            )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
            // brand
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(C.gradientStart, C.gradientEnd))),
                    contentAlignment = Alignment.Center
                ) {
                    ShieldGlyph(Modifier.size(21.dp), Color.White)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AEGIS", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.2.sp)
                        Spacer(Modifier.width(5.dp))
                        Text("VPN", color = C.accentSoft, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text("DESKTOP · WINDOWS", color = C.muted, fontSize = 8.5.sp, letterSpacing = 1.1.sp)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(100.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("v$version", color = C.subtext, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
            SidebarDivider()
            Spacer(Modifier.height(14.dp))

            // actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                GalleryButton(
                    text = "Добавить",
                    modifier = Modifier.weight(1f),
                    onClick = onAdd
                )
                Spacer(Modifier.width(8.dp))
                AutoButton(active = autoEnabled, onClick = onAuto)
            }

            Spacer(Modifier.height(16.dp))

            // section header
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("СЕРВЕРЫ", color = C.muted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(100.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("${servers.size}", color = C.subtext, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(9.dp))

            if (servers.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                                .background(C.accent.copy(alpha = 0.08f))
                                .border(1.dp, C.accent.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) { ServerGlyph(Modifier.size(22.dp), C.accentSoft) }
                        Spacer(Modifier.height(10.dp))
                        Text("Нет серверов", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text("Нажмите «Добавить»", color = C.muted, fontSize = 10.5.sp)
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(servers, key = { it.id }) { server ->
                            ServerListCard(
                                server = server,
                                selected = server.id == selected?.id,
                                status = status,
                                ping = pings[server.id],
                                onClick = { onSelect(server.id) }
                            )
                        }
                        item { Spacer(Modifier.height(10.dp)) }
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(14.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg)))
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(5.dp).clip(CircleShape)
                        .background(if (autoEnabled) C.connected else C.muted)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (autoEnabled) "Автоподключение включено" else "Автоподключение выключено",
                    color = if (autoEnabled) C.subtext else C.muted,
                    fontSize = 9.5.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            SidebarDivider()
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                GhostButton(
                    label = "Удалить",
                    color = if (selected != null) C.danger else C.muted,
                    enabled = selected != null,
                    modifier = Modifier.weight(1f),
                    onClick = onDelete
                )
                GhostButton(
                    label = if (updateChecking) "…" else "Обновить",
                    color = if (updateAvailable) C.accentSoft else C.subtext,
                    enabled = !updateChecking,
                    modifier = Modifier.weight(1f),
                    onClick = onUpdate,
                    badge = updateAvailable
                )
                GhostButton(
                    label = "В трей",
                    color = C.subtext,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onTray
                )
            }
        }

        // right hairline separator
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp)
                .background(Color.White.copy(alpha = 0.05f))
        )
    }
}

@Composable
private fun SidebarDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
}

@Composable
private fun GalleryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.01f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "addScale")
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(C.gradientStart, C.accentSoft)))
            .hoverable(hover)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text("+", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun AutoButton(active: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(if (active) C.accent.copy(alpha = 0.16f) else Color.White.copy(alpha = if (hovered) 0.08f else 0.045f), tween(180), label = "autoBg")
    val border by animateColorAsState(if (active) C.accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), tween(180), label = "autoBorder")
    Row(
        Modifier.height(38.dp).width(78.dp).clip(RoundedCornerShape(12.dp))
            .background(bg).border(1.dp, border, RoundedCornerShape(12.dp))
            .hoverable(hover).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape)
                .background(if (active) C.connected else C.muted)
        )
        Spacer(Modifier.width(6.dp))
        Text("Авто", color = if (active) C.text else C.subtext, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GhostButton(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    badge: Boolean = false
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (hovered && enabled) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.03f),
        tween(160), label = "ghostBg"
    )
    Box(
        modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .hoverable(hover)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) color else color.copy(alpha = 0.4f),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)
                    .size(5.dp).clip(CircleShape).background(C.accentSoft)
            )
        }
    }
}

@Composable
private fun TopBar(page: Page, compact: Boolean, status: VpnStatus, onPage: (Page) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp)
            .padding(horizontal = if (compact) 18.dp else 26.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Page.entries.forEach { item ->
                NavTab(item = item, active = item == page, compact = compact, onClick = { onPage(item) })
            }
        }
        Spacer(Modifier.weight(1f))
        if (!compact) {
            Box(
                Modifier.clip(RoundedCornerShape(100.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(100.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(C.accentSoft.copy(alpha = 0.8f)))
                    Spacer(Modifier.width(6.dp))
                    Text("TUN · PROXY", color = C.muted, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        StatusPill(status)
        Spacer(Modifier.width(10.dp))
        IconSquare(active = page == Page.Settings, onClick = { onPage(Page.Settings) }) {
            GearGlyph(Modifier.size(15.dp), if (page == Page.Settings) C.accentSoft else C.subtext)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
}

@Composable
private fun NavTab(item: Page, active: Boolean, compact: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (active) Color.White.copy(alpha = 0.07f) else if (hovered) Color.White.copy(alpha = 0.035f) else Color.Transparent,
        tween(180), label = "tabBg"
    )
    val fg by animateColorAsState(if (active) C.text else C.subtext, tween(180), label = "tabFg")
    Row(
        Modifier.height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(if (active) Modifier.border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp)) else Modifier)
            .hoverable(hover)
            .clickable { onClick() }
            .padding(horizontal = if (compact) 9.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavGlyph(item, Modifier.size(14.dp), if (active) C.accentSoft else C.subtext)
        if (!compact) {
            Spacer(Modifier.width(7.dp))
            Text(item.title, color = fg, fontSize = 11.5.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusPill(status: VpnStatus) {
    val color = statusColor(status)
    val pulse = rememberInfiniteTransition(label = "status")
    val alpha by pulse.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "statusAlpha"
    )
    Row(
        Modifier.clip(RoundedCornerShape(100.dp))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = if (status == VpnStatus.CONNECTED) alpha else 1f)))
        Spacer(Modifier.width(6.dp))
        Text(statusTitle(status), color = color, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    }
}

@Composable
private fun IconSquare(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        if (active) C.accent.copy(alpha = 0.14f) else if (hovered) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.03f),
        tween(160), label = "iconBg"
    )
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .hoverable(hover).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomePage(
    vpn: VpnSnapshot,
    selected: ServerProfile?,
    ping: Int?,
    autoEnabled: Boolean,
    autoApps: Int,
    autoDomains: Int,
    downloadMbps: Double,
    uploadMbps: Double,
    dlHistory: List<Float>,
    ulHistory: List<Float>,
    sessionSecs: Long,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val low = maxHeight < 650.dp || vpn.status == VpnStatus.ERROR
        val color = statusColor(vpn.status)

        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight - 32.dp)
                .padding(horizontal = 26.dp, vertical = if (low) 8.dp else 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(if (low) 2.dp else 8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(100.dp))
                    .background(color.copy(alpha = 0.08f))
                    .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(100.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(7.dp))
                Text(statusTitle(vpn.status), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when {
                    !autoEnabled -> "Автоподключение выключено"
                    autoApps > 0 -> "Автоподключение активно · $autoApps приложений"
                    autoDomains > 0 -> "Автоподключение активно · $autoDomains доменов"
                    else -> "Автоподключение активно"
                },
                color = if (autoEnabled) C.subtext else C.muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(if (low) 4.dp else 10.dp))
            PowerButton(status = vpn.status, low = low, onClick = onToggle)
            Spacer(Modifier.height(if (low) 6.dp else 12.dp))

            Text(
                when (vpn.status) {
                    VpnStatus.CONNECTED -> "Соединение защищено"
                    VpnStatus.CONNECTING -> "Устанавливаем соединение…"
                    VpnStatus.STOPPING -> "Отключаем VPN…"
                    VpnStatus.ERROR -> "Не удалось подключиться"
                    else -> "VPN отключён"
                },
                color = C.text,
                fontSize = if (low) 18.sp else 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            if (selected != null) {
                SelectedServerCard(
                    server = selected,
                    ping = ping,
                    status = vpn.status,
                    onClick = onOpenServers,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                )
            } else {
                Text("Сервер не выбран", color = C.subtext, fontSize = 11.sp)
            }

            if (vpn.status == VpnStatus.ERROR && vpn.error != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.widthIn(max = 520.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(C.danger.copy(alpha = 0.08f))
                        .border(1.dp, C.danger.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(vpn.error ?: "", color = C.danger, fontSize = 10.5.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(if (low) 10.dp else 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth()) {
                SpeedCard(
                    up = true,
                    mbps = uploadMbps,
                    history = ulHistory,
                    modifier = Modifier.weight(1f)
                )
                SpeedCard(
                    up = false,
                    mbps = downloadMbps,
                    history = dlHistory,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaChip(icon = { ClockGlyph(Modifier.size(12.dp), C.subtext) }, text = "Сессия ${formatDuration(sessionSecs)}")
                MetaChip(icon = { ShieldGlyph(Modifier.size(12.dp), C.subtext) }, text = "TUN + PROXY")
                if (selected != null) {
                    MetaChip(icon = { PingGlyph(Modifier.size(12.dp), C.subtext) }, text = "${ping?.let { "$it мс" } ?: "—"}")
                }
            }

            Spacer(Modifier.height(if (low) 8.dp else 12.dp))
            ProtectedStrip(status = vpn.status, modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PowerButton(status: VpnStatus, low: Boolean, onClick: () -> Unit) {
    val connected = status == VpnStatus.CONNECTED
    val busy = status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING
    val error = status == VpnStatus.ERROR
    val color = statusColor(status)

    val outer = if (low) 202.dp else 236.dp
    val button = if (low) 136.dp else 156.dp

    val transition = rememberInfiniteTransition(label = "power")
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (busy) 2400 else 22000, easing = LinearEasing)),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "powerScale")

    val glow = when {
        connected -> 0.42f + 0.22f * pulse
        busy -> 0.26f + 0.16f * pulse
        error -> 0.20f + 0.10f * pulse
        else -> 0.13f + 0.06f * pulse
    }

    Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(
                    colors = listOf(color.copy(alpha = glow * 0.55f), color.copy(alpha = glow * 0.12f), Color.Transparent),
                    center = c,
                    radius = r
                ),
                radius = r,
                center = c
            )
            drawCircle(color.copy(alpha = 0.16f), radius = r - 1.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
            drawCircle(
                color.copy(alpha = 0.10f),
                radius = r - 17.dp.toPx(),
                center = c,
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx())))
            )
        }

        Canvas(Modifier.size(outer - 30.dp).graphicsLayer { rotationZ = spin }) {
            val w = size.minDimension
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.55f), Color.Transparent)
                ),
                startAngle = 0f, sweepAngle = 64f, useCenter = false,
                topLeft = Offset(2f, 2f), size = Size(w - 4f, w - 4f),
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Canvas(Modifier.size(outer - 30.dp).graphicsLayer { rotationZ = -spin * 0.55f }) {
            val w = size.minDimension
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.28f), Color.Transparent)
                ),
                startAngle = 0f, sweepAngle = 40f, useCenter = false,
                topLeft = Offset(2f, 2f), size = Size(w - 4f, w - 4f),
                style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 7.dp.toPx()
            for (i in 0 until 3) {
                val a = Math.toRadians(spin.toDouble() * 0.25 + i * 120.0)
                drawCircle(
                    color.copy(alpha = 0.55f),
                    radius = 1.8.dp.toPx(),
                    center = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                )
            }
        }

        Box(
            Modifier.size(button)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(
                    when {
                        connected -> Brush.linearGradient(listOf(C.gradientStart, C.accentSoft))
                        busy -> Brush.linearGradient(listOf(C.accent.copy(alpha = 0.45f), C.surfaceHigh))
                        error -> Brush.linearGradient(listOf(C.danger.copy(alpha = 0.35f), C.surfaceHigh))
                        else -> Brush.linearGradient(listOf(C.surfaceHigh, C.surface))
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = when {
                        connected -> Color.White.copy(alpha = 0.22f)
                        error -> C.danger.copy(alpha = 0.45f)
                        busy -> C.accent.copy(alpha = 0.55f)
                        else -> C.accent.copy(alpha = 0.30f)
                    },
                    shape = CircleShape
                )
                .hoverable(hover)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent))
                )
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (busy) {
                    CircularProgressIndicator(
                        color = C.text,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(if (low) 28.dp else 34.dp)
                    )
                } else {
                    PowerGlyph(
                        Modifier.size(if (low) 30.dp else 38.dp),
                        if (connected) Ink else C.text.copy(alpha = 0.92f)
                    )
                }
                Spacer(Modifier.height(if (low) 6.dp else 9.dp))
                Text(
                    when (status) {
                        VpnStatus.CONNECTED -> "ОТКЛЮЧИТЬ"
                        VpnStatus.CONNECTING -> "ОТМЕНА"
                        VpnStatus.STOPPING -> "ЖДИТЕ"
                        VpnStatus.ERROR -> "ПОВТОРИТЬ"
                        else -> "ПОДКЛЮЧИТЬ"
                    },
                    color = if (connected) Ink else C.text,
                    fontSize = if (low) 8.5.sp else 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

@Composable
private fun SpeedCard(up: Boolean, mbps: Double, history: List<Float>, modifier: Modifier = Modifier) {
    val accent = if (up) C.accentSoft else C.accent
    Box(
        modifier.height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) { ArrowGlyph(up, Modifier.size(15.dp), accent) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (up) "ЗАГРУЗКА" else "СКАЧИВАНИЕ", color = C.muted, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(formatSpeed(mbps), color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        Text("Мбит/с", color = C.muted, fontSize = 8.5.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(history, accent, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun ProtectedStrip(status: VpnStatus, modifier: Modifier = Modifier) {
    val connected = status == VpnStatus.CONNECTED
    val color = if (connected) C.connected else if (status == VpnStatus.ERROR) C.danger else C.muted
    Row(
        modifier.height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(9.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { ShieldGlyph(Modifier.size(13.dp), color) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                when {
                    connected -> "Ваше соединение защищено"
                    status == VpnStatus.CONNECTING -> "Устанавливаем защищённый туннель"
                    status == VpnStatus.ERROR -> "Соединение не защищено"
                    else -> "VPN отключён — трафик не защищён"
                },
                color = if (connected) C.text else C.subtext,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (connected) "Трафик шифруется через выбранный сервер" else "Нажмите кнопку, чтобы включить защиту",
                color = C.muted,
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.weight(1f))
        Equalizer(active = connected, color = if (connected) C.accentSoft else C.muted)
    }
}

@Composable
private fun MetaChip(icon: @Composable () -> Unit, text: String) {
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(text, color = C.subtext, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SelectedServerCard(
    server: ServerProfile,
    ping: Int?,
    status: VpnStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (hovered) 0.05f else 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .hoverable(hover)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CountryFlag(server.name, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(displayName(server.name), color = C.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(server.endpointLabel, color = C.muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PingLabel(ping, 10.sp)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(selected = true, endpointPing = ping, status = status)
                Spacer(Modifier.width(5.dp))
                Text("VLESS · ${transportLabel(server)}", color = C.muted, fontSize = 8.5.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- servers page

@Composable
private fun ServersPage(
    servers: List<ServerProfile>,
    selected: ServerProfile?,
    pings: Map<String, Int?>,
    status: VpnStatus,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val fastest = servers.mapNotNull { pings[it.id] }.minOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Серверы", color = C.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("Профили VLESS, протоколы и доступность в реальном времени", color = C.subtext, fontSize = 10.5.sp)
            }
            GalleryButton(text = "Добавить сервер", onClick = onAdd)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("ВСЕГО", "${servers.size}", "профилей", C.accentSoft, Modifier.weight(1f))
            MiniStat("VLESS", "${servers.count { it.protocol.shortLabel == "VLESS" }}", "подключений", C.accent, Modifier.weight(1f))
            MiniStat("БЫСТРЕЙШИЙ", fastest?.let { "$it мс" } ?: "—", "задержка", C.connected, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ServerGlyph(Modifier.size(28.dp), C.accentSoft)
                    Spacer(Modifier.height(10.dp))
                    Text("Список серверов пуст", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("Добавьте VLESS-ключ или ссылку на подписку", color = C.subtext, fontSize = 10.5.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { server ->
                    ServerDetailRow(
                        server = server,
                        selected = server.id == selected?.id,
                        status = status,
                        ping = pings[server.id],
                        onClick = { onSelect(server.id) },
                        onDelete = { onDelete(server.id) }
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, hint: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, color = C.muted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(5.dp))
                Text(hint, color = C.muted, fontSize = 8.5.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
private fun ServerDetailRow(
    server: ServerProfile,
    selected: Boolean,
    status: VpnStatus,
    ping: Int?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> C.accent.copy(alpha = 0.10f)
            hovered -> Color.White.copy(alpha = 0.05f)
            else -> Color.White.copy(alpha = 0.028f)
        },
        tween(180), label = "srvBg"
    )
    val border by animateColorAsState(
        if (selected) C.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f),
        tween(180), label = "srvBorder"
    )
    Row(
        Modifier.fillMaxWidth().height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .hoverable(hover)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(100.dp))
            .background(if (selected) C.accent else Color.Transparent))
        Spacer(Modifier.width(9.dp))
        CountryFlag(server.name, 30.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayName(server.name), color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected) {
                    Spacer(Modifier.width(7.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(100.dp))
                            .background(C.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text("ВЫБРАН", color = C.accentSoft, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("VLESS", C.accentSoft)
                Spacer(Modifier.width(4.dp))
                Tag(transportLabel(server), C.subtext)
                Spacer(Modifier.width(7.dp))
                Text(server.endpointLabel, color = C.muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            PingLabel(ping, 10.5.sp)
            Spacer(Modifier.height(3.dp))
            ConnectionDot(selected = selected, endpointPing = ping, status = status)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = if (hovered) 0.07f else 0.03f))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) { TrashGlyph(Modifier.size(12.dp), C.muted) }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text, color = color.copy(alpha = 0.95f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}

// ---------------------------------------------------------------- stats page

@Composable
private fun StatsPage(
    vpn: VpnSnapshot,
    selected: ServerProfile?,
    ping: Int?,
    downloadMbps: Double,
    uploadMbps: Double,
    dlHistory: List<Float>,
    ulHistory: List<Float>,
    rxTotal: Long,
    txTotal: Long,
    sessionSecs: Long
) {
    val connected = vpn.status == VpnStatus.CONNECTED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 16.dp)) {
        Text("Статистика", color = C.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text("Текущая скорость, трафик и параметры активного соединения", color = C.subtext, fontSize = 10.5.sp)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("СЕССИЯ", formatDuration(sessionSecs), if (connected) "активна" else "не активна", C.accentSoft, Modifier.weight(1f)) {
                ClockGlyph(Modifier.size(14.dp), C.accentSoft)
            }
            MetricCard("СКАЧАНО", formatBytes(rxTotal), "за сессию", C.accent, Modifier.weight(1f)) {
                ArrowGlyph(false, Modifier.size(14.dp), C.accent)
            }
            MetricCard("ОТПРАВЛЕНО", formatBytes(txTotal), "за сессию", C.connected, Modifier.weight(1f)) {
                ArrowGlyph(true, Modifier.size(14.dp), C.connected)
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.03f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Скорость соединения", color = C.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("Обновление раз в секунду", color = C.muted, fontSize = 9.sp)
                    }
                    Legend("Скачивание", C.accent)
                    Spacer(Modifier.width(12.dp))
                    Legend("Загрузка", C.accentSoft)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpeedValue("↓", "Скачивание", downloadMbps, C.accent)
                    SpeedValue("↑", "Загрузка", uploadMbps, C.accentSoft)
                }
                Spacer(Modifier.height(10.dp))
                DualChart(dlHistory, ulHistory, Modifier.fillMaxWidth().height(168.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(title = "Соединение", modifier = Modifier.weight(1f)) {
                InfoRow("Сервер", selected?.let { displayName(it.name) } ?: "не выбран")
                InfoRow("Протокол", selected?.let { "${it.protocol.shortLabel} · ${transportLabel(it)}" } ?: "—")
                InfoRow("Адрес", selected?.host ?: "—", mono = true)
                InfoRow("Пинг", ping?.let { "$it мс" } ?: "—")
            }
            InfoCard(title = "Режим", modifier = Modifier.weight(1f)) {
                InfoRow("Туннель", "TUN (весь трафик)")
                InfoRow("Резерв", "Proxy 127.0.0.1:2080")
                InfoRow("Домены", "маршрутизация включена")
                InfoRow("Статус", if (connected) "защищено" else statusTitle(vpn.status).lowercase(Locale.ROOT))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MetricCard(label: String, value: String, hint: String, accent: Color, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Row(
        modifier.height(66.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = C.muted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(3.dp))
            Text(value, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(hint, color = C.muted, fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun SpeedValue(arrow: String, label: String, mbps: Double, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(arrow, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(5.dp))
        Column {
            Text(formatSpeed(mbps), color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("$label · Мбит/с", color = C.muted, fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = C.muted, fontSize = 8.5.sp)
    }
}

@Composable
private fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title.uppercase(Locale.ROOT), color = C.muted, fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.muted, fontSize = 9.5.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            color = C.subtext,
            fontSize = 9.5.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp)
        )
    }
}

@Composable
private fun InfoRowBlock(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = C.muted, fontSize = 9.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = C.subtext, fontSize = 9.5.sp, lineHeight = 13.sp)
    }
}

// ---------------------------------------------------------------- settings page

@Composable
private fun SettingsPage(
    autoEnabled: Boolean,
    processes: Set<String>,
    domains: Set<String>,
    onAutoEnabled: (Boolean) -> Unit,
    onProcessesChange: (Set<String>) -> Unit,
    onDomainsChange: (Set<String>) -> Unit,
    updateAvailable: DesktopUpdater.UpdateAvailable?,
    updateChecking: Boolean,
    onCheckUpdates: () -> Unit
) {
    var procInput by remember { mutableStateOf("") }
    var domInput by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 16.dp)) {
        Text("Настройки", color = C.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text("Автоматизация, туннель и маршрутизация", color = C.subtext, fontSize = 10.5.sp)

        Spacer(Modifier.height(16.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingCard("Автоподключение", Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (autoEnabled) "Включено" else "Выключено",
                            color = if (autoEnabled) C.connected else C.subtext,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text("VPN поднимается при запуске выбранных приложений", color = C.muted, fontSize = 9.sp, lineHeight = 13.sp)
                    }
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = onAutoEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = C.accent,
                            checkedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                            uncheckedThumbColor = C.subtext,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }
            SettingCard("Защита трафика", Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(C.connected.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { ShieldGlyph(Modifier.size(15.dp), C.connected) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("TUN + Proxy", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Весь трафик через туннель", color = C.muted, fontSize = 9.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingCard("Приложения-триггеры") {
            Text("VPN включается автоматически, пока запущен хотя бы один процесс из списка.", color = C.muted, fontSize = 9.5.sp, lineHeight = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = procInput, onValueChange = { procInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Telegram.exe", color = C.muted, fontSize = 11.sp) },
                    shape = RoundedCornerShape(11.dp),
                    colors = fieldColors()
                )
                AddChipButton {
                    val v = procInput.trim()
                    if (v.isNotEmpty()) { onProcessesChange(processes + v); procInput = "" }
                }
            }
            Spacer(Modifier.height(9.dp))
            FlowChips(items = processes, onRemove = { onProcessesChange(processes - it) }, empty = "Правил нет — добавьте Telegram.exe")
        }

        Spacer(Modifier.height(12.dp))
        SettingCard("Маршрутизация доменов") {
            Text("Домены, которые направляются через VPN. Требуется подключение.", color = C.muted, fontSize = 9.5.sp, lineHeight = 14.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = domInput, onValueChange = { domInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("youtube.com", color = C.muted, fontSize = 11.sp) },
                    shape = RoundedCornerShape(11.dp),
                    colors = fieldColors()
                )
                AddChipButton {
                    val v = domInput.trim().lowercase(Locale.ROOT).removePrefix("https://").removePrefix("http://").trim('/')
                    if (v.isNotEmpty()) { onDomainsChange(domains + v); domInput = "" }
                }
            }
            Spacer(Modifier.height(9.dp))
            FlowChips(items = domains, onRemove = { onDomainsChange(domains - it) }, empty = "Доменов нет — добавьте youtube.com")
        }

        Spacer(Modifier.height(12.dp))
        SettingCard("Подключение и система") {
            InfoRowBlock("Режим туннеля", "TUN (весь трафик), резерв — прокси 127.0.0.1:2080")
            InfoRowBlock("Автозапуск", "ярлык AegisVPN (Admin) в автозагрузке Windows")
            InfoRowBlock("Сворачивание", "закрытие окна скрывает приложение в трей")
            InfoRowBlock("Zapret / winws", "останавливается автоматически перед подключением")
            InfoRowBlock("Версия", "Aegis VPN ${DesktopUpdater.APP_VERSION}")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.height(34.dp).widthIn(min = 150.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(C.accent.copy(alpha = 0.14f))
                        .border(1.dp, C.accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                        .clickable(enabled = !updateChecking) { onCheckUpdates() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            updateChecking -> "Проверяем…"
                            updateAvailable != null -> "Доступно ${updateAvailable.info.version}"
                            else -> "Проверить обновления"
                        },
                        color = C.accentSoft,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text("Обновления проверяются при запуске и вручную", color = C.muted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SettingCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(title, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(9.dp))
        content()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.accent.copy(alpha = 0.65f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
    focusedTextColor = C.text,
    unfocusedTextColor = C.text,
    cursorColor = C.accent,
    focusedContainerColor = Color.White.copy(alpha = 0.02f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.02f)
)

@Composable
private fun AddChipButton(onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        if (hovered) C.accentSoft else C.gradientStart,
                        C.gradientEnd
                    )
                )
            )
            .hoverable(hover)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(items: Set<String>, onRemove: (String) -> Unit, empty: String) {
    if (items.isEmpty()) {
        Text(empty, color = C.muted, fontSize = 10.sp)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            Row(
                Modifier.clip(RoundedCornerShape(100.dp))
                    .background(C.accent.copy(alpha = 0.09f))
                    .border(1.dp, C.accent.copy(alpha = 0.18f), RoundedCornerShape(100.dp))
                    .padding(start = 10.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item, color = C.text, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(16.dp).clip(CircleShape).clickable { onRemove(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = C.subtext, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- server card

@Composable
private fun ServerListCard(
    server: ServerProfile,
    selected: Boolean,
    status: VpnStatus,
    ping: Int?,
    onClick: () -> Unit
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> C.accent.copy(alpha = 0.11f)
            hovered -> Color.White.copy(alpha = 0.055f)
            else -> Color.White.copy(alpha = 0.028f)
        },
        tween(200), label = "cardBg"
    )
    val border by animateColorAsState(
        if (selected) C.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.055f),
        tween(200), label = "cardBorder"
    )
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(13.dp))
            .hoverable(hover)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(2.5.dp).height(28.dp).clip(RoundedCornerShape(100.dp))
                .background(if (selected) C.accent else Color.Transparent)
        )
        Spacer(Modifier.width(7.dp))
        CountryFlag(server.name, 28.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName(server.name),
                color = C.text,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag("VLESS", C.accentSoft)
                Spacer(Modifier.width(4.dp))
                Tag(transportLabel(server), C.subtext)
                Spacer(Modifier.width(5.dp))
                Text(
                    server.endpointLabel,
                    color = C.muted,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            PingLabel(ping, 9.5.sp)
            Spacer(Modifier.height(4.dp))
            ConnectionDot(selected = selected, endpointPing = ping, status = status)
        }
    }
}

@Composable
private fun ConnectionDot(selected: Boolean, endpointPing: Int?, status: VpnStatus) {
    val color = when {
        selected && status == VpnStatus.CONNECTED -> C.connected
        selected && status == VpnStatus.CONNECTING -> C.accentSoft
        selected && status == VpnStatus.ERROR -> C.danger
        endpointPing == null -> C.danger.copy(alpha = 0.7f)
        else -> C.connected.copy(alpha = 0.55f)
    }
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha by pulse.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dotAlpha"
    )
    Box(
        Modifier.size(6.dp).clip(CircleShape)
            .background(color.copy(alpha = if (selected && status == VpnStatus.CONNECTED) alpha else 0.9f))
    )
}

@Composable
private fun PingLabel(ping: Int?, fontSize: androidx.compose.ui.unit.TextUnit) {
    val color = when {
        ping == null -> C.muted
        ping < 90 -> C.connected
        ping < 220 -> C.accentSoft
        else -> C.warning
    }
    Text(
        ping?.let { "$it мс" } ?: "—",
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold
    )
}

// ---------------------------------------------------------------- flags & glyphs

@Composable
private fun CountryFlag(name: String, size: Dp) {
    val code = countryCode(name)
    val fallbackStart = C.accent
    val fallbackEnd = C.accentSoft
    Box(
        Modifier.size(size).clip(CircleShape).background(C.surfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val oval = Path().apply { addOval(Rect(0f, 0f, w, h)) }
            clipPath(oval) {
                when (code) {
                    "PL" -> {
                        drawRect(Color(0xFFF4F4F4), size = Size(w, h / 2f))
                        drawRect(Color(0xFFD22B3F), topLeft = Offset(0f, h / 2f), size = Size(w, h / 2f))
                    }
                    "FR" -> {
                        listOf(0xFF1B3E9C, 0xFFF4F4F4, 0xFFD22B3F).forEachIndexed { i, c ->
                            drawRect(Color(c), topLeft = Offset(w / 3f * i, 0f), size = Size(w / 3f + 1f, h))
                        }
                    }
                    "NL" -> {
                        listOf(0xFFAE1C28, 0xFFF4F4F4, 0xFF21468B).forEachIndexed { i, c ->
                            drawRect(Color(c), topLeft = Offset(0f, h / 3f * i), size = Size(w, h / 3f + 1f))
                        }
                    }
                    "DE" -> {
                        listOf(0xFF1A1A1A, 0xFFD22B3F, 0xFFFFC72C).forEachIndexed { i, c ->
                            drawRect(Color(c), topLeft = Offset(0f, h / 3f * i), size = Size(w, h / 3f + 1f))
                        }
                    }
                    "RU" -> {
                        listOf(0xFFF4F4F4, 0xFF1B3E9C, 0xFFD22B3F).forEachIndexed { i, c ->
                            drawRect(Color(c), topLeft = Offset(0f, h / 3f * i), size = Size(w, h / 3f + 1f))
                        }
                    }
                    "US" -> {
                        drawRect(Color(0xFFF4F4F4), size = Size(w, h))
                        for (i in 0 until 7) {
                            drawRect(Color(0xFFD22B3F), topLeft = Offset(0f, h / 7f * i), size = Size(w, h / 14f))
                        }
                        drawRect(Color(0xFF1B3E9C), size = Size(w * 0.45f, h * 0.5f))
                    }
                    "GB" -> {
                        drawRect(Color(0xFF1B3E9C), size = Size(w, h))
                        val sw = w * 0.20f
                        drawLine(Color(0xFFF4F4F4), Offset(0f, 0f), Offset(w, h), sw)
                        drawLine(Color(0xFFF4F4F4), Offset(w, 0f), Offset(0f, h), sw)
                        drawLine(Color(0xFFD22B3F), Offset(0f, 0f), Offset(w, h), sw * 0.45f)
                        drawLine(Color(0xFFD22B3F), Offset(w, 0f), Offset(0f, h), sw * 0.45f)
                        drawLine(Color(0xFFF4F4F4), Offset(w / 2f, 0f), Offset(w / 2f, h), w * 0.30f)
                        drawLine(Color(0xFFF4F4F4), Offset(0f, h / 2f), Offset(w, h / 2f), w * 0.30f)
                        drawLine(Color(0xFFD22B3F), Offset(w / 2f, 0f), Offset(w / 2f, h), w * 0.17f)
                        drawLine(Color(0xFFD22B3F), Offset(0f, h / 2f), Offset(w, h / 2f), w * 0.17f)
                    }
                    "CH" -> {
                        drawRect(Color(0xFFD22B3F), size = Size(w, h))
                        drawRect(Color(0xFFF4F4F4), topLeft = Offset(w * 0.42f, h * 0.18f), size = Size(w * 0.16f, h * 0.64f))
                        drawRect(Color(0xFFF4F4F4), topLeft = Offset(w * 0.18f, h * 0.42f), size = Size(w * 0.64f, h * 0.16f))
                    }
                    "JP" -> {
                        drawRect(Color(0xFFF4F4F4), size = Size(w, h))
                        drawCircle(Color(0xFFD22B3F), radius = w * 0.30f, center = Offset(w / 2f, h / 2f))
                    }
                    "SG" -> {
                        drawRect(Color(0xFFD22B3F), size = Size(w, h / 2f))
                        drawRect(Color(0xFFF4F4F4), topLeft = Offset(0f, h / 2f), size = Size(w, h / 2f))
                    }
                    "HK" -> {
                        drawRect(Color(0xFFD22B3F), size = Size(w, h))
                        drawCircle(Color(0xFFF4F4F4), radius = w * 0.16f, center = Offset(w / 2f, h / 2f))
                    }
                    "CA" -> {
                        drawRect(Color(0xFFF4F4F4), size = Size(w, h))
                        drawRect(Color(0xFFD22B3F), size = Size(w * 0.26f, h))
                        drawRect(Color(0xFFD22B3F), topLeft = Offset(w * 0.74f, 0f), size = Size(w * 0.26f, h))
                        drawCircle(Color(0xFFD22B3F), radius = w * 0.14f, center = Offset(w / 2f, h / 2f))
                    }
                    "CZ" -> {
                        drawRect(Color(0xFFF4F4F4), size = Size(w, h / 2f))
                        drawRect(Color(0xFFD22B3F), topLeft = Offset(0f, h / 2f), size = Size(w, h / 2f))
                        val tri = Path().apply {
                            moveTo(0f, 0f); lineTo(w * 0.50f, h / 2f); lineTo(0f, h); close()
                        }
                        drawPath(tri, Color(0xFF11457E))
                    }
                    "AT" -> {
                        listOf(0xFFD22B3F, 0xFFF4F4F4, 0xFFD22B3F).forEachIndexed { i, c ->
                            drawRect(Color(c), topLeft = Offset(0f, h / 3f * i), size = Size(w, h / 3f + 1f))
                        }
                    }
                    "EE" -> {
                        listOf(0xFF0072CE, 0xFF1A1A1A, 0xFFF4F4F4).forEachIndexed { i, c ->
                            drawRect(Color(c), topLeft = Offset(0f, h / 3f * i), size = Size(w, h / 3f + 1f))
                        }
                    }
                    "SE" -> {
                        drawRect(Color(0xFF006AA7), size = Size(w, h))
                        drawRect(Color(0xFFFECC02), topLeft = Offset(w * 0.30f, 0f), size = Size(w * 0.16f, h))
                        drawRect(Color(0xFFFECC02), topLeft = Offset(0f, h * 0.42f), size = Size(w, h * 0.16f))
                    }
                    "FI" -> {
                        drawRect(Color(0xFFF4F4F4), size = Size(w, h))
                        drawRect(Color(0xFF1B3E9C), topLeft = Offset(w * 0.28f, 0f), size = Size(w * 0.18f, h))
                        drawRect(Color(0xFF1B3E9C), topLeft = Offset(0f, h * 0.41f), size = Size(w, h * 0.18f))
                    }
                    "" -> {
                        drawRect(
                            Brush.linearGradient(listOf(fallbackStart, fallbackEnd), start = Offset(0f, 0f), end = Offset(w, h))
                        )
                        drawCircle(Color.White.copy(alpha = 0.85f), radius = w * 0.22f, center = Offset(w / 2f, h / 2f), style = Stroke(w * 0.06f))
                        drawLine(Color.White.copy(alpha = 0.85f), Offset(w / 2f, h * 0.28f), Offset(w / 2f, h * 0.72f), w * 0.06f)
                    }
                }
            }
            drawCircle(Color.White.copy(alpha = 0.18f), radius = w / 2f - 0.6f, style = Stroke(1.2f))
        }
    }
}

@Composable
private fun PowerGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = w * 0.085f
        drawArc(
            color = color,
            startAngle = 285f,
            sweepAngle = 330f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(w - stroke, w - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(color, Offset(w / 2f, w * 0.03f), Offset(w / 2f, w * 0.45f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ShieldGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.88f, h * 0.22f)
            lineTo(w * 0.88f, h * 0.50f)
            cubicTo(w * 0.88f, h * 0.76f, w * 0.70f, h * 0.91f, w * 0.50f, h * 0.96f)
            cubicTo(w * 0.30f, h * 0.91f, w * 0.12f, h * 0.76f, w * 0.12f, h * 0.50f)
            lineTo(w * 0.12f, h * 0.22f)
            close()
        }
        drawPath(path, color, style = Stroke(w * 0.085f, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.34f, h * 0.50f), Offset(w * 0.46f, h * 0.62f), w * 0.085f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.46f, h * 0.62f), Offset(w * 0.67f, h * 0.38f), w * 0.085f, StrokeCap.Round)
    }
}

@Composable
private fun ArrowGlyph(up: Boolean, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = w * 0.10f
        if (up) {
            drawLine(color, Offset(w * 0.5f, w * 0.85f), Offset(w * 0.5f, w * 0.20f), s, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, w * 0.20f), Offset(w * 0.24f, w * 0.46f), s, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, w * 0.20f), Offset(w * 0.76f, w * 0.46f), s, StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.5f, w * 0.15f), Offset(w * 0.5f, w * 0.80f), s, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, w * 0.80f), Offset(w * 0.24f, w * 0.54f), s, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, w * 0.80f), Offset(w * 0.76f, w * 0.54f), s, StrokeCap.Round)
        }
    }
}

@Composable
private fun ClockGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = w * 0.09f
        drawCircle(color, radius = w / 2f - s / 2f, style = Stroke(s))
        drawLine(color, Offset(w / 2f, w * 0.27f), Offset(w / 2f, w * 0.52f), s, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, w * 0.52f), Offset(w * 0.68f, w * 0.62f), s, StrokeCap.Round)
    }
}

@Composable
private fun PingGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = w * 0.10f
        drawCircle(color, radius = w * 0.10f, center = Offset(w / 2f, w / 2f))
        drawArc(color, -45f, 90f, false, topLeft = Offset(w * 0.20f, w * 0.20f), size = Size(w * 0.60f, w * 0.60f), style = Stroke(s, cap = StrokeCap.Round))
        drawArc(color, -45f, 90f, false, topLeft = Offset(w * 0.02f, w * 0.02f), size = Size(w * 0.96f, w * 0.96f), style = Stroke(s, cap = StrokeCap.Round))
    }
}

@Composable
private fun TrashGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = w * 0.10f
        drawLine(color, Offset(w * 0.22f, h * 0.26f), Offset(w * 0.78f, h * 0.26f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.18f), Offset(w * 0.60f, h * 0.18f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, h * 0.30f), Offset(w * 0.34f, h * 0.84f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.70f, h * 0.30f), Offset(w * 0.66f, h * 0.84f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), s, StrokeCap.Round)
    }
}

@Composable
private fun ServerGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = w * 0.075f
        val r = CornerRadius(w * 0.12f)
        drawRoundRect(color, topLeft = Offset(w * 0.14f, h * 0.16f), size = Size(w * 0.72f, h * 0.28f), cornerRadius = r, style = Stroke(s))
        drawRoundRect(color, topLeft = Offset(w * 0.14f, h * 0.56f), size = Size(w * 0.72f, h * 0.28f), cornerRadius = r, style = Stroke(s))
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.28f, h * 0.30f))
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.28f, h * 0.70f))
    }
}

@Composable
private fun GearGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.minDimension
        val c = Offset(w / 2f, w / 2f)
        val s = w * 0.085f
        drawCircle(color, radius = w * 0.20f, center = c, style = Stroke(s))
        drawCircle(color, radius = w * 0.37f, center = c, style = Stroke(s * 0.75f))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            drawLine(
                color,
                Offset(c.x + (w * 0.37f * cos(a)).toFloat(), c.y + (w * 0.37f * sin(a)).toFloat()),
                Offset(c.x + (w * 0.47f * cos(a)).toFloat(), c.y + (w * 0.47f * sin(a)).toFloat()),
                s,
                StrokeCap.Round
            )
        }
    }
}

@Composable
private fun NavGlyph(page: Page, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = w * 0.10f
        when (page) {
            Page.Home -> {
                val p = Path().apply {
                    moveTo(w * 0.14f, h * 0.50f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.50f)
                }
                drawPath(p, color, style = Stroke(s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawRoundRect(color, topLeft = Offset(w * 0.24f, h * 0.48f), size = Size(w * 0.52f, h * 0.38f), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(s))
            }
            Page.Servers -> {
                val r = CornerRadius(w * 0.12f)
                drawRoundRect(color, topLeft = Offset(w * 0.14f, h * 0.16f), size = Size(w * 0.72f, h * 0.28f), cornerRadius = r, style = Stroke(s))
                drawRoundRect(color, topLeft = Offset(w * 0.14f, h * 0.56f), size = Size(w * 0.72f, h * 0.28f), cornerRadius = r, style = Stroke(s))
                drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.28f, h * 0.30f))
                drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.28f, h * 0.70f))
            }
            Page.Stats -> {
                drawLine(color, Offset(w * 0.20f, h * 0.84f), Offset(w * 0.20f, h * 0.58f), s * 1.6f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.50f, h * 0.84f), Offset(w * 0.50f, h * 0.32f), s * 1.6f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.80f, h * 0.84f), Offset(w * 0.80f, h * 0.46f), s * 1.6f, StrokeCap.Round)
            }
            Page.Settings -> {
                val c = Offset(w / 2f, h / 2f)
                drawCircle(color, radius = w * 0.20f, center = c, style = Stroke(s))
                drawCircle(color, radius = w * 0.37f, center = c, style = Stroke(s * 0.75f))
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    drawLine(
                        color,
                        Offset(c.x + (w * 0.37f * cos(a)).toFloat(), c.y + (w * 0.37f * sin(a)).toFloat()),
                        Offset(c.x + (w * 0.47f * cos(a)).toFloat(), c.y + (w * 0.47f * sin(a)).toFloat()),
                        s,
                        StrokeCap.Round
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- charts

@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val reveal by animateFloatAsState(if (appeared) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "reveal")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(0.6f)
        val bottom = h - 4f

        drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, bottom), Offset(w, bottom), 1f)

        if (values.size < 2) {
            drawLine(color.copy(alpha = 0.30f), Offset(0f, h / 2f), Offset(w, h / 2f), 1.5f)
            return@Canvas
        }
        val stepX = w / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = bottom - (v / maxV) * (h - 5f) * reveal
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, bottom)
            lineTo(0f, bottom)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), Color.Transparent), startY = 0f, endY = h))
        drawPath(line, Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color)), style = Stroke(1.7f, cap = StrokeCap.Round))
        val lastX = w
        val lastY = bottom - (values.last() / maxV) * (h - 5f) * reveal
        drawCircle(color, radius = 2.2f, center = Offset(lastX, lastY))
    }
}

@Composable
private fun DualChart(dl: List<Float>, ul: List<Float>, modifier: Modifier = Modifier) {
    val accent = C.accent
    val accentSoft = C.accentSoft
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val maxV = maxOf(dl.maxOrNull() ?: 0f, ul.maxOrNull() ?: 0f).coerceAtLeast(0.6f)

        for (i in 0..4) {
            val y = h * i / 4f
            drawLine(Color.White.copy(alpha = 0.045f), Offset(0f, y), Offset(w, y), 1f)
        }

        fun series(values: List<Float>, color: Color, fill: Boolean) {
            if (values.size < 2) return
            val stepX = w / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i * stepX
                val y = h - 4f - (v / maxV) * (h - 12f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (fill) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent), startY = 0f, endY = h))
            }
            drawPath(path, Brush.horizontalGradient(listOf(color.copy(alpha = 0.30f), color)), style = Stroke(2f, cap = StrokeCap.Round))
            drawCircle(color, radius = 2.4f, center = Offset(w, h - 4f - (values.last() / maxV) * (h - 12f)))
        }

        series(dl, accent, true)
        series(ul, accentSoft, false)
    }
}

@Composable
private fun Equalizer(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(Modifier.size(width = 34.dp, height = 16.dp)) {
        val bars = 5
        val gap = size.width / (bars * 1.7f)
        val barWidth = gap * 0.7f
        for (i in 0 until bars) {
            val amp = if (active) 0.35f + 0.65f * ((sin(phase + i * 0.9f) + 1f) / 2f) else 0.30f
            val bh = size.height * amp
            drawRoundRect(
                color.copy(alpha = if (active) 0.85f else 0.35f),
                topLeft = Offset(i * gap * 1.7f, size.height - bh),
                size = Size(barWidth, bh),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}
