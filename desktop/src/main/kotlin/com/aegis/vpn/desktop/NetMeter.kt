package com.aegis.vpn.desktop

import java.io.File
import kotlin.concurrent.thread

/**
 * Lightweight network throughput meter. Reads cumulative adapter byte counters from Windows
 * through a single long-lived PowerShell process and exposes current rates + session totals.
 * Purely additive UI telemetry: it never touches the VPN engine.
 */
class NetMeter {
    @Volatile var downloadMbps: Double = 0.0
        private set
    @Volatile var uploadMbps: Double = 0.0
        private set
    @Volatile var rxTotal: Long = 0L
        private set
    @Volatile var txTotal: Long = 0L
        private set
    @Volatile var available: Boolean = false
        private set

    @Volatile var accumulate: Boolean = false

    private var process: Process? = null

    fun start() {
        if (process != null) return
        thread(isDaemon = true, name = "aegis-net-meter") {
            runCatching {
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString(script.toByteArray(Charsets.UTF_16LE))
                val ps = ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded
                ).redirectErrorStream(true).start()
                process = ps
                val reader = ps.inputStream.bufferedReader()
                var prevRx = -1L
                var prevTx = -1L
                var prevAt = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    val parts = line.trim().split(' ')
                    if (parts.size != 2) continue
                    val rx = parts[0].toLongOrNull() ?: continue
                    val tx = parts[1].toLongOrNull() ?: continue
                    val now = System.nanoTime()
                    if (prevRx >= 0L && now > prevAt) {
                        val dt = (now - prevAt) / 1_000_000_000.0
                        if (dt > 0.05) {
                            val dRx = (rx - prevRx).coerceAtLeast(0L)
                            val dTx = (tx - prevTx).coerceAtLeast(0L)
                            downloadMbps = dRx * 8.0 / 1_000_000.0 / dt
                            uploadMbps = dTx * 8.0 / 1_000_000.0 / dt
                            if (accumulate) {
                                rxTotal += dRx
                                txTotal += dTx
                            }
                        }
                    }
                    prevRx = rx
                    prevTx = tx
                    prevAt = now
                    available = true
                }
            }
            process = null
        }
    }

    fun resetTotals() {
        rxTotal = 0L
        txTotal = 0L
    }

    fun stop() {
        runCatching { process?.destroyForcibly() }
        process = null
    }

    private companion object {
        val script: String = buildString {
            append("\$ErrorActionPreference='SilentlyContinue'\n")
            append("\$ProgressPreference='SilentlyContinue'\n")
            append("while(\$true){\n")
            append("  \$rows = Get-NetAdapterStatistics\n")
            append("  if(\$rows){\n")
            append("    \$rx = (\$rows | Measure-Object -Property ReceivedBytes -Sum).Sum\n")
            append("    \$tx = (\$rows | Measure-Object -Property SentBytes -Sum).Sum\n")
            append("    if(\$rx -ne \$null -and \$tx -ne \$null){ Write-Output (\"{0} {1}\" -f [int64]\$rx, [int64]\$tx) }\n")
            append("  }\n")
            append("  Start-Sleep -Milliseconds 1000\n")
            append("}\n")
        }

        @Suppress("unused")
        val javaHome: String = System.getProperty("java.home") ?: File(".").absolutePath
    }
}
