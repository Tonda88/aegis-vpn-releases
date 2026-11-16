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

data class AppSettings(
    val autoStart: Boolean = true,
    val autoReconnect: Boolean = true,
    val includeMode: Boolean = false,
    val selectedPackages: Set<String> = emptySet(),
    /** Packages that raise the VPN while they are in the foreground. */
    val autoApps: Set<String> = emptySet(),
    /** Remembered mode of the Applications tab. */
    val appListMode: AppListMode = AppListMode.TUNNEL,
    val accentTheme: AccentTheme = AccentTheme.MINT
)

enum class AppListMode {
    TUNNEL,
    AUTO
}

enum class AccentTheme(val label: String) {
    MINT("РњСЏС‚РЅР°СЏ"),
    BLUE("РЎРёРЅСЏСЏ"),
    VIOLET("Р¤РёРѕР»РµС‚РѕРІР°СЏ"),
    ORANGE("РћСЂР°РЅР¶РµРІР°СЏ"),
    PINK("Р РѕР·РѕРІР°СЏ"),
    CYAN("Р‘РёСЂСЋР·РѕРІР°СЏ")
}

data class ParsedProfile(
    val name: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val outbound: org.json.JSONObject,
    val isEndpoint: Boolean
)

data class VpnSnapshot(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val serverName: String? = null,
    val serverId: String? = null,
    val error: String? = null,
    val connectedSince: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
)

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STOPPING,
    ERROR
}

