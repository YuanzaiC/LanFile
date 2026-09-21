package com.yuanzai.lanfile.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 访问过网页端的设备（按 IP 识别）。
 *
 * 除了记录 UA / 首次访问 / 最近访问 / 请求次数，还可以对单个设备：
 *  - 封禁：该 IP 的所有请求直接返回 403（网页显示「访问已被禁止」）
 *  - 单独授权：上传 / 删除 / 改动文件 / 文字互传，可覆盖全局开关
 *    （`null` 表示跟随全局设置）
 */
class WebClient(
    val ip: String,
    var userAgent: String = "",
    var firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = firstSeen,
    var requests: Int = 0,
    var blocked: Boolean = false,
    var allowUpload: Boolean? = null,
    var allowDelete: Boolean? = null,
    var allowModify: Boolean? = null,
    var allowText: Boolean? = null
) {

    /** 设备类别：列表里用不同图标区分（电脑 / 安卓 / iPhone / 平板 / 未知）。 */
    enum class DeviceKind { COMPUTER, ANDROID, IPHONE, IPAD, OTHER }

    /** UA 压缩成一句人话，列表里显示用。 */
    fun deviceLabel(): String = describeUserAgent(userAgent)

    /** 该设备属于哪一类（决定用哪个图标）。 */
    val kind: DeviceKind
        get() = deviceKind(userAgent)

    fun permissionLabel(kind: String, global: Boolean): String {
        val value = permission(kind)
        return when (value) {
            null -> "跟随全局（${if (global) "允许" else "禁止"}）"
            true -> "允许"
            false -> "禁止"
        }
    }

    fun permission(kind: String): Boolean? = when (kind) {
        ClientRegistry.KIND_UPLOAD -> allowUpload
        ClientRegistry.KIND_DELETE -> allowDelete
        ClientRegistry.KIND_MODIFY -> allowModify
        else -> allowText
    }

    fun setPermission(kind: String, value: Boolean?) {
        when (kind) {
            ClientRegistry.KIND_UPLOAD -> allowUpload = value
            ClientRegistry.KIND_DELETE -> allowDelete = value
            ClientRegistry.KIND_MODIFY -> allowModify = value
            else -> allowText = value
        }
    }

    /** 是否被单独设过权限（用于列表上标注「已单独设置」）。 */
    fun hasCustomPermissions(): Boolean =
        allowUpload != null || allowDelete != null || allowModify != null || allowText != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("ip", ip)
        put("ua", userAgent)
        put("first", firstSeen)
        put("last", lastSeen)
        put("requests", requests)
        put("blocked", blocked)
        put("allowUpload", allowUpload ?: JSONObject.NULL)
        put("allowDelete", allowDelete ?: JSONObject.NULL)
        put("allowModify", allowModify ?: JSONObject.NULL)
        put("allowText", allowText ?: JSONObject.NULL)
    }

    companion object {

        fun fromJson(json: JSONObject): WebClient = WebClient(
            ip = json.optString("ip"),
            userAgent = json.optString("ua"),
            firstSeen = json.optLong("first", System.currentTimeMillis()),
            lastSeen = json.optLong("last", System.currentTimeMillis()),
            requests = json.optInt("requests", 0),
            blocked = json.optBoolean("blocked", false),
            allowUpload = json.optBooleanOrNull("allowUpload"),
            allowDelete = json.optBooleanOrNull("allowDelete"),
            allowModify = json.optBooleanOrNull("allowModify"),
            allowText = json.optBooleanOrNull("allowText")
        )

        private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
            if (!has(name) || isNull(name)) null else optBoolean(name)

        /** 把 User-Agent 归纳成「系统 · 浏览器」这样的短标签。 */
        fun describeUserAgent(ua: String): String {
            if (ua.isBlank()) return "未知设备"
            val commandLine = ua.startsWith("curl", ignoreCase = true) ||
                ua.startsWith("Wget", ignoreCase = true) ||
                ua.startsWith("Java", ignoreCase = true) ||
                ua.startsWith("python", ignoreCase = true)
            val system = when {
                ua.contains("Android", ignoreCase = true) -> "Android"
                ua.contains("iPhone", ignoreCase = true) -> "iPhone"
                ua.contains("iPad", ignoreCase = true) -> "iPad"
                ua.contains("Macintosh", ignoreCase = true) -> "macOS"
                ua.contains("Windows", ignoreCase = true) -> "Windows"
                ua.contains("CrOS", ignoreCase = true) -> "ChromeOS"
                ua.contains("Linux", ignoreCase = true) -> "Linux"
                commandLine -> "命令行"
                else -> "未知系统"
            }
            val browser = when {
                ua.contains("Edg/", ignoreCase = true) -> "Edge"
                ua.contains("OPR/", ignoreCase = true) || ua.contains("Opera", ignoreCase = true) -> "Opera"
                ua.contains("MicroMessenger", ignoreCase = true) -> "微信"
                ua.contains("Firefox", ignoreCase = true) -> "Firefox"
                ua.contains("Chrome", ignoreCase = true) -> "Chrome"
                ua.contains("Safari", ignoreCase = true) -> "Safari"
                ua.startsWith("curl", ignoreCase = true) -> "curl"
                ua.startsWith("Wget", ignoreCase = true) -> "wget"
                ua.contains("Java", ignoreCase = true) -> "Java 客户端"
                else -> "未知浏览器"
            }
            return "$system · $browser"
        }

        /** 按 UA 判断设备类别。 */
        fun deviceKind(ua: String): DeviceKind = when {
            ua.isBlank() -> DeviceKind.OTHER
            ua.contains("iPad", ignoreCase = true) -> DeviceKind.IPAD
            ua.contains("Android", ignoreCase = true) && ua.contains("Tablet", ignoreCase = true) ->
                DeviceKind.IPAD
            ua.contains("iPhone", ignoreCase = true) || ua.contains("iPod", ignoreCase = true) ->
                DeviceKind.IPHONE
            ua.contains("Android", ignoreCase = true) -> DeviceKind.ANDROID
            ua.contains("Windows", ignoreCase = true) ||
                ua.contains("Macintosh", ignoreCase = true) ||
                ua.contains("Mac OS X", ignoreCase = true) ||
                ua.contains("Linux", ignoreCase = true) ||
                ua.contains("CrOS", ignoreCase = true) -> DeviceKind.COMPUTER
            else -> DeviceKind.OTHER
        }
    }
}

