package com.yuanzai.lanfile.server

/**
 * 进程内共享的服务器实例引用。
 *
 * 服务端运行在 App 自己的进程里，因此 UI 可以直接读取实时状态，不必再做一次 IPC。
 */
object LanServerHolder {

    @Volatile
    var server: LanHttpServer? = null

    fun isRunning(): Boolean = server?.isRunning == true

    fun boundPort(): Int? = server?.boundPort

    /** 正在监听的局域网地址（未绑定时为 null）。 */
    fun boundNetworkLabel(): String? = server?.boundNetworkLabel
}