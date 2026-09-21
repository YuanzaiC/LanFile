package com.yuanzai.lanfile.model

/** 服务运行状态，UI 通过 StateFlow 观察。 */
data class ServerStatus(
    val running: Boolean = false,
    val port: Int = 8080,
    val ip: String? = null,
    val rootPath: String = "",
    val usingPublicDir: Boolean = false,
    val startedAt: Long = 0L,
    val servedRequests: Int = 0,
    val error: String? = null,
    val networkAvailable: Boolean = false
) {
    /** 形如 http://192.168.1.100:8080，无 IP 时为 null。 */
    val url: String?
        get() = ip?.takeIf { it.isNotBlank() }?.let { "http://$it:$port" }

    val ipLabel: String
        get() = ip?.takeIf { it.isNotBlank() } ?: "未连接局域网"

    val statusLabel: String
        get() = when {
            error != null -> error
            running && url != null -> "服务运行中"
            running -> "服务运行中（等待网络）"
            else -> "服务已停止"
        }
}