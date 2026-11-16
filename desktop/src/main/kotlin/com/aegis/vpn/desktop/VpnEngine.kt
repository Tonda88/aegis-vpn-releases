package com.aegis.vpn.desktop

import com.aegis.vpn.config.ConfigParser
import com.aegis.vpn.config.SingBoxConfig
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnSnapshot
import com.aegis.vpn.data.VpnStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.TimeUnit

class VpnEngine {
    private val _state = MutableStateFlow(VpnSnapshot())
    val state: StateFlow<VpnSnapshot> = _state
    private var process: Process? = null
    private var logJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AegisVPN-Desktop").apply { mkdirs() }
    private val configFile = File(workDir, "config.json")
    private val logFile = File(workDir, "sing-box.log")
    @Volatile var proxyDomains: Set<String> = emptySet()

    fun connect(profile: ServerProfile) {
        if (_state.value.status == VpnStatus.CONNECTING || _state.value.status == VpnStatus.CONNECTED) disconnect()
        _state.value = VpnSnapshot(VpnStatus.CONNECTING, profile.name)
        scope.launch {
            try {
                // zapret/winws uses WinDivert and conflicts with the sing-box TUN adapter.
                ConflictManager.stopDpiBypass()
                val parsed = ConfigParser.parse(profile.raw).getOrThrow()
                val exe = findSingBox() ?: error("sing-box.exe не найден. Поместите sing-box.exe рядом с приложением или в папку tools.")

                fun logTail(n: Int = 8): String = runCatching { logFile.readLines().takeLast(n).joinToString(" | ") }.getOrDefault("")
                fun isBindError(t: String) = t.contains("address already in use", true) || t.contains("bind:", true) || t.contains("Only one usage", true)
                fun isTunFail(t: String) = t.contains("Access is denied", true) || t.contains("configure tun", true) || t.contains("wintun", true) || t.contains("operation not permitted", true)

                suspend fun startWith(tun: Boolean, port: Int): Pair<Process, Long>? {
                    configFile.writeText(SingBoxConfig.build(parsed, tun, port, proxyDomains))
                    runCatching { logFile.writeText("") }
                    val pb = ProcessBuilder(exe.absolutePath, "run", "-c", configFile.absolutePath)
                        .directory(exe.parentFile ?: workDir)
                        .redirectErrorStream(true)
                    pb.environment()["ENABLE_DEPRECATED_SPECIAL_OUTBOUNDS"] = "true"
                    val p = pb.start()
                    process = p
                    val startedAt = System.currentTimeMillis()
                    logJob = launch {
                        runCatching {
                            logFile.bufferedWriter().use { w ->
                                p.inputStream.bufferedReader().forEachLine { line ->
                                    w.appendLine(line); w.flush()
                                }
                            }
                        }
                    }
                    delay(1500)
                    return if (p.isAlive) (p to startedAt) else {
                        runCatching { p.waitFor(1, TimeUnit.SECONDS) }
                        runCatching { if (p.isAlive) p.destroyForcibly() }
                        logJob?.cancel()
                        logJob = null
                        process = null
                        delay(200)
                        null
                    }
                }

                var usedPort = 2080
                var running = startWith(tun = true, port = usedPort)
                var proxyFallback = false
                var portNote: String? = null
                if (running == null) {
                    var tail = logTail()
                    if (isTunFail(tail)) {
                        // try proxy-only, possibly on different ports if bind conflict
                        for (port in listOf(2080, 2081, 2082, 2083, 8888)) {
                            usedPort = port
                            running = startWith(tun = false, port = port)
                            if (running != null) { proxyFallback = true; break }
                            tail = logTail()
                            if (!isBindError(tail)) break
                        }
                        if (usedPort != 2080 && running != null) portNote = "прокси 127.0.0.1:$usedPort"
                    } else if (isBindError(tail)) {
                        // try other ports keeping TUN attempt as failed path? try proxy-only with alt ports
                        for (port in listOf(2081, 2082, 2083, 8888)) {
                            usedPort = port
                            running = startWith(tun = true, port = port)
                            if (running != null) break
                            tail = logTail()
                            if (isTunFail(tail)) {
                                for (p2 in listOf(port, 2080, 2081, 2082, 2083, 8888).distinct()) {
                                    usedPort = p2
                                    running = startWith(tun = false, port = p2)
                                    if (running != null) { proxyFallback = true; break }
                                    if (!isBindError(logTail())) break
                                }
                                break
                            }
                            if (!isBindError(tail)) break
                        }
                        if (usedPort != 2080 && running != null) portNote = "прокси 127.0.0.1:$usedPort"
                    }
                    if (running == null) {
                        val tail2 = runCatching { logFile.readLines().takeLast(8).joinToString("\n") }.getOrDefault(tail)
                        _state.value = VpnSnapshot(VpnStatus.ERROR, profile.name, "sing-box завершился. ${tail2.take(300)}")
                        return@launch
                    }
                }

                val (p, startedAt) = running!!
                val note = when {
                    proxyFallback && portNote != null -> "TUN недоступен — включён $portNote (запустите от имени администратора для режима всего трафика)"
                    proxyFallback -> "TUN недоступен — включён прокси 127.0.0.1:$usedPort (запустите от имени администратора для режима всего трафика)"
                    portNote != null -> "Подключено ($portNote)"
                    else -> null
                }
                _state.value = VpnSnapshot(VpnStatus.CONNECTED, profile.name, note, startedAt)
                // monitor exit
                launch {
                    val code = p.waitFor()
                    if (process === p) {
                        process = null
                        val current = _state.value
                        if (current.status == VpnStatus.CONNECTED || current.status == VpnStatus.CONNECTING) {
                            val tail = runCatching { logFile.readLines().takeLast(6).joinToString(" | ") }.getOrDefault("")
                            _state.value = VpnSnapshot(VpnStatus.ERROR, profile.name, "Соединение завершено ($code). ${tail.take(250)}")
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = VpnSnapshot(VpnStatus.ERROR, profile.name, e.message?.take(300) ?: "Ошибка подключения")
            }
        }
    }

    fun disconnect() {
        val name = _state.value.serverName
        _state.value = VpnSnapshot(VpnStatus.STOPPING, name)
        scope.launch {
            val p = process
            process = null
            runCatching {
                p?.destroy()
                p?.waitFor(3, TimeUnit.SECONDS)
                if (p?.isAlive == true) p.destroyForcibly()
            }
            logJob?.cancel()
            logJob = null
            _state.value = VpnSnapshot(VpnStatus.DISCONNECTED)
        }
    }

    val isActive: Boolean get() = _state.value.status == VpnStatus.CONNECTED || _state.value.status == VpnStatus.CONNECTING
    val isTransitioning: Boolean get() = _state.value.status == VpnStatus.CONNECTING || _state.value.status == VpnStatus.STOPPING


    private fun findSingBox(): File? {
        val names = listOf("sing-box.exe", "sing-box")
        val candidates = mutableListOf<File>()
        // next to running app/jar
        val codeSrc = runCatching { File(VpnEngine::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        if (codeSrc != null) names.forEach { candidates.add(File(codeSrc, it)) }
        val cwd = File(System.getProperty("user.dir"))
        names.forEach { candidates.add(File(cwd, it)) }
        names.forEach { candidates.add(File(cwd, "tools/$it")) }
        names.forEach { candidates.add(File(cwd, "tools/sing-box-1.14.4-windows-amd64/$it")) }
        // known dev location
        names.forEach { candidates.add(File("C:\\vpnapp\\tools", it)) }
        names.forEach { candidates.add(File("C:\\vpnapp\\tools\\sing-box-1.14.4-windows-amd64", it)) }
        return candidates.firstOrNull { it.exists() && it.isFile }
    }
}
