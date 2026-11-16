package com.aegis.vpn.desktop

import java.util.concurrent.TimeUnit

/**
 * Stops DPI-bypass tools which conflict with the TUN adapter (zapret / winws).
 * The app runs elevated, so it can stop the Windows service as well.
 */
object ConflictManager {
    data class StopResult(
        val stoppedServices: List<String> = emptyList(),
        val stoppedProcesses: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        val changed: Boolean get() = stoppedServices.isNotEmpty() || stoppedProcesses.isNotEmpty()

        fun summary(): String? = when {
            changed -> buildString {
                append("Остановлен zapret")
                val details = mutableListOf<String>()
                if (stoppedServices.isNotEmpty()) details += "служба ${stoppedServices.joinToString(", ")}"
                if (stoppedProcesses.isNotEmpty()) details += "процесс ${stoppedProcesses.joinToString(", ")}"
                if (details.isNotEmpty()) append(" (${details.joinToString("; ")})")
            }
            errors.isNotEmpty() -> "zapret: ${errors.first()}"
            else -> null
        }
    }

    private enum class ServiceState { NOT_FOUND, STOPPED, RUNNING, PENDING, UNKNOWN }

    private val serviceNames = listOf("zapret", "zapret-discord", "zapret-discord-youtube")
    private val processNames = listOf("winws.exe", "zapret.exe", "zapret-discord.exe")

    private val stateRegex = Regex(
        """:\s*([1-7])\s+(RUNNING|STOPPED|START_PENDING|STOP_PENDING|PAUSED|PAUSE_PENDING|CONTINUE_PENDING)"""
    )

    @Synchronized
    fun stopDpiBypass(): StopResult {
        val stoppedServices = mutableListOf<String>()
        val stoppedProcesses = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (service in serviceNames) {
            when (queryState(service)) {
                ServiceState.NOT_FOUND, ServiceState.STOPPED -> Unit
                ServiceState.RUNNING, ServiceState.PENDING -> {
                    val stop = run("sc.exe", "stop", service)
                    if (stop.code != 0 && stop.code != 1062) {
                        errors += "не удалось остановить службу $service (код ${stop.code})"
                    } else if (waitForState(service, ServiceState.STOPPED, 8_000)) {
                        stoppedServices += service
                    } else {
                        // A service wrapper may keep winws alive; kill the image and check again.
                        processNames.forEach { stopProcess(it, stoppedProcesses, errors) }
                        if (waitForState(service, ServiceState.STOPPED, 3_000)) {
                            stoppedServices += service
                        } else if (errors.none { it.contains(service) }) {
                            errors += "служба $service не остановилась"
                        }
                    }
                }
                ServiceState.UNKNOWN -> errors += "не удалось определить состояние службы $service"
            }
        }

        // Kill standalone (non-service) zapret instances too.
        processNames.forEach { stopProcess(it, stoppedProcesses, errors) }

        return StopResult(
            stoppedServices = stoppedServices.distinct(),
            stoppedProcesses = stoppedProcesses.distinct(),
            errors = errors.distinct()
        )
    }

    private fun stopProcess(name: String, stopped: MutableList<String>, errors: MutableList<String>) {
        if (!isProcessRunning(name)) return
        val result = run("taskkill.exe", "/F", "/T", "/IM", name)
        if (result.code == 0) stopped += name
        else if (isProcessRunning(name)) errors += "не удалось остановить процесс $name"
    }

    private fun isProcessRunning(name: String): Boolean {
        val result = run("tasklist.exe", "/FI", "IMAGENAME eq $name", "/NH")
        return result.code == 0 && result.output.contains(name, ignoreCase = true)
    }

    private fun queryState(service: String): ServiceState {
        val result = run("sc.exe", "query", service)
        if (result.code == 1060 || result.output.contains("1060")) return ServiceState.NOT_FOUND
        if (result.code != 0) return ServiceState.UNKNOWN

        val state = stateRegex.find(result.output)?.groupValues?.get(1)?.toIntOrNull()
            ?: return ServiceState.UNKNOWN
        return when (state) {
            1 -> ServiceState.STOPPED
            4 -> ServiceState.RUNNING
            2, 3 -> ServiceState.PENDING
            else -> ServiceState.UNKNOWN
        }
    }

    private fun waitForState(service: String, desired: ServiceState, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = queryState(service)
            if (state == desired || (desired == ServiceState.STOPPED && state == ServiceState.NOT_FOUND)) return true
            Thread.sleep(250)
        }
        val finalState = queryState(service)
        return finalState == desired || (desired == ServiceState.STOPPED && finalState == ServiceState.NOT_FOUND)
    }

    private data class CmdResult(val code: Int, val output: String)

    private fun run(vararg command: String): CmdResult = try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!finished) {
            process.destroyForcibly()
            CmdResult(-1, output)
        } else {
            CmdResult(process.exitValue(), output)
        }
    } catch (e: Exception) {
        CmdResult(-1, e.message.orEmpty())
    }
}
