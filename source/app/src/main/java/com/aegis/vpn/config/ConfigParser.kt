package com.aegis.vpn.config

import com.aegis.vpn.data.ParsedProfile
import com.aegis.vpn.data.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ConfigParser {

    fun parse(rawInput: String): Result<ParsedProfile> = runCatching {
        val raw = rawInput.trim().removePrefix("\uFEFF")
        require(raw.isNotBlank()) { "Пустая строка" }
        when {
            raw.startsWith("vless://", ignoreCase = true) -> parseVless(raw)
            raw.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(raw)
            raw.startsWith("wg://", ignoreCase = true) ||
                raw.startsWith("wireguard://", ignoreCase = true) -> parseWireGuardUrl(raw)
            raw.contains("[Interface]", ignoreCase = true) -> parseWireGuardConf(raw)
            else -> error("Неизвестный формат. Поддерживаются vless://, ss://, wg:// и WireGuard .conf")
        }
    }

    private data class LinkParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
        val rawQuery: String
    )

    private fun splitLink(raw: String, scheme: String, defaultPort: Int): LinkParts {
        var body = raw.substringAfter("://", "")
        val fragment = if (body.contains('#')) decodeComponent(body.substringAfter('#')) else ""
        body = body.substringBefore('#')
        val rawQuery = body.substringAfter('?', "")
        body = body.substringBefore('?')
        require(body.isNotBlank()) { "В ссылке нет адреса сервера" }
        require(body.contains('@')) { "В ссылке нет пользовательских данных" }
        val userInfo = body.substringBeforeLast('@')
        val authority = body.substringAfterLast('@')
        val (host, port) = splitHostPort(authority, defaultPort)
        val query = parseQuery(rawQuery)
        return LinkParts(userInfo, host, port, query, fragment, rawQuery)
    }

    private fun parseVless(raw: String): ParsedProfile {
        val link = splitLink(raw, "vless://", 443)
        val uuid = decodeComponent(link.userInfo).trim()
        require(uuid.isNotBlank()) { "Не указан UUID" }

        val params = link.query
        val network = params["type"]?.lowercase().orEmpty().ifBlank { "tcp" }
        val security = params["security"]?.lowercase().orEmpty().ifBlank { "none" }
        val flow = params["flow"].orEmpty()
        val host = link.host
        require(host.isNotBlank()) { "Не указан хост сервера" }

        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", link.port)
            .put("uuid", uuid)

        val tlsEnabled = security == "tls" || security == "reality" || params.containsKey("tls")
        if (tlsEnabled && flow.isNotBlank() && flow != "none") {
            outbound.put("flow", flow)
        }

        if (tlsEnabled) {
            val tls = JSONObject().put("enabled", true)
            val sni = params["sni"]?.takeIf { it.isNotBlank() }
                ?: params["peer"]?.takeIf { it.isNotBlank() }
                ?: host
            tls.put("server_name", sni)

            params["allowInsecure"]?.let { tls.put("insecure", it == "1" || it.equals("true", true)) }
            params["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                tls.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            }
            params["fp"]?.takeIf { it.isNotBlank() }?.let { fingerprint ->
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            }

            if (security == "reality" || params.containsKey("pbk")) {
                val publicKey = params["pbk"].orEmpty()
                    .trim()
                    .replace('+', '-')
                    .replace('/', '_')
                    .trimEnd('=')
                require(publicKey.isNotBlank()) { "Reality-ссылка без public key (pbk)" }
                tls.put(
                    "reality",
                    JSONObject()
                        .put("enabled", true)
                        .put("public_key", publicKey)
                        .put("short_id", params["sid"].orEmpty())
                )
            }
            outbound.put("tls", tls)
        }

        val transport = when (network) {
            "tcp", "none" -> null
            "ws" -> {
                val path = params["path"].orEmpty().ifBlank { "/" }
                val wsHost = params["host"].orEmpty().ifBlank { params["sni"].orEmpty() }
                JSONObject().put("type", "ws").put("path", path).also {
                    if (wsHost.isNotBlank()) {
                        it.put("headers", JSONObject().put("Host", wsHost))
                    }
                    params["ed"]?.toIntOrNull()?.takeIf { value -> value > 0 }?.let { value ->
                        it.put("max_early_data", value)
                        params["eh"]?.takeIf { header -> header.isNotBlank() }?.let { header ->
                            it.put("early_data_header_name", header)
                        }
                    }
                }
            }
            "grpc" -> {
                val service = params["serviceName"].orEmpty().ifBlank {
                    params["path"].orEmpty().trim('/')
                }
                JSONObject().put("type", "grpc").put("service_name", service)
            }
            "http", "h2" -> {
                val hosts = params["host"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                JSONObject().put("type", "http").put("path", params["path"].orEmpty().ifBlank { "/" })
                    .also { if (hosts.isNotEmpty()) it.put("host", JSONArray(hosts)) }
            }
            "httpupgrade" -> {
                JSONObject().put("type", "httpupgrade")
                    .put("host", params["host"].orEmpty())
                    .put("path", params["path"].orEmpty().ifBlank { "/" })
            }
            else -> error("Транспорт «$network» пока не поддерживается")
        }
        if (transport != null) outbound.put("transport", transport)

        val name = link.fragment.ifBlank { "VLESS · $host" }
        return ParsedProfile(cleanName(name), VpnProtocol.VLESS, host, link.port, outbound, false)
    }

    private fun parseShadowsocks(raw: String): ParsedProfile {
        var body = raw.substringAfter("://", "")
        val fragment = if (body.contains('#')) decodeComponent(body.substringAfter('#')) else ""
        body = body.substringBefore('#')
        val rawQuery = body.substringAfter('?', "")
        body = body.substringBefore('?')
        require(body.isNotBlank()) { "Пустая ss:// ссылка" }

        var method = ""
        var password = ""
        var host = ""
        var port = 0

        if (body.contains('@')) {
            val userInfo = body.substringBeforeLast('@')
            val authority = body.substringAfterLast('@')
            val credentials = decodeSsUserInfo(userInfo)
            val colon = credentials.indexOf(':')
            require(colon > 0) { "В ss:// нет метода шифрования и пароля" }
            method = credentials.substring(0, colon)
            password = credentials.substring(colon + 1)
            val parsed = splitHostPort(authority, 8388)
            host = parsed.first
            port = parsed.second
        } else {
            val decoded = Base64Codec.decodeToText(body)
            require(decoded.contains('@')) { "Некорректная ss:// ссылка" }
            val credentials = decoded.substringBeforeLast('@')
            val authority = decoded.substringAfterLast('@')
            val colon = credentials.indexOf(':')
            require(colon > 0) { "Некорректная ss:// ссылка" }
            method = credentials.substring(0, colon)
            password = credentials.substring(colon + 1)
            val parsed = splitHostPort(authority, 8388)
            host = parsed.first
            port = parsed.second
        }

        require(method.isNotBlank() && password.isNotEmpty()) { "Не указан пароль Shadowsocks" }
        require(host.isNotBlank()) { "Не указан сервер Shadowsocks" }

        val outbound = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)

        parseQuery(rawQuery)["plugin"]?.takeIf { it.isNotBlank() }?.let { pluginValue ->
            val pluginName = pluginValue.substringBefore(';').trim()
            val options = pluginValue.substringAfter(';', "").trim()
            require(pluginName == "obfs-local" || pluginName == "simple-obfs" || pluginName == "v2ray-plugin") {
                "Плагин Shadowsocks «$pluginName» не поддерживается"
            }
            outbound.put("plugin", if (pluginName == "simple-obfs") "obfs-local" else pluginName)
            if (options.isNotEmpty()) outbound.put("plugin_opts", options)
        }

        val name = fragment.ifBlank { "Shadowsocks · $host" }
        return ParsedProfile(cleanName(name), VpnProtocol.SHADOWSOCKS, host, port, outbound, false)
    }

    private fun decodeSsUserInfo(rawUserInfo: String): String {
        if (rawUserInfo.contains(':')) return decodeComponent(rawUserInfo)
        return try {
            val decoded = Base64Codec.decodeToText(rawUserInfo)
            if (decoded.contains(':') && decoded.all { it.code in 32..126 }) decoded
            else decodeComponent(rawUserInfo)
        } catch (_: Exception) {
            decodeComponent(rawUserInfo)
        }
    }

    private fun parseWireGuardUrl(raw: String): ParsedProfile {
        val body = raw.substringAfter("://", "")
        val fragment = if (body.contains('#')) decodeComponent(body.substringAfter('#')) else ""
        val withoutFragment = body.substringBefore('#')

        // Some tools encode a complete WireGuard config in Base64 after wg://
        val maybeBase64 = withoutFragment.substringBefore('?')
        if (!withoutFragment.contains('?') && maybeBase64.length > 40) {
            runCatching { Base64Codec.decodeToText(maybeBase64) }
                .getOrNull()
                ?.takeIf { it.contains("[Interface]", ignoreCase = true) }
                ?.let { return parseWireGuardConf(it, fragment) }
        }

        val rawQuery = withoutFragment.substringAfter('?', "")
        val query = parseQuery(rawQuery).mapKeys { it.key.lowercase().replace("_", "") }
        val authority = withoutFragment.substringBefore('?')
        val (authorityHost, authorityPort) = if (authority.isNotBlank() && !authority.contains('@')) {
            splitHostPort(authority, 51820)
        } else {
            "" to 51820
        }

        val privateKey = query["privatekey"]
            ?: runCatching { decodeComponent(withoutFragment.substringBefore('@')) }.getOrNull()
            ?: error("WireGuard: не указан private key")
        val publicKey = query["publickey"] ?: error("WireGuard: не указан public key")
        val addresses = query["address"] ?: query["ip"] ?: error("WireGuard: не указан address")
        val host = query["host"]?.ifBlank { null } ?: authorityHost
        require(host.isNotBlank()) { "WireGuard: не указан адрес сервера" }
        val port = query["port"]?.toIntOrNull() ?: authorityPort
        val conf = "[Interface]\nPrivateKey = $privateKey\nAddress = $addresses\n" +
            (query["mtu"]?.let { "MTU = $it\n" } ?: "") +
            "[Peer]\nPublicKey = $publicKey\n" +
            (query["presharedkey"]?.let { "PresharedKey = $it\n" } ?: "") +
            "Endpoint = $host:$port\nAllowedIPs = ${query["allowedips"] ?: "0.0.0.0/0, ::/0"}\n" +
            (query["keepalive"]?.let { "PersistentKeepalive = $it\n" } ?: "")
        return parseWireGuardConf(conf, fragment)
    }

    private fun parseWireGuardConf(raw: String, explicitName: String = ""): ParsedProfile {
        var section = ""
        val interfaceValues = linkedMapOf<String, String>()
        val peers = mutableListOf<LinkedHashMap<String, String>>()
        var currentPeer: LinkedHashMap<String, String>? = null
        var commentName = ""

        raw.lineSequence().forEach { originalLine ->
            val line = originalLine.trim()
            if (line.startsWith("#") || line.startsWith(";")) {
                val comment = line.trimStart('#', ';', ' ').trim()
                if (commentName.isBlank() && comment.startsWith("Name", ignoreCase = true) && comment.contains('=')) {
                    commentName = comment.substringAfter('=').trim()
                }
                return@forEach
            }
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[")) {
                section = line.trim('[', ']').trim().lowercase()
                if (section == "peer") {
                    currentPeer = linkedMapOf()
                    peers.add(currentPeer!!)
                }
                return@forEach
            }
            val equals = line.indexOf('=')
            if (equals <= 0) return@forEach
            val key = line.substring(0, equals).trim()
            val value = line.substring(equals + 1).trim()
            when (section) {
                "interface" -> interfaceValues[key.lowercase()] = value
                "peer" -> currentPeer?.put(key.lowercase(), value)
            }
        }

        val privateKey = interfaceValues["privatekey"].orEmpty()
        val addresses = interfaceValues["address"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeAddressPrefix(it) }
        require(privateKey.isNotBlank()) { "WireGuard: не указан PrivateKey" }
        require(addresses.isNotEmpty()) { "WireGuard: не указан Address" }
        require(peers.isNotEmpty()) { "WireGuard: в конфигурации нет [Peer]" }

        val peerArray = JSONArray()
        var firstHost = ""
        var firstPort = 51820
        peers.forEach { peer ->
            val publicKey = peer["publickey"].orEmpty()
            require(publicKey.isNotBlank()) { "WireGuard: у peer нет PublicKey" }
            val endpoint = peer["endpoint"].orEmpty()
            require(endpoint.isNotBlank()) { "WireGuard: у peer нет Endpoint" }
            val (host, port) = splitHostPort(endpoint, 51820)
            if (firstHost.isBlank()) {
                firstHost = host
                firstPort = port
            }
            val allowed = peer["allowedips"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("0.0.0.0/0", "::/0") }
            val peerJson = JSONObject()
                .put("address", host)
                .put("port", port)
                .put("public_key", publicKey)
                .put("allowed_ips", JSONArray(allowed))
            peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { peerJson.put("pre_shared_key", it) }
            peer["persistentkeepalive"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
                peerJson.put("persistent_keepalive_interval", it.coerceAtMost(3600))
            }
            peerArray.put(peerJson)
        }

        val mtu = interfaceValues["mtu"]?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1408
        val endpoint = JSONObject()
            .put("type", "wireguard")
            .put("tag", "proxy")
            .put("address", JSONArray(addresses))
            .put("private_key", privateKey)
            .put("mtu", mtu)
            .put("peers", peerArray)

        val name = explicitName.ifBlank { commentName }.ifBlank { "WireGuard · $firstHost" }
        return ParsedProfile(cleanName(name), VpnProtocol.WIREGUARD, firstHost, firstPort, endpoint, true)
    }

    private fun normalizeAddressPrefix(value: String): String {
        if (value.contains('/')) return value
        return if (value.contains(':')) "$value/128" else "$value/32"
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&')
            .filter { it.isNotBlank() }
            .associate { part ->
                val key = decodeComponent(part.substringBefore('='))
                val value = decodeComponent(part.substringAfter('=', ""))
                key to value
            }
    }

    private fun splitHostPort(authority: String, defaultPort: Int): Pair<String, Int> {
        val value = authority.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            require(close > 0) { "Некорректный IPv6-адрес" }
            val host = value.substring(1, close)
            val port = if (close + 1 < value.length && value[close + 1] == ':') {
                value.substring(close + 2).toIntOrNull() ?: defaultPort
            } else defaultPort
            require(port in 1..65535) { "Некорректный порт" }
            return host to port
        }
        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val host = value.substringBeforeLast(':')
            val port = value.substringAfterLast(':').toIntOrNull() ?: defaultPort
            require(host.isNotBlank()) { "Не указан хост" }
            require(port in 1..65535) { "Некорректный порт" }
            return host to port
        }
        if (colonCount > 1) {
            // Unbracketed IPv6, no explicit port.
            return value to defaultPort
        }
        require(value.isNotBlank()) { "Не указан хост" }
        return value to defaultPort
    }

    private fun decodeComponent(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }

    private fun cleanName(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(64).ifBlank { "Сервер" }
}
