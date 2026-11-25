package com.aegis.vpn.core

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.aegis.vpn.AppGraph
import com.aegis.vpn.R
import com.aegis.vpn.data.VpnStatus
import com.aegis.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observeJob?.cancel()
        observeJob = scope.launch {
            VpnStateHolder.state.collectLatest { snapshot ->
                updateTile(snapshot.status, snapshot.serverName)
            }
        }
    }

    override fun onStopListening() {
        observeJob?.cancel()
        observeJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val status = VpnStateHolder.state.value.status
        when (status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING, VpnStatus.STOPPING -> {
                VpnController.disconnect(this)
            }
            VpnStatus.DISCONNECTED, VpnStatus.ERROR -> {
                val prepare = VpnService.prepare(this)
                val server = runCatching { AppGraph.store.selectedServer() }.getOrNull()
                if (server == null) {
                    openApp()
                    return
                }
                if (prepare != null) {
                    // VPN permission must be granted from an activity.
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(EXTRA_CONNECT_REQUEST, true)
                    }
                    startActivityAndCollapse(intentPending(intent))
                } else {
                    VpnController.connect(this)
                }
            }
        }
    }

    private fun intentPending(intent: Intent): android.app.PendingIntent {
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE
        return android.app.PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivityAndCollapse(intentPending(intent))
    }

    private fun updateTile(status: VpnStatus, serverName: String?) {
        val tile = qsTile ?: return
        when (status) {
            VpnStatus.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitleCompat(serverName ?: getString(R.string.tile_connected))
            }
            VpnStatus.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitleCompat(getString(R.string.tile_connecting))
            }
            VpnStatus.STOPPING -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitleCompat(getString(R.string.tile_disconnecting))
            }
            VpnStatus.DISCONNECTED, VpnStatus.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                tile.subtitleCompat(getString(R.string.tile_disconnected))
            }
        }
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_shield)
        tile.updateTile()
    }

    private fun Tile.subtitleCompat(value: CharSequence) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            subtitle = value
        }
    }

    companion object {
        const val EXTRA_CONNECT_REQUEST = "connect_request"
    }
}
