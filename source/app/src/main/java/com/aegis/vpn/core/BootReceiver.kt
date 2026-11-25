package com.aegis.vpn.core

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.aegis.vpn.AppGraph

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        runCatching {
            AppGraph.init(context.applicationContext as Application)
            val settings = AppGraph.store.settings.value
            val server = AppGraph.store.selectedServer()

            if (settings.autoStart && server != null && VpnService.prepare(context) == null) {
                VpnController.connect(context)
            } else if (server == null) {
                Log.i("BootReceiver", "no server selected, skip autostart")
            } else {
                Log.i("BootReceiver", "VPN permission is not granted, skip autostart")
            }

            // The per-application watcher has to survive a reboot as well.
            if (settings.autoApps.isNotEmpty()) {
                AutoConnectController.start(context.applicationContext)
            }
        }.onFailure {
            Log.w("BootReceiver", "autostart failed: ${it.message}")
        }
    }
}
