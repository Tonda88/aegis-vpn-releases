package com.aegis.vpn.core

import com.aegis.vpn.data.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Выбирает сервер с наилучшим пингом.
 *
 * Задержка измеряется TCP-подключением к host:port: это тот же путь, который
 * использует туннель, и он не зависит от ICMP (его часто блокируют).
 * Все серверы проверяются параллельно, поэтому общее время ожидания —
 * не больше одного таймаута.
 */
object ServerSelector {

    const val DEFAULT_TIMEOUT_MS = 1500

    /** TCP-connect в миллисекундах или null, если сервер не ответил. */
    fun measure(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int? {
        if (host.isBlank() || port !in 1..65535) return null
        return runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        }.getOrNull()
    }

    /**
     * Возвращает самый быстрый сервер или null, если ни один не ответил.
     * Один сервер не проверяется: выбирать не из чего, а задержка при
     * подключении пользователю не нужна.
     */
    suspend fun pickBest(
        servers: List<ServerProfile>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        probe: suspend (ServerProfile) -> Int? = { measure(it.host, it.port, timeoutMs) }
    ): ServerProfile? {
        if (servers.isEmpty()) return null
        if (servers.size == 1) return servers.first()

        val measured = coroutineScope {
            servers.map { server ->
                async(Dispatchers.IO) { server to probe(server) }
            }.awaitAll()
        }

        return measured
            .filter { it.second != null }
            .minByOrNull { it.second!! }
            ?.first
    }
}
