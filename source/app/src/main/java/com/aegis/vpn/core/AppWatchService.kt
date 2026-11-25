package com.aegis.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aegis.vpn.AppGraph
import com.aegis.vpn.R
import com.aegis.vpn.data.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches which application is on the screen and switches the VPN according to
 * the per-application rules configured on the "Приложения" tab:
 *
 *  - the selected application comes to the foreground -> the VPN is raised;
 *  - the user leaves it -> the VPN is lowered again.
 *
 * The watcher runs as a low priority foreground service so that it keeps
 * working while the Aegis application itself is closed.
 */
class AppWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tracker: ForegroundAppTracker
    private var watchJob: Job? = null
    private var stateJob: Job? = null
    private var disconnectJob: Job? = null

    /** The VPN was raised by a rule and may be lowered automatically. */
    private var autoConnected = false

    /** The user pressed "disconnect" by hand, do not fight them. */
    private var suppressed = false

    /** The last known foreground package that is not a transient overlay. */
    private var foreground: String? = null

    /** Whether the foreground package currently carries an auto rule. */
    private var active = false

    private var retryAt = 0L

    override fun onCreate() {
        super.onCreate()
        tracker = ForegroundAppTracker(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val hasRules = runCatching { AppGraph.store.settings.value.autoApps.isNotEmpty() }
            .getOrDefault(false)
        if (!hasRules) {
            // Nothing to watch, do not restart in a loop.
            stopSelf()
            return START_NOT_STICKY
        }

        showForegroundNotification()

        if (!UsageAccess.isGranted(this)) {
            Log.w(TAG, "usage access is not granted, the watcher cannot detect the foreground app")
        }

        if (watchJob == null) {
            watchJob = scope.launch {
                while (isActive) {
                    tick()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        if (stateJob == null) {
            stateJob = scope.launch {
                VpnStateHolder.state.collectLatest { snapshot ->
                    when (snapshot.status) {
                        // The connection may have been raised by hand, so the
                        // flag is never set here: only the watcher itself
                        // marks a connection as managed.
                        VpnStatus.DISCONNECTED -> {
                            if (autoConnected) {
                                // The tunnel went down on its own or the user
                                // stopped it by hand. Do not raise it again
                                // until the application is entered anew.
                                autoConnected = false
                                suppressed = true
                            }
                            updateWatchNotification(error = false)
                        }
                        VpnStatus.ERROR -> {
                            autoConnected = false
                            if (active) retryAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
                            updateWatchNotification(error = true)
                        }
                        VpnStatus.CONNECTED -> updateWatchNotification(error = false)
                        else -> Unit
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchJob?.cancel()
        stateJob?.cancel()
        disconnectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun tick() {
        val settings = runCatching { AppGraph.store.settings.value }.getOrNull() ?: return
        val autoApps = settings.autoApps
        if (autoApps.isEmpty()) {
            // Nothing to watch any more.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val foregroundPackage = tracker.refresh() ?: return
        val meaningful = !UsageAccess.isTransient(foregroundPackage, packageName)

        if (meaningful && foregroundPackage != foreground) {
            foreground = foregroundPackage
            val nowAuto = foregroundPackage in autoApps
            Log.d(TAG, "foreground=$foregroundPackage auto=$nowAuto")
            if (nowAuto != active) {
                active = nowAuto
                if (nowAuto) {
                    // Cancel any pending disconnect when the user returns.
                    disconnectJob?.cancel()
                    disconnectJob = null
                    if (!suppressed) requestConnect()
                } else {
                    if (!isScreenOn()) {
                        // The screen was turned off; respect the user setting.
                        val keepOnScreenOff = runCatching {
                            AppGraph.store.settings.value.keepVpnOnScreenOff
                        }.getOrDefault(true)
                        if (keepOnScreenOff) {
                            Log.d(TAG, "the screen is off, keeping the tunnel (keepVpnOnScreenOff=true)")
                            return
                        }
                    }
                    // Grace period: wait before disconnecting in case the user
                    // switches back quickly (e.g. checks another app briefly).
                    disconnectJob?.cancel()
                    disconnectJob = scope.launch {
                        delay(DISCONNECT_GRACE_MS)
                        if (!active) {
                            suppressed = false
                            requestDisconnect()
                        }
                    }
                }
            }
        }

        // Retry a failed connection while the application stays in the foreground.
        if (active && !autoConnected && !suppressed &&
            SystemClock.elapsedRealtime() >= retryAt
        ) {
            requestConnect()
        }
    }

    private fun requestConnect() {
        if (autoConnected) return
        if (VpnStateHolder.state.value.status == VpnStatus.CONNECTED) {
            // Somebody else raised the tunnel, the rule does not take it over.
            return
        }
        if (VpnService.prepare(this) != null) {
            Log.i(TAG, "VPN permission is missing, cannot connect automatically")
            return
        }
        val server = runCatching { AppGraph.store.selectedServer() }.getOrNull()
        if (server == null) {
            Log.i(TAG, "no server selected, nothing to connect")
            return
        }
        Log.i(TAG, "auto-connecting for ${server.name}")
        autoConnected = true
        suppressed = false
        retryAt = 0L
        VpnController.connect(this)
    }

    private fun requestDisconnect() {
        if (!autoConnected) return
        autoConnected = false
        Log.i(TAG, "auto-disconnecting, the foreground app left the rule")
        VpnController.disconnect(this)
    }

    private fun isScreenOn(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { power.isInteractive }.getOrDefault(true)
    }

    // ---------- Notifications ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_watch_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.channel_watch_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification() {
        val notification: Notification = buildWatchNotification(error = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateWatchNotification(error: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildWatchNotification(error))
    }

    private fun buildWatchNotification(error: Boolean): Notification {
        val title = getString(R.string.channel_watch_name)
        val text = if (error) {
            "Ошибка подключения, повтор через ${RETRY_DELAY_MS / 1000} с"
        } else {
            getString(R.string.channel_watch_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "AppWatchService"
        private const val CHANNEL_ID = "vpn_auto_watch"
        private const val NOTIFICATION_ID = 102
        private const val POLL_INTERVAL_MS = 300L       // было 1000L — быстрее замечаем смену приложения
        private const val RETRY_DELAY_MS = 5_000L       // было 15_000L — быстрее повторяем при ошибке
        private const val DISCONNECT_GRACE_MS = 1_500L  // новый — не отключаем сразу при быстром переключении
        const val ACTION_STOP = "com.aegis.vpn.action.STOP_WATCH"
    }
}
