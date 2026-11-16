package com.aegis.vpn.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            runCatching { VpnController.disconnect(context) }
                .onFailure { Log.w("NotificationAction", "disconnect: ${it.message}") }
        }
    }

    companion object {
        const val ACTION_STOP = "com.aegis.vpn.action.NOTIFICATION_STOP"
    }
}
