package com.yuanzai.lanfile.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * 局域网 IP 获取与网络状态判断。
 *
 * 注意：Android 的「默认网络」不一定是 Wi-Fi。
 * 例如手机连了一个没有外网的路由器时，系统会把默认网络保持为移动数据，
 * 而 App 里普通的 socket 会跟随 UID 路由规则走默认网络，导致
 * 「手机自己能打开网页、同一局域网的其他设备却打不开」。
 * 因此这里不只看 [ConnectivityManager.getActiveNetwork]，而是遍历所有网络。
 */
object NetworkUtils {

    private val PREFERRED_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "en", "wifi")

    /**
     * 需要排除的网卡前缀：回环、点对点、蜂窝数据（不同厂商命名不同）、VPN 隧道等。
     * 蜂窝数据地址常是 10.x，会被误判成「局域网地址」显示出来，必须按网卡名排除。
     */
    private val IGNORED_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "v4-", "p2p", "dummy", "tun", "ppp", "vpn",
        "clat", "sit", "lo", "hwsim"
    )

    /**
     * 当前最适合展示 / 复制的局域网 IPv4 地址。
     *
     * 优先取「系统默认网络（activeNetwork）上的地址」——双 Wi-Fi 手机有两个地址时，
     * 只有默认网络那条链路最可能是通的，这样首页「访问地址」不会给出一个连不上的地址。
     */
    fun primaryIpv4(): String? {
        val candidates = collectAddresses()
        if (candidates.isEmpty()) return null
        val active = activeNetworkAddresses()
        if (active != null) {
            candidates.firstOrNull { it.second in active }?.let { return it.second }
        }
        return candidates.first().second
    }

    private fun appContext(): Context? = try {
        com.yuanzai.lanfile.LanFileApp.instance
    } catch (e: Throwable) {
        null
    }

    /** 系统默认网络上的所有地址（用于挑首选地址）。 */
    private fun activeNetworkAddresses(): Set<String>? {
        val context = appContext() ?: return null
        val manager = connectivity(context) ?: return null
        val active = runCatching { manager.activeNetwork }.getOrNull() ?: return null
        val properties = linkProperties(context, active) ?: return null
        val result = properties.linkAddresses.mapNotNull { it.address.hostAddress }.toSet()
        return result.ifEmpty { null }
    }

    /** 所有可用局域网 IPv4 地址（用于展示“其它地址”）。 */
    fun allIpv4(): List<String> = collectAddresses().map { it.second }

    /** 返回 (网卡名, IP) 列表，已按优先级排序。 */
    fun interfaces(): List<Pair<String, String>> = collectAddresses()

    /** 网卡 MAC 地址（用于和电脑上 `arp -a` 对比，排查同一 IP 被两台设备占用）。 */
    fun macAddress(interfaceName: String): String? = try {
        NetworkInterface.getByName(interfaceName)?.hardwareAddress
            ?.joinToString(":") { byte -> String.format("%02x", byte) }
    } catch (e: Exception) {
        null
    }

    /**
     * 局域网网卡上的全局 IPv6 地址（(网卡名, 地址)）。
     *
     * 这是给「IPv4 这条路走不通」准备的备用通道：家里路由器开了 IPv6 时，
     * 手机和电脑在同一网段内直接用 IPv6 通信，完全绕开 IPv4 的 ARP 与中间设备。
     * 只返回全局 / ULA 地址——链路本地地址（fe80::）需要带 %网卡名，浏览器里没法用。
     */
    fun globalIpv6(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>(2)
        val list = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            return result
        }
        for (nif in list) {
            val name = runCatching { nif.name }.getOrNull() ?: continue
            val lower = name.lowercase()
            if (IGNORED_PREFIXES.any { lower.startsWith(it) }) continue
            if (PREFERRED_PREFIXES.none { lower.startsWith(it) }) continue
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            for (address in nif.inetAddresses) {
                val ipv6 = address as? Inet6Address ?: continue
                if (ipv6.isLinkLocalAddress || ipv6.isLoopbackAddress || ipv6.isMulticastAddress) continue
                val host = ipv6.hostAddress?.substringBefore('%') ?: continue
                if (host.isEmpty()) continue
                result.add(name to host)
            }
        }
        return result
    }

    private fun collectAddresses(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        for (nif in interfaces) {
            val name = runCatching { nif.name ?: "" }.getOrDefault("")
            val lower = name.lowercase()
            if (IGNORED_PREFIXES.any { lower.startsWith(it) }) continue
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loopback = runCatching { nif.isLoopback }.getOrDefault(true)
            if (!up || loopback) continue
            val addresses = runCatching { nif.inetAddresses }.getOrNull() ?: continue
            for (address in addresses) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = address.hostAddress ?: continue
                if (!address.isSiteLocalAddress) continue
                result.add(name to host)
            }
        }
        return result.sortedBy { (name, _) ->
            val lower = name.lowercase()
            val index = PREFERRED_PREFIXES.indexOfFirst { lower.startsWith(it) }
            if (index >= 0) index else PREFERRED_PREFIXES.size
        }
    }

    fun connectivity(context: Context): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 遍历所有网络，返回第一个已连接的 Wi-Fi / 以太网网络。 */
    fun lanNetwork(context: Context): Network? {
        for ((network, caps) in connectedNetworks(context)) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return network
            }
        }
        return null
    }

    /** 所有可选网络（含热点、以太网、蜂窝、VPN），用于子网匹配与诊断。 */
    fun allNetworks(context: Context): List<Pair<Network, NetworkCapabilities>> =
        connectedNetworks(context)

    fun linkProperties(context: Context, network: Network): LinkProperties? =
        runCatching { connectivity(context)?.getLinkProperties(network) }.getOrNull()

    /** 一个可用于局域网服务的网络（Wi-Fi / 以太网 / 热点），带便于展示的标签。 */
    data class LanNetworkInfo(
        val network: Network,
        val ip: String?,
        val interfaceName: String?,
        val transportLabel: String
    ) {
        val label: String
            get() = listOfNotNull(interfaceName, ip).joinToString(" ").ifEmpty { transportLabel }
    }

    /**
     * 列出**所有**应该承载局域网服务的目标地址。
     *
     * 手机可能同时有两个局域网地址（例如「双 Wi-Fi 加速」同时连了 2.4G 与 5G，
     * 出现 wlan0=192.168.1.83、wlan1=192.168.1.85 两个地址）。
     * Android 的 socket 上行路由按 netId 决定，只用一个监听 socket 的话，
     * 从另一个地址进来的连接会因为回包走错网络而被对方立刻拒绝（RST），
     * 所以必须给每个地址各建一个监听 socket。
     *
     * @param preferredIp 优先排在前面的地址（通常是界面上展示的那个 IP）
     */
    fun lanTargets(context: Context, preferredIp: String? = null): List<LanNetworkInfo> {
        val localInterfaces = collectAddresses()
        val result = ArrayList<LanNetworkInfo>(2)
        for ((network, caps) in connectedNetworks(context)) {
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!wifi && !ethernet) continue
            val properties = linkProperties(context, network) ?: continue
            for (address in properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()) {
                if (!address.isSiteLocalAddress || address.isLinkLocalAddress) continue
                val ip = address.hostAddress ?: continue
                if (result.any { it.ip == ip }) continue
                result.add(
                    LanNetworkInfo(
                        network = network,
                        ip = ip,
                        interfaceName = localInterfaces.firstOrNull { it.second == ip }?.first,
                        transportLabel = if (ethernet) "以太网" else "Wi-Fi"
                    )
                )
            }
        }
        if (preferredIp != null) {
            val index = result.indexOfFirst { it.ip == preferredIp }
            if (index > 0) {
                val preferred = result.removeAt(index)
                result.add(0, preferred)
            }
        }
        // 补漏：网卡上存在、但 ConnectivityManager 没列出的地址（双 Wi-Fi 场景常见）。
        // 用 LinkProperties.interfaceName 把地址精确对应回它的网络。
        val byInterface = HashMap<String, Network>(2)
        for ((network, caps) in connectedNetworks(context)) {
            if (!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                continue
            }
            val name = interfaceName(context, network) ?: continue
            byInterface[name] = network
        }
        for ((name, ip) in localInterfaces) {
            if (result.any { it.ip == ip }) continue
            val network = byInterface[name] ?: continue
            result.add(LanNetworkInfo(network, ip, name, "Wi-Fi"))
        }
        return result
    }

    /** 网卡名（wlan0 / wlan1 / eth0…）。 */
    @Suppress("DEPRECATION")
    fun interfaceName(context: Context, network: Network): String? =
        runCatching { linkProperties(context, network)?.interfaceName }.getOrNull()

    /**
     * 找到拥有该地址的网络（**精确匹配地址**）。
     *
     * 同一网段下可能有多个局域网地址（wlan0=.83、wlan1=.85），
     * 只有精确匹配才能把连接绑定到正确的网络。
     */
    fun networkForAddress(context: Context, ip: String?): Network? {
        if (ip.isNullOrEmpty()) return null
        for ((network, caps) in connectedNetworks(context)) {
            if (!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                continue
            }
            val properties = linkProperties(context, network) ?: continue
            if (properties.linkAddresses.any { it.address.hostAddress == ip }) return network
        }
        return null
    }

    /**
     * 找出**首选**的局域网网络（见 [lanTargets] 的说明）。
     *
     * @param preferredIp 优先匹配的地址（通常是界面上展示的那个 IP）
     */
    fun lanNetworkInfo(context: Context, preferredIp: String? = null): LanNetworkInfo? =
        lanTargets(context, preferredIp).firstOrNull()

    /** 把进程默认网络绑定到指定网络（null = 解除绑定）。 */
    fun bindProcessToNetwork(context: Context, network: Network?): Boolean {
        val manager = connectivity(context) ?: return false
        return runCatching { manager.bindProcessToNetwork(network) }.isSuccess
    }

    /**
     * 当前局域网监听地址的标签（形如 “192.168.1.83:8080、192.168.1.85:8080”）。
     * 服务端与界面用同一份格式，方便判断「地址集合是否变了、要不要重建监听」。
     */
    fun lanTargetLabel(context: Context, port: Int): String? =
        lanTargets(context, primaryIpv4())
            .joinToString("、") { "${it.ip}:$port" }
            .ifEmpty { null }

    private fun connectedNetworks(context: Context): List<Pair<Network, NetworkCapabilities>> {
        val manager = connectivity(context) ?: return emptyList()
        val result = ArrayList<Pair<Network, NetworkCapabilities>>(4)
        val seen = HashSet<Network>(4)
        for (network in candidateNetworks(manager)) {
            if (!seen.add(network)) continue
            val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            result.add(network to caps)
        }
        return result
    }

    /**
     * 候选网络列表。
     *
     * [ConnectivityManager.getAllNetworks] 在较新的系统上已被标记废弃（可能不再返回其它网络），
     * 因此这里同时使用：
     *  1. allNetworks（老系统上最全）；
     *  2. 服务里注册 NetworkCallback 时记录下来的 Wi-Fi / 以太网网络（新系统上仍然可靠）；
     *  3. activeNetwork（兜底）。
     */
    @Suppress("DEPRECATION")
    private fun candidateNetworks(manager: ConnectivityManager): List<Network> {
        val result = LinkedHashSet<Network>(4)
        runCatching { manager.allNetworks }.getOrNull()?.let { networks ->
            for (network in networks) if (network != null) result.add(network)
        }
        result.addAll(knownLanNetworks)
        runCatching { manager.activeNetwork }.getOrNull()?.let { result.add(it) }
        return result.toList()
    }

    // 由前台服务的 NetworkCallback 维护，避免新系统上 allNetworks 受限后拿不到网络
    private val knownLanNetworks = java.util.concurrent.CopyOnWriteArraySet<Network>()

    fun noteLanNetwork(network: Network, available: Boolean) {
        if (available) knownLanNetworks.add(network) else knownLanNetworks.remove(network)
    }

    /** 是否连接到 Wi-Fi 或以太网（局域网可用）——不要求它是“默认网络”。 */
    fun hasLanConnection(context: Context): Boolean = lanNetworkInfo(context) != null

    fun isWifiConnected(context: Context): Boolean =
        connectedNetworks(context).any { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

    fun isEthernetConnected(context: Context): Boolean =
        connectedNetworks(context).any { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

    fun isCellularConnected(context: Context): Boolean =
        connectedNetworks(context).any { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }

    fun isVpnActive(context: Context): Boolean =
        connectedNetworks(context).any { (_, caps) ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

    /**
     * 把 App 进程的默认网络切换为局域网网络，并返回该网络的标签（无局域网时返回 null）。
     *
     * Android 没有给「监听 socket」提供 bindSocket 接口（[Network] 只有 Socket / DatagramSocket /
     * FileDescriptor 三个重载），因此对监听 socket 只能通过进程级绑定来控制上行路由：
     * 绑定后新建的 socket 都会走这个网络，SYN-ACK 与响应才能正确回到局域网里的其他设备。
     *
     * 本 App 自身不访问外网，所以进程级绑定没有副作用；没有局域网时会解除绑定。
     */
    fun bindProcessToLanNetwork(context: Context): String? {
        val info = lanNetworkInfo(context, primaryIpv4())
        if (info == null) {
            bindProcessToNetwork(context, null)
            return null
        }
        return if (bindProcessToNetwork(context, info.network)) info.label else null
    }

    /** 当前“默认网络”（App 普通 socket 会走它）是否是局域网。 */
    fun isDefaultNetworkLan(context: Context): Boolean {
        val manager = connectivity(context) ?: return false
        val active = runCatching { manager.activeNetwork }.getOrNull() ?: return false
        val caps = runCatching { manager.getNetworkCapabilities(active) }.getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 网络状态的中文描述，用于首页提示。 */
    fun describe(context: Context): String = when {
        hasLanConnection(context) && defaultNetworkLabel(context) != null -> {
            val label = defaultNetworkLabel(context)
            "已连接局域网（默认网络：$label）"
        }
        hasLanConnection(context) -> "已连接局域网"
        isCellularConnected(context) -> "只连接了移动数据，请连接 Wi-Fi"
        else -> "未连接任何网络"
    }

    /** 默认网络的类型名，用于诊断（非局域网时给出提醒）。 */
    fun defaultNetworkLabel(context: Context): String? {
        val manager = connectivity(context) ?: return null
        val active = runCatching { manager.activeNetwork }.getOrNull() ?: return null
        val caps = runCatching { manager.getNetworkCapabilities(active) }.getOrNull() ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其它"
        }
    }

    /**
     * 可能影响「其他设备访问本机」的网络提示，没有问题时返回 null。
     */
    fun accessWarning(context: Context): String? = when {
        isVpnActive(context) ->
            "检测到 VPN：部分 VPN 会拦截局域网回包，若其他设备打不开网页请先关闭 VPN。"
        hasLanConnection(context) && !isDefaultNetworkLan(context) &&
            defaultNetworkLabel(context) == "移动数据" ->
            "已连接 Wi-Fi，但系统默认网络是移动数据（该 Wi-Fi 可能没有外网）。" +
                "若其他设备打不开网页，请关闭移动数据后重试。"
        isCellularConnected(context) && !hasLanConnection(context) ->
            "只连接了移动数据，其他设备无法通过局域网访问，请先连接 Wi-Fi。"
        else -> null
    }
}