/** 访问记录仓库：内存里维护，落盘到 `filesDir/clients.json`。 */
object ClientRegistry {

    const val KIND_UPLOAD = "upload"
    const val KIND_DELETE = "delete"
    const val KIND_MODIFY = "modify"
    const val KIND_TEXT = "text"

    const val KIND_LABELS_UPLOAD = "上传文件"
    const val KIND_LABELS_DELETE = "删除文件"
    const val KIND_LABELS_MODIFY = "重命名 / 新建 / 移动 / 复制"
    const val KIND_LABELS_TEXT = "文字互传"

    private const val FILE_NAME = "clients.json"
    private const val MAX_CLIENTS = 200
    /** 访问记录落盘节流：网页端在轮询，不能每个请求都写文件。 */
    private const val SAVE_INTERVAL_MS = 20_000L

    private val lock = Any()
    private val clients = LinkedHashMap<String, WebClient>()
    private val listeners = LinkedHashSet<(List<WebClient>) -> Unit>()

    private var file: File? = null
    private var lastSaveAt = 0L

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, FILE_NAME)
            load()
        }
    }

    fun all(): List<WebClient> = synchronized(lock) {
        clients.values.sortedWith(
            compareByDescending<WebClient> { it.blocked }.thenByDescending { it.lastSeen }
        )
    }

    fun count(): Int = synchronized(lock) { clients.size }

    fun blockedCount(): Int = synchronized(lock) { clients.values.count { it.blocked } }

    fun find(ip: String?): WebClient? = synchronized(lock) {
        if (ip.isNullOrBlank()) null else clients[ip]
    }

    /** 记录一次访问（不存在则新建）。 */
    fun touch(ip: String?, userAgent: String?): WebClient? {
        if (ip.isNullOrBlank() || ip == "unknown") return null
        var added = false
        val client = synchronized(lock) {
            val existing = clients[ip]
            if (existing == null) {
                added = true
                val created = WebClient(
                    ip = ip,
                    userAgent = userAgent.orEmpty(),
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                    requests = 1
                )
                clients[ip] = created
                if (clients.size > MAX_CLIENTS) {
                    // 超出上限时丢掉最久没访问的（封禁中的记录不动）
                    clients.values
                        .filter { !it.blocked }
                        .minByOrNull { it.lastSeen }
                        ?.let { clients.remove(it.ip) }
                }
                created
            } else {
                existing.lastSeen = System.currentTimeMillis()
                existing.requests += 1
                if (!userAgent.isNullOrBlank() && userAgent != existing.userAgent) {
                    existing.userAgent = userAgent
                }
                existing
            }
        }
        when {
            added -> persist(force = true)
            System.currentTimeMillis() - lastSaveAt > SAVE_INTERVAL_MS -> persist(force = true)
        }
        if (added) notifyListeners()
        return client
    }

    fun setBlocked(ip: String, blocked: Boolean) {
        synchronized(lock) { clients[ip]?.blocked = blocked }
        persist(force = true)
        notifyListeners()
    }

    fun setPermission(ip: String, kind: String, value: Boolean?) {
        synchronized(lock) { clients[ip]?.setPermission(kind, value) }
        persist(force = true)
        notifyListeners()
    }

    fun remove(ip: String) {
        synchronized(lock) { clients.remove(ip) }
        persist(force = true)
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) { clients.clear() }
        persist(force = true)
        notifyListeners()
    }

    // ------------------------------------------------------------- 权限判定

    fun isBlocked(ip: String?): Boolean = find(ip)?.blocked == true

    fun canUpload(client: WebClient?, settings: SettingsStore): Boolean =
        client?.allowUpload ?: settings.webUploadEnabled

    fun canDelete(client: WebClient?, settings: SettingsStore): Boolean =
        client?.allowDelete ?: settings.webDeleteEnabled

    fun canModify(client: WebClient?, settings: SettingsStore): Boolean =
        client?.allowModify ?: settings.webModifyEnabled

    fun canText(client: WebClient?, settings: SettingsStore): Boolean =
        client?.allowText ?: settings.webTextEnabled

    // ------------------------------------------------------------- 监听 / 落盘

    fun addListener(listener: (List<WebClient>) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: (List<WebClient>) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = all()
        val copy = synchronized(lock) { listeners.toList() }
        for (listener in copy) runCatching { listener(snapshot) }
    }

    private fun load() {
        val target = file ?: return
        if (!target.isFile) return
        runCatching {
            val array = JSONArray(target.readText(Charsets.UTF_8))
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val client = WebClient.fromJson(json)
                if (client.ip.isNotBlank()) clients[client.ip] = client
            }
        }
    }

    fun persist(force: Boolean = false) {
        val target = file ?: return
        val snapshot = synchronized(lock) {
            if (!force && System.currentTimeMillis() - lastSaveAt < SAVE_INTERVAL_MS) return
            lastSaveAt = System.currentTimeMillis()
            JSONArray().also { array -> clients.values.forEach { array.put(it.toJson()) } }
        }
        runCatching { target.writeText(snapshot.toString(), Charsets.UTF_8) }
    }
}