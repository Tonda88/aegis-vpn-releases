package com.aegis.vpn.data

import java.util.UUID

enum class VpnProtocol(val label: String, val shortLabel: String) {
    VLESS("VLESS", "VLESS"),
    SHADOWSOCKS("Shadowsocks", "SS"),
    WIREGUARD("WireGuard", "WG");

    companion object {
        fun fromStorage(value: String): VpnProtocol =
            entries.firstOrNull { it.name == value } ?: VLESS
    }
}

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val raw: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val endpointLabel: String
        get() = if (port > 0) "$host:$port" else host
}

data class ParsedProfile(
    val name: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val outbound: org.json.JSONObject,
    val isEndpoint: Boolean
)

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class VpnSnapshot(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val serverName: String? = null,
    val error: String? = null,
    val connectedSince: Long = 0L
)
