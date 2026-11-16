package com.aegis.vpn.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.aegis.vpn.AppGraph

object VpnController {
    const val ACTION_CONNECT = "com.aegis.vpn.action.CONNECT"
    const val ACTION_DISCONNECT = "com.aegis.vpn.action.DISCONNECT"
    const val EXTRA_SERVER_ID = "server_id"

    fun permissionIntent(context: Context): Intent? = VpnService.prepare(context)

    fun connect(context: Context, serverId: String? = null) {
        val intent = Intent(context, AegisVpnService::class.java).apply {
            action = ACTION_CONNECT
            serverId?.let { putExtra(EXTRA_SERVER_ID, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, AegisVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

/**
 * Starts and stops the background watcher that switches the VPN according to
 * the per-application rules. The watcher only runs while at least one rule is
 * configured, so it costs nothing when the feature is not used.
 */
object AutoConnectController {

    fun sync(context: Context) {
        val settings = runCatching { AppGraph.store.settings.value }.getOrNull()
        val hasRules = settings?.autoApps?.isNotEmpty() == true
        if (hasRules) {
            start(context)
        } else {
            stop(context)
        }
    }

    fun start(context: Context) {
        val intent = Intent(context, AppWatchService::class.java)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { /* the watcher is optional, ignore failures */ }
    }

    fun stop(context: Context) {
        val intent = Intent(context, AppWatchService::class.java).apply {
            action = AppWatchService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
            .onFailure { /* ignore */ }
    }
}
