package com.aegis.vpn.config

import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and decodes subscription links.
 *
 * Many panels return a Base64 list of share links for regular clients and a
 * placeholder list for unknown clients. Sending the v2rayNG user agent plus a
 * stable hardware id makes such panels return the real configuration. The
 * hardware id is generated once per installation and stored in preferences.
 */
object Subscription {

    private val supportedPrefixes = listOf("vless://", "ss://", "wg://", "wireguard://")

    fun fetch(url: String, hwid: String): String {
        val parsed = URL(url)
        require(parsed.protocol == "http" || parsed.protocol == "https") {
            "Ссылка должна начинаться с http:// или https://"
        }

        val connection = parsed.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "v2rayNG/1.9.16")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("x-hwid", hwid)

            val code = connection.responseCode
            if (code !in 200..299) {
                val details = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                error("HTTP $code${if (details.isBlank()) "" else ": ${details.take(100)}"}")
            }

            val builder = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.append(buffer, 0, read)
                    if (builder.length > 4_000_000) error("Ответ подписки слишком большой")
                }
            }
            builder.toString()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Accepts either a plain list of share links or a Base64-encoded list.
     * Unsupported protocols (Hysteria2, TUIC, Trojan, ...) are ignored.
     */
    fun decode(body: String): List<String> {
        var text = body.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()

        val looksLikeList = text.contains("://") || text.contains("[Interface]", ignoreCase = true)
        if (!looksLikeList) {
            text = runCatching { Base64Codec.decodeToText(text) }.getOrNull()
                ?.removePrefix("\uFEFF")
                ?: return emptyList()
        }

        return text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith("//")
            }
            .filter { line ->
                supportedPrefixes.any { line.startsWith(it, ignoreCase = true) } ||
                    line.contains("[Interface]", ignoreCase = true)
            }
            .toList()
    }
}
