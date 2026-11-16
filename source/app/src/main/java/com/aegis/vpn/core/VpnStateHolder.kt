package com.aegis.vpn.core

import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnSnapshot
import com.aegis.vpn.data.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnStateHolder {
    private val _state = MutableStateFlow(VpnSnapshot())
    val state: StateFlow<VpnSnapshot> = _state.asStateFlow()

    fun connecting(server: ServerProfile) {
        _state.value = VpnSnapshot(
            status = VpnStatus.CONNECTING,
            serverName = server.name,
            serverId = server.id
        )
    }

    fun connected(server: ServerProfile) {
        _state.value = VpnSnapshot(
            status = VpnStatus.CONNECTED,
            serverName = server.name,
            serverId = server.id,
            connectedSince = System.currentTimeMillis()
        )
    }

    fun stopping() {
        val current = _state.value
        _state.value = current.copy(status = VpnStatus.STOPPING)
    }

    fun disconnected() {
        _state.value = VpnSnapshot(status = VpnStatus.DISCONNECTED)
    }

    fun error(message: String, server: ServerProfile? = null) {
        _state.value = VpnSnapshot(
            status = VpnStatus.ERROR,
            serverName = server?.name,
            serverId = server?.id,
            error = message.take(500)
        )
    }

    fun traffic(rx: Long, tx: Long) {
        val current = _state.value
        if (current.status == VpnStatus.CONNECTED || current.status == VpnStatus.CONNECTING) {
            _state.value = current.copy(rxBytes = rx.coerceAtLeast(0), txBytes = tx.coerceAtLeast(0))
        }
    }
}
