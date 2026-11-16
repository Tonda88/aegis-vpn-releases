package com.aegis.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aegis.vpn.AppGraph
import com.aegis.vpn.BuildConfig
import com.aegis.vpn.R
import com.aegis.vpn.config.ConfigParser
import com.aegis.vpn.config.SingBoxConfig
import com.aegis.vpn.data.ServerProfile
import com.aegis.vpn.data.VpnStatus
import com.aegis.vpn.ui.MainActivity
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AegisVpnService :
    VpnService(),
    PlatformInterface,
    CommandServerHandler {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val stopping = AtomicBoolean(false)
    private var connectJob: Job? = null
    private var trafficJob: Job? = null
    private var commandServer: CommandServer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private lateinit var networkMonitor: UpstreamNetworkMonitor

    override fun onCreate() {
        super.onCreate()
        networkMonitor = UpstreamNetworkMonitor()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == VpnController.ACTION_DISCONNECT) {
            showForegroundNotification("Отключение", "Завершаем соединение…")
            serviceScope.launch { stopVpnInternal() }
            return START_NOT_STICKY
        }

        showForegroundNotification("Подключение", "Устанавливаем защищённое соединение…")

        val requestedId = intent?.getStringExtra(VpnController.EXTRA_SERVER_ID)
        val server = requestedId?.let { id -> AppGraph.store.servers.value.firstOrNull { it.id == id } }
            ?: AppGraph.store.selectedServer()

        if (server == null) {
            VpnStateHolder.error("Сначала добавьте сервер")
            notifyErrorAndStop("Нет сервера")
            return START_NOT_STICKY
        }

        if (stopping.get()) return START_NOT_STICKY

        VpnStateHolder.connecting(server)
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            connectMutex.withLock {
                connectInternal(server)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        serviceScope.launch {
            stopVpnInternal()
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        trafficJob?.cancel()
        connectJob?.cancel()
        networkMonitor.stop()
        closeNative()
        if (VpnStateHolder.state.value.status != VpnStatus.ERROR) {
            VpnStateHolder.disconnected()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun connectInternal(server: ServerProfile) {
        var failure: Throwable? = null
        try {
            if (stopping.get()) return
            ensureLibboxSetup()
            if (prepare(this) != null) {
                error("Разрешите VPN-подключение в системном диалоге")
            }

            val parsed = ConfigParser.parse(server.raw).getOrElse { throw it }
            val config = SingBoxConfig.build(parsed)
            Libbox.checkConfig(config)

            var serverHandle = commandServer
            if (serverHandle == null) {
                serverHandle = CommandServer(this, this)
                serverHandle.start()
                commandServer = serverHandle
            }

            val overrideOptions = OverrideOptions().apply {
                autoRedirect = false
            }
            serverHandle.startOrReloadService(config, overrideOptions)

            trafficJob?.cancel()
            VpnStateHolder.connected(server)
            startTrafficTicker(server)
            updateForegroundNotification("Защищено", server.name)
            Log.i(TAG, "Connected to ${server.name} (${server.endpointLabel})")
        } catch (throwable: Throwable) {
            failure = throwable
            Log.e(TAG, "Connection failed", throwable)
        }

        if (failure != null) {
            val message = friendlyError(failure)
            closeNative()
            trafficJob?.cancel()
            VpnStateHolder.error(message, server)
            updateForegroundNotification("Ошибка подключения", message)
            delay(300)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startTrafficTicker(server: ServerProfile) {
        val startRx = TrafficCounter.totalRx()
        val startTx = TrafficCounter.totalTx()
        trafficJob = serviceScope.launch {
            while (isActive && VpnStateHolder.state.value.status == VpnStatus.CONNECTED) {
                delay(1000)
                VpnStateHolder.traffic(
                    TrafficCounter.totalRx() - startRx,
                    TrafficCounter.totalTx() - startTx
                )
            }
        }
    }

    private fun stopVpnInternal() {
        if (stopping.getAndSet(true)) return
        VpnStateHolder.stopping()
        trafficJob?.cancel()
        networkMonitor.stop()
        closeNative()
        VpnStateHolder.disconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeNative() {
        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "closeService: ${it.message}") }
        commandServer = null
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
    }

    private fun friendlyError(throwable: Throwable): String {
        val raw = throwable.message?.trim().orEmpty()
        if (raw.isBlank()) return "Не удалось подключиться. Проверьте ключ и сеть."
        return raw
            .replace("io.nekohasekai.libbox.LibboxException: ", "")
            .lineSequence()
            .take(4)
            .joinToString(" ")
            .take(350)
    }

    private fun notifyErrorAndStop(text: String) {
        updateForegroundNotification("Ошибка", text)
        serviceScope.launch {
            delay(400)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureLibboxSetup() {
        LibboxRuntime.ensureSetup(this)
    }

    // ---------- VpnService / PlatformInterface ----------

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder = Builder()
            .setSession("Aegis VPN")
            .setMtu(options.mtu.coerceIn(1280, 9000))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        var inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }
        var inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val prefix = inet6.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                var dns = options.dnsServerAddress
                while (dns.hasNext()) {
                    builder.addDnsServer(dns.next())
                }
            }

            var hasV4Route = false
            var v4Routes: RoutePrefixIterator = options.inet4RouteAddress
            while (v4Routes.hasNext()) {
                val prefix = v4Routes.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasV4Route = true
            }
            if (!hasV4Route) builder.addRoute("0.0.0.0", 0)

            var hasV6Route = false
            var v6Routes: RoutePrefixIterator = options.inet6RouteAddress
            while (v6Routes.hasNext()) {
                val prefix = v6Routes.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasV6Route = true
            }
            if (!hasV6Route) builder.addRoute("::", 0)

            applySplitTunneling(builder)
        }

        val descriptor = builder.establish()
            ?: error("Система не разрешила создать VPN. Проверьте разрешение VPN.")
        runCatching { fileDescriptor?.close() }
        fileDescriptor = descriptor
        return descriptor.fd
    }

    private fun applySplitTunneling(builder: Builder) {
        val settings = AppGraph.store.settings.value
        val selected = settings.selectedPackages
        if (settings.includeMode) {
            if (selected.isEmpty()) return
            selected.forEach { packageName ->
                runCatching { builder.addAllowedApplication(packageName) }
                    .onFailure { Log.d(TAG, "addAllowedApplication $packageName: ${it.message}") }
            }
        } else {
            selected.forEach { packageName ->
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.d(TAG, "addDisallowedApplication $packageName: ${it.message}") }
            }
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.start(listener) {
            if (AppGraph.store.settings.value.autoReconnect) {
                runCatching { commandServer?.resetNetwork() }
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.stop()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
        for (networkInterface in all) {
            val item = io.nekohasekai.libbox.NetworkInterface()
            item.name = networkInterface.name
            item.index = runCatching { networkInterface.index }.getOrDefault(0)
            item.mtu = runCatching { networkInterface.mtu }.getOrDefault(1500)
            item.addresses = StringListIterator(
                runCatching {
                    networkInterface.interfaceAddresses.map { address ->
                        val ip = address.address
                        if (ip is Inet6Address) {
                            "${Inet6Address.getByAddress(ip.address).hostAddress}/${address.networkPrefixLength}"
                        } else {
                            "${ip.hostAddress}/${address.networkPrefixLength}"
                        }
                    }
                }.getOrDefault(emptyList())
            )
            var flags = 0
            if (runCatching { networkInterface.isUp }.getOrDefault(false)) {
                flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) {
                flags = flags or OsConstants.IFF_LOOPBACK
            }
            if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) {
                flags = flags or OsConstants.IFF_POINTOPOINT
            }
            if (runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)) {
                flags = flags or OsConstants.IFF_MULTICAST
            }
            item.flags = flags
            item.type = when {
                networkInterface.name?.startsWith("wlan") == true -> Libbox.InterfaceTypeWIFI
                networkInterface.name?.startsWith("rmnet") == true ||
                    networkInterface.name?.startsWith("ccmni") == true ->
                    Libbox.InterfaceTypeCellular
                networkInterface.name?.startsWith("eth") == true -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.dnsServer = StringListIterator(emptyList())
            item.gateway = StringListIterator(emptyList())
            item.metered = true
            interfaces.add(item)
        }
        return NetworkInterfaceArray(interfaces.iterator())
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
            if (uid == Process.INVALID_UID) error("connection owner not found")
            ConnectionOwner().also { it.userId = uid }
        } else {
            Libbox.findConnectionOwner(
                ipProtocol,
                sourceAddress,
                sourcePort,
                destinationAddress,
                destinationPort
            )
        }
        val packages = packageManager.getPackagesForUid(owner.userId)
        owner.userName = packages?.firstOrNull().orEmpty()
        owner.setAndroidPackageNames(StringListIterator(packages?.toList().orEmpty()))
        return owner
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("shell is not supported")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = error("shell is not supported")

    override fun readSystemSSHHostKey(): String = error("ssh is not supported")

    override fun lookupSFTPServer(): String = error("sftp is not supported")

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        error("bridge is not supported")

    override fun lookupUser(username: String?): PlatformUser = error("users are not supported")

    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: LibboxNotification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    // ---------- CommandServerHandler ----------

    override fun serviceStop() {
        serviceScope.launch { stopVpnInternal() }
    }

    override fun serviceReload() {
        val server = AppGraph.store.selectedServer()
        if (server == null) {
            serviceScope.launch { stopVpnInternal() }
            return
        }
        serviceScope.launch {
            connectMutex.withLock {
                VpnStateHolder.connecting(server)
                connectInternal(server)
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) Log.d("sing-box", message)
    }

    override fun connectSSHAgent(): Int = -1

    // ---------- Notifications ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_vpn_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_vpn_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Aegis VPN · $title")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_power, "Отключить", stopIntent)
            .build()
    }

    private fun showForegroundNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
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

    private fun updateForegroundNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(title, text.take(160)))
    }

    // ---------- Inner helpers ----------

    private inner class UpstreamNetworkMonitor {
        private val connectivity = getSystemService(ConnectivityManager::class.java)
        private var listener: InterfaceUpdateListener? = null
        private var registered = false
        private var currentNetwork: Network? = null
        private var onAvailableCallback: (() -> Unit)? = null

        private val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isUsable(network)) return
                currentNetwork = network
                publish(network)
                onAvailableCallback?.invoke()
            }

            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    currentNetwork = null
                    listener?.updateDefaultInterface("", -1, false, false)
                    refresh()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (currentNetwork == network && isUsable(network)) {
                    publish(network)
                } else if (currentNetwork == null) {
                    refresh()
                }
            }
        }

        fun start(newListener: InterfaceUpdateListener, onAvailable: () -> Unit) {
            stop()
            listener = newListener
            onAvailableCallback = onAvailable
            registered = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    connectivity.registerBestMatchingNetworkCallback(
                        networkRequest,
                        callback,
                        Handler(Looper.getMainLooper())
                    )
                } else {
                    connectivity.requestNetwork(networkRequest, callback)
                }
            } catch (throwable: Throwable) {
                Log.w(TAG, "network monitor: ${throwable.message}")
                registered = false
            }
            refresh()
        }

        fun stop() {
            listener = null
            onAvailableCallback = null
            currentNetwork = null
            if (registered) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
                registered = false
            }
        }

        fun refresh() {
            val network = pickNetwork()
            if (network != null) {
                currentNetwork = network
                publish(network)
            }
        }

        private fun pickNetwork(): Network? {
            val active = connectivity.activeNetwork
            if (active != null && isUsable(active)) return active
            return connectivity.allNetworks.firstOrNull { isUsable(it) }
        }

        private fun isUsable(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        private fun publish(network: Network) {
            val target = listener ?: return
            var name = ""
            var index = -1
            for (attempt in 0 until 6) {
                val linkProperties = connectivity.getLinkProperties(network)
                val interfaceName = linkProperties?.interfaceName
                if (!interfaceName.isNullOrBlank()) {
                    val ni = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull()
                    if (ni != null) {
                        name = interfaceName
                        index = runCatching { ni.index }.getOrDefault(-1)
                        break
                    }
                }
                Thread.sleep(80)
            }
            target.updateDefaultInterface(name, index, false, false)
        }
    }

    private class StringListIterator(private val values: List<String>) : StringIterator {
        private val iterator = values.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
        override fun len(): Int = values.size
    }

    private class NetworkInterfaceArray(
        private val iterator: Iterator<io.nekohasekai.libbox.NetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }

    private companion object {
        const val TAG = "AegisVpnService"
        const val CHANNEL_ID = "vpn_connection"
        const val NOTIFICATION_ID = 101
    }
}

object TrafficCounter {
    fun totalRx(): Long = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0)
    fun totalTx(): Long = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0)
}

object LibboxRuntime {
    private val lock = Any()
    @Volatile
    private var initialized = false

    fun ensureSetup(service: Service) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            service.filesDir.mkdirs()
            service.cacheDir.mkdirs()
            val options = SetupOptions().apply {
                basePath = service.filesDir.absolutePath
                workingPath = service.filesDir.absolutePath
                tempPath = service.cacheDir.absolutePath
                logMaxLines = 2000
                debug = BuildConfig.DEBUG
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
                oomKillerEnabled = false
                powerReportEnabled = false
                platformMetadata = JSONObject()
                    .put("os", "Android ${Build.VERSION.RELEASE}")
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .toString()
            }
            Libbox.setup(options)
            runCatching {
                Libbox.setLocale(Locale.getDefault().toLanguageTag())
            }
            initialized = true
        }
    }
}
