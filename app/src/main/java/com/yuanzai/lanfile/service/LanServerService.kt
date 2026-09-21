package com.yuanzai.lanfile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuanzai.lanfile.core.NetworkUtils
import com.yuanzai.lanfile.core.NotificationHelper
import com.yuanzai.lanfile.core.SettingsStore
import com.yuanzai.lanfile.core.StorageManager
import com.yuanzai.lanfile.model.ServerStatus
import com.yuanzai.lanfile.server.LanHttpServer
import com.yuanzai.lanfile.server.LanServerHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 承载局域网 HTTP 服务的前台服务。
 *
 *  - 打开 App 即启动（[ACTION_START]）
 *  - 使用前台服务 + 通知，切到后台 / 锁屏后继续运行
 *  - Wi-Fi 断开、IP 变化时自动刷新状态与通知
 *  - 用户点击通知里的“停止服务”或 App 内停止按钮时终止服务
 */
class LanServerService : Service() {

    private lateinit var settings: SettingsStore

    /** 服务实例。会被网络回调线程读写，因此加锁 + volatile。 */
    @Volatile
    private var server: LanHttpServer? = null
    private val serverLock = Any()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lanNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    @Volatile
    private var foregroundMode = true

    @Volatile
    private var foregroundStarted = false

    @Volatile
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        NotificationHelper.createChannels(this)
        StorageManager.refreshRoot()
        acquireWifiLock()
        acquireCpuLock()
        registerNetworkCallback()
    }

    /**
     * 持有高性能 WifiLock。
     *
     * 这是局域网服务能稳定收发的关键：不持锁时，Wi-Fi 固件会进入省电模式，
     * 典型症状就是「TCP 能连上（握手包很小、能穿过省电窗口），但之后的数据
     * 长时间收不到」，也就是对方一直转圈 / 0 字节。
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        val lock = runCatching {
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanfile:server")
        }.getOrNull() ?: return
        runCatching { lock.acquire() }
        wifiLock = lock
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
        wifiLock = null
    }

    /**
     * 持有 CPU 唤醒锁：作为服务器，锁屏后也要能随时应答，
     * 不能让系统把 CPU 挂起导致连接停在那里。
     */
    private fun acquireCpuLock() {
        if (cpuLock?.isHeld == true) return
        val manager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanfile:cpu")
        }.getOrNull() ?: return
        runCatching { lock.acquire() }
        cpuLock = lock
    }

    private fun releaseCpuLock() {
        val lock = cpuLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
        cpuLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val explicitForeground = intent?.hasExtra(EXTRA_FOREGROUND) == true
        val foreground = if (explicitForeground) {
            intent!!.getBooleanExtra(EXTRA_FOREGROUND, settings.backgroundRun)
        } else {
            settings.backgroundRun
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopServer()
                startServer(foreground)
            }
            else -> startServer(foreground)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        unregisterNetworkCallback()
        releaseWifiLock()
        releaseCpuLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------- 服务控制

    private fun startServer(foreground: Boolean) = synchronized(serverLock) {
        foregroundMode = foreground
        if (foreground) {
            promoteToForeground(buildStatus(running = server?.isRunning == true))
        }
        val current = server
        if (current != null && current.isRunning) {
            refreshStatus()
            return@synchronized
        }
        if (!startServerInstance() && !foreground) stopSelf()
    }

    /** 创建并启动服务实例；成功返回 true。调用方负责前台状态。 */
    private fun startServerInstance(): Boolean = synchronized(serverLock) {
        val port = settings.port
        val instance = LanHttpServer(applicationContext, port)
        try {
            val boundPort = instance.start()
            server = instance
            LanServerHolder.server = instance
            lastError = null
            val status = buildStatus(running = true).copy(
                port = boundPort,
                error = if (boundPort != port) "端口 $port 被占用，已改用 $boundPort" else null
            )
            _status.value = status
            updateNotification(status)
            true
        } catch (e: Exception) {
            server = null
            LanServerHolder.server = null
            val message = e.message ?: e.javaClass.simpleName
            lastError = "服务启动失败：$message"
            _status.value = buildStatus(running = false).copy(error = lastError)
            NotificationHelper.notifyError(this, lastError ?: "服务启动失败")
            false
        }
    }

    /**
     * 局域网网络变化后重建监听 socket（网络没变时不需要重建）。
     *
     * 这里刻意不调用 [stopServer]：[stopServer] 会撤掉前台通知，
     * 而在后台重新 startForeground 可能被 Android 12+ 的后台启动限制拦下。
     * 保持前台状态不变，只换掉监听 socket。
     */
    private fun rebindServerSocket() = synchronized(serverLock) {
        server?.stop()
        server = null
        LanServerHolder.server = null
        startServerInstance()
    }

    private fun stopServer() = synchronized(serverLock) {
        server?.stop()
        server = null
        LanServerHolder.server = null
        _status.value = buildStatus(running = false).copy(error = null)
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    private fun promoteToForeground(status: ServerStatus) {
        val notification = NotificationHelper.buildServiceNotification(this, status)
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                foregroundServiceType()
            )
            foregroundStarted = true
        } catch (e: Exception) {
            // 前台服务被系统拒绝（少见）：退化为普通后台服务
            foregroundStarted = false
        }
    }

    private fun foregroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    private fun updateNotification(status: ServerStatus) {
        if (!foregroundStarted) return
        if (!settings.showNotification && !foregroundMode) return
        val notification = NotificationHelper.buildServiceNotification(this, status)
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------- 状态

    private fun buildStatus(running: Boolean): ServerStatus {
        val ip = NetworkUtils.primaryIpv4()
        return ServerStatus(
            running = running,
            port = server?.boundPort ?: settings.port,
            ip = ip,
            rootPath = StorageManager.root().absolutePath,
            usingPublicDir = StorageManager.isUsingPublicDir(),
            startedAt = startedAt,
            servedRequests = server?.servedRequests ?: 0,
            error = lastError,
            networkAvailable = NetworkUtils.hasLanConnection(this)
        )
    }

    private var startedAt: Long = 0L

    /** 重新读取 IP / 请求数并推送到 UI 与通知。 */
    fun refreshStatus() {
        val current = server
        if (current != null && current.isRunning && startedAt == 0L) {
            startedAt = System.currentTimeMillis()
        }
        val status = buildStatus(running = current?.isRunning == true)
        _status.value = status
        updateNotification(status)
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = manager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshStatus()

            override fun onLost(network: Network) = refreshStatus()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                refreshStatus()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refreshStatus()
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }

        // 额外单独监听 Wi-Fi / 以太网：
        // 当默认网络是移动数据时（例如手机连了没有外网的路由器），
        // registerDefaultNetworkCallback 不会因 Wi-Fi 的连接/断开而回调，
        // 服务端就可能在网络切换后仍然绑定在旧网络上。
        val lanCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                NetworkUtils.noteLanNetwork(network, true)
                // 重新关联 Wi-Fi / 换网后 Network 对象会换成新的，
                // 监听 socket 上残留的旧网络绑定必须换掉，否则回包会走错出口。
                rebindServerSocket()
            }

            override fun onLost(network: Network) {
                NetworkUtils.noteLanNetwork(network, false)
                onLanChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                NetworkUtils.noteLanNetwork(network, true)
                onLanChanged()
            }
        }
        lanNetworkCallback = lanCallback
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            manager.registerNetworkCallback(request, lanCallback)
        }
    }

    /** 局域网网络发生变化：重新绑定进程默认网络，必要时重建监听 socket 并刷新状态。 */
    private fun onLanChanged() {
        runCatching { NetworkUtils.bindProcessToLanNetwork(this) }
        val current = server
        val label = NetworkUtils.lanTargetLabel(this, current?.boundPort ?: settings.port)
        if (label != null && current != null && current.isRunning &&
            !sameAddressSet(current.boundNetworkLabel, label)
        ) {
            // 局域网地址集合变了（换 Wi-Fi / 双 Wi-Fi 多出一个地址）：必须重建监听 socket
            rebindServerSocket()
        } else {
            refreshStatus()
        }
    }

    /** 地址集合比较（顺序无关）：避免两个地址顺序变化导致无意义的重建。 */
    private fun sameAddressSet(first: String?, second: String?): Boolean {
        if (first == null || second == null) return first == second
        return first.split("、").toSet() == second.split("、").toSet()
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivityManager ?: return
        networkCallback?.let { callback -> runCatching { manager.unregisterNetworkCallback(callback) } }
        lanNetworkCallback?.let { callback -> runCatching { manager.unregisterNetworkCallback(callback) } }
        networkCallback = null
        lanNetworkCallback = null
    }

    companion object {

        const val ACTION_START = "com.yuanzai.lanfile.action.START"
        const val ACTION_STOP = "com.yuanzai.lanfile.action.STOP"
        const val ACTION_RESTART = "com.yuanzai.lanfile.action.RESTART"
        const val EXTRA_FOREGROUND = "foreground"

        private val _status = MutableStateFlow(ServerStatus())

        /** UI 观察的服务状态。 */
        val status: StateFlow<ServerStatus> = _status.asStateFlow()

        /** 启动服务；[foreground] 为 true 时使用前台服务（后台/锁屏继续运行）。 */
        fun start(context: Context, foreground: Boolean) {
            val intent = Intent(context, LanServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FOREGROUND, foreground)
            try {
                if (foreground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务受限时的兜底：仅更新错误提示
                _status.value = _status.value.copy(error = "无法启动服务：${e.message ?: "系统限制"}")
            }
        }

        fun restart(context: Context, foreground: Boolean) {
            val intent = Intent(context, LanServerService::class.java)
                .setAction(ACTION_RESTART)
                .putExtra(EXTRA_FOREGROUND, foreground)
            try {
                if (foreground) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                _status.value = _status.value.copy(error = "无法重启服务：${e.message ?: "系统限制"}")
            }
        }

        /** 停止服务（会关闭 HTTP 服务并移除通知）。 */
        fun stop(context: Context) {
            val intent = Intent(context, LanServerService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                context.stopService(Intent(context, LanServerService::class.java))
            }
        }
    }
}