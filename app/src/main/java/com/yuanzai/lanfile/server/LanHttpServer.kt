package com.yuanzai.lanfile.server

import android.content.Context
import com.yuanzai.lanfile.core.NetworkUtils
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网 HTTP 服务器（纯 Kotlin 实现的轻量 ServerSocket 服务，无第三方依赖）。
 *
 * 特点：
 *  - 多客户端并发：线程池按需扩展，最多 [MAX_WORKERS] 个并发传输
 *  - 上传 / 下载全部流式处理，不会把大文件读进内存
 *  - 支持 keep-alive、Range 断点续传、chunked 请求体
 *  - 端口被占用时自动尝试后续端口
 *  - 任何单个请求出错都不会影响服务进程
 */
class LanHttpServer(
    context: Context,
    private val preferredPort: Int
) {

    private val appContext = context.applicationContext

    /** 实际绑定的端口（可能与首选端口不同）。 */
    @Volatile
    var boundPort: Int = preferredPort
        private set

    private val router = ApiRouter(appContext, WebAssets(appContext)) { boundPort }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ServerSocket>()
    private val acceptThreads = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    private val threadCounter = AtomicInteger(0)

    private var executor: ThreadPoolExecutor = newExecutor()

    private fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        CORE_WORKERS,
        MAX_WORKERS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable ->
            Thread(runnable, "lanfile-http-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var servedRequests: Int = 0
        private set

    @Volatile
    var activeConnections: Int = 0
        private set

    /** 局域网地址标签（用于判断地址集合是否变化 → 是否需要重建监听），未绑定时为 null。 */
    @Volatile
    var boundNetworkLabel: String? = null
        private set

    /** **实际**建立起来的 IPv6 监听端口；未建立时为 0。 */
    @Volatile
    var ipv6Port: Int = 0
        private set

    /**
     * 启动服务。
     *
     * 用最朴素、最兼容的做法：在通配地址上监听一个端口。
     * 这样本机所有地址（包括双 Wi-Fi 下的两个地址）都能连上，`127.0.0.1` 也能用。
     *
     * 唯一的网络处理是「建监听前把进程默认网络切到局域网」，避免默认网络是
     * 移动数据 / VPN 时回包走错出口；**不对已建立的连接做任何重绑定**。
     *
     * @return 实际监听端口
     * @throws IOException 所有候选端口都不可用时抛出，附带中文错误信息
     */
    @Throws(IOException::class)
    fun start(): Int {
        if (isRunning) {
            return boundPort
        }
        if (executor.isShutdown) executor = newExecutor()
        val targets = NetworkUtils.lanTargets(appContext, NetworkUtils.primaryIpv4())
        // 建监听之前先把进程默认网络切到局域网：这样监听 socket（以及它 accept 出来的连接）
        // 都从局域网网卡收发，不会被移动数据 / VPN 抢走默认路由。
        // 除此之外**不做任何网络绑定**——对已建立的连接中途改绑定会把连接变成单向黑洞。
        NetworkUtils.bindProcessToNetwork(appContext, targets.firstOrNull()?.network)
        var lastError: Exception? = null
        for (candidate in portCandidates(preferredPort)) {
            var socket: ServerSocket? = null
            try {
                val created = ServerSocket()
                socket = created
                created.reuseAddress = true
                // 必须显式写成 IPv4 的 0.0.0.0：只写 port 时，不同 Android 版本会把它解析成
                // IPv6 通配地址 ::（双栈），IPv4 侧行为不确定。
                created.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate), BACKLOG)
                val bound = created.localSocketAddress as? InetSocketAddress
                val actualPort = bound?.port ?: candidate
                listeners.add(created)
                boundPort = actualPort
                boundNetworkLabel =
                    if (targets.isEmpty()) null
                    else targets.joinToString("、") { "${it.ip}:$actualPort" }
                isRunning = true
                // 再额外开一条 IPv6 通道（独立端口，避免与 IPv4 通配地址抢端口）：
                // IPv4 那条路被中间设备掐断时，IPv6 往往是通的。
                startIpv6Listener(candidate + 1)
                val thread = Thread({ acceptLoop(created) }, "lanfile-accept")
                thread.isDaemon = true
                acceptThreads.add(thread)
                thread.start()
                return actualPort
            } catch (e: Exception) {
                runCatching { socket?.close() }
                lastError = e
            }
        }
        throw IOException(
            "无法监听端口 ${portCandidates(preferredPort).joinToString("/")}：" +
                (lastError?.message ?: "端口被占用")
        )
    }

    fun stop() {
        if (!isRunning && listeners.isEmpty()) return
        isRunning = false
        for (socket in listeners) runCatching { socket.close() }
        listeners.clear()
        ipv6Port = 0
        for (thread in acceptThreads) thread.interrupt()
        acceptThreads.clear()
        executor.shutdownNow()
        boundNetworkLabel = null
    }

    private fun portCandidates(preferred: Int): List<Int> {
        val result = ArrayList<Int>(PORT_ATTEMPTS)
        for (offset in 0 until PORT_ATTEMPTS) {
            val candidate = preferred + offset
            if (candidate in 1024..65535) result.add(candidate)
        }
        if (result.isEmpty()) result.add(8080)
        return result
    }

    /**
     * 额外建立一条 IPv6 监听（独立端口，所有异常都吞掉，绝不影响 IPv4 那条）。
     */
    private fun startIpv6Listener(port: Int) {
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("::"), port), BACKLOG)
            listeners.add(socket)
            ipv6Port = port
            val thread = Thread({ acceptLoop(socket) }, "lanfile-accept6")
            thread.isDaemon = true
            acceptThreads.add(thread)
            thread.start()
        } catch (e: Exception) {
            ipv6Port = 0
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (isRunning) {
                    // 单个 accept 失败不影响服务，稍等后继续
                    try {
                        Thread.sleep(50)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                break
            }
            try {
                executor.execute { handleClient(client) }
            } catch (e: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        activeConnections++
        val remote = runCatching {
            val address = socket.inetAddress?.hostAddress ?: "unknown"
            "$address:${socket.port}"
        }.getOrDefault("unknown")
        var requests = 0
        try {
            socket.tcpNoDelay = true
            // 注意：这里**不要**对已接受的 socket 调用 Network.bindSocket()，
            // 也不要把线程降为后台优先级：前者会把已建立的连接变成单向黑洞，
            // 后者会在 App 退到后台时被 CPU 分组限流，导致传输卡住。
            val source = BufferedSource(SocketByteSource(socket.getInputStream()))
            while (isRunning) {
                socket.soTimeout = if (requests == 0) FIRST_REQUEST_TIMEOUT_MS else KEEP_ALIVE_TIMEOUT_MS
                val request = try {
                    HttpRequest.read(source, remote)
                } catch (e: SocketTimeoutException) {
                    // 连上了却迟迟不发请求：不要静默挂着，明确回一个 408。
                    // 这样客户端至少能拿到一个可读的响应（而不是「0 字节」）。
                    if (requests == 0) {
                        runCatching {
                            val exchange = HttpExchange(socket, HttpRequest.placeholder(remote, source))
                            exchange.sendError(
                                408,
                                "连接已建立，但没有收到你发来的请求数据。请检查电脑上是否有" +
                                    "多张网卡 / 虚拟网卡 / VPN 代理，以及路由器是否开启了 AP 隔离。"
                            )
                            exchange.finish()
                        }
                    }
                    break
                } catch (e: IOException) {
                    // 对方发的不是合法 HTTP（例如浏览器强制 HTTPS、中间有代理改造请求）：
                    // 明确回一个 400 再断开，绝不静默关闭——否则客户端只会看到「0 字节响应」。
                    runCatching {
                        val exchange = HttpExchange(socket, HttpRequest.placeholder(remote, source))
                        exchange.sendError(400, e.message ?: "无法解析的 HTTP 请求")
                        exchange.finish()
                    }
                    break
                } ?: break

                val exchange = HttpExchange(socket, request)
                if (request.expectsContinue()) {
                    exchange.sendContinue()
                }
                servedRequests++
                try {
                    router.handle(exchange)
                } catch (e: Throwable) {
                    runCatching { exchange.sendError(500, "服务器内部错误：${e.message ?: "未知错误"}") }
                }
                runCatching { exchange.finish() }
                requests++
                if (!exchange.keepAlive) break
                // keep-alive 空闲等待时间短一些，尽快释放线程
                runCatching { socket.soTimeout = KEEP_ALIVE_TIMEOUT_MS }
            }
        } catch (e: Throwable) {
            // 客户端异常断开：属于正常情况
        } finally {
            activeConnections--
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val BACKLOG = 64
        private const val PORT_ATTEMPTS = 10
        private const val CORE_WORKERS = 4
        private const val MAX_WORKERS = 24
        private const val TRANSFER_TIMEOUT_MS = 60_000
        private const val KEEP_ALIVE_TIMEOUT_MS = 15_000

        /** 连接建立后等待首个请求的时间；超时就回 408，避免客户端无限挂起。 */
        private const val FIRST_REQUEST_TIMEOUT_MS = 5_000
    }
}