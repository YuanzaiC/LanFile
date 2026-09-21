package com.yuanzai.lanfile.server

import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/**
 * 一条已解析的 HTTP 请求。
 */
internal class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val body: HttpBody?,
    val remoteAddress: String,
    val source: BufferedSource
) {

    /** 解码后的路径，例如 /图片/壁纸 */
    val path: String = decodePath(extractPath(target))

    /**
     * 客户端 IP（去掉 remoteAddress 末尾的端口）。
     * IPv4 / IPv6 都适用：端口永远是最后一段。
     */
    val clientIp: String
        get() = remoteAddress.substringBeforeLast(':').ifBlank { remoteAddress }

    /** 客户端标识（浏览器 UA），设备列表里用来展示是什么设备。 */
    val userAgent: String
        get() = header("user-agent").orEmpty()

    /** 查询参数（已解码）。 */
    val query: Map<String, String> = parseQuery(target)

    val keepAlive: Boolean

    init {
        val connection = headers["connection"]?.lowercase() ?: ""
        keepAlive = when {
            connection.contains("close") -> false
            version == "HTTP/1.0" -> connection.contains("keep-alive")
            else -> true
        }
    }

    fun header(name: String): String? = headers[name.lowercase()]

    fun queryParam(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }

    fun contentType(): String? = header("content-type")

    /** multipart/form-data 的 boundary。 */
    fun boundary(): String? {
        val type = contentType() ?: return null
        if (!type.lowercase().startsWith("multipart/")) return null
        val marker = "boundary="
        val index = type.lowercase().indexOf(marker)
        if (index < 0) return null
        var value = type.substring(index + marker.length).trim()
        if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        return value.substringBefore(';').trim().takeIf { it.isNotEmpty() }
    }

    fun expectsContinue(): Boolean =
        header("expect")?.lowercase()?.contains("100-continue") == true

    /** 解析 JSON 请求体。 */
    fun jsonBody(maxBytes: Int = 4 * 1024 * 1024): JSONObject {
        val bytes = bodyBytes(maxBytes)
        if (bytes.isEmpty()) throw com.yuanzai.lanfile.core.OpException(400, "缺少请求数据")
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw com.yuanzai.lanfile.core.OpException(400, "请求数据格式错误（需要 JSON）")
        }
    }

    fun bodyBytes(maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        val requestBody = body ?: return ByteArray(0)
        return try {
            requestBody.readBytes(maxBytes)
        } catch (e: IOException) {
            throw com.yuanzai.lanfile.core.OpException(413, e.message ?: "请求体过大")
        }
    }

    fun bodyText(maxBytes: Int = 4 * 1024 * 1024): String =
        String(bodyBytes(maxBytes), Charsets.UTF_8)

    private fun extractPath(target: String): String {
        var value = target
        // 兼容代理发送的绝对 URI
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            val schemeEnd = value.indexOf("://") + 3
            val slash = value.indexOf('/', schemeEnd)
            value = if (slash >= 0) value.substring(slash) else "/"
        }
        val queryIndex = value.indexOf('?')
        if (queryIndex >= 0) value = value.substring(0, queryIndex)
        val hashIndex = value.indexOf('#')
        if (hashIndex >= 0) value = value.substring(0, hashIndex)
        return if (value.isEmpty()) "/" else value
    }

    private fun parseQuery(target: String): Map<String, String> {
        val index = target.indexOf('?')
        if (index < 0 || index == target.length - 1) return emptyMap()
        var raw = target.substring(index + 1)
        val hash = raw.indexOf('#')
        if (hash >= 0) raw = raw.substring(0, hash)
        val result = HashMap<String, String>(4)
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            val decodedKey = runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
            val decodedValue = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            result[decodedKey] = decodedValue
        }
        return result
    }

    companion object {

        /** 读取请求行与请求头；连接空闲关闭时返回 null。 */
        fun read(source: BufferedSource, remoteAddress: String): HttpRequest? {
            val requestLine = source.readLine(8192) ?: return null
            // 浏览器强制 HTTPS 时会先发 TLS 握手：首字节 0x16（Handshake）。
            // 明确区分出来，日志里就不会只写“解析失败”而让人猜。
            if (requestLine.isNotEmpty() && requestLine[0].code == 0x16) {
                throw IOException("对方用 HTTPS(加密) 访问了本服务，而本服务只提供 HTTP —— 请在浏览器里用 http:// 打开，或关闭浏览器的“始终使用安全连接/HTTPS 优先”")
            }
            var current = requestLine
            var guard = 0
            while (current.isBlank()) {
                // 容忍请求之间的空行
                if (++guard > 4) return null
                current = source.readLine(8192) ?: return null
            }
            return parse(current, source, remoteAddress)
        }

        /**
         * 请求无法解析时使用的占位对象：只用于把 400 错误写回客户端。
         * 用 HTTP/1.0 让 [HttpRequest.keepAlive] 为 false，写完就断开。
         */
        fun placeholder(remoteAddress: String, source: BufferedSource): HttpRequest =
            HttpRequest("GET", "/", "HTTP/1.0", emptyMap(), null, remoteAddress, source)

        private fun parse(requestLine: String, source: BufferedSource, remoteAddress: String): HttpRequest {
            val parts = requestLine.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) throw IOException("请求行格式错误：$requestLine")
            val method = parts[0].uppercase()
            val target = parts[1]
            val version = if (parts.size >= 3) parts[2] else "HTTP/1.1"

            val headers = HashMap<String, String>(16)
            while (true) {
                val line = source.readLine(8192) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                // 同名头部合并（例如多个 Cookie）
                val existing = headers[name]
                headers[name] = if (existing == null) value else "$existing, $value"
            }

            val transferEncoding = headers["transfer-encoding"]?.lowercase() ?: ""
            val body: HttpBody? = when {
                transferEncoding.contains("chunked") -> ChunkedBody(source)
                else -> {
                    val length = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
                    if (length > 0) FixedLengthBody(source, length) else null
                }
            }

            return HttpRequest(method, target, version, headers, body, remoteAddress, source)
        }

        private fun decodePath(raw: String): String {
            val decoded = percentDecode(raw)
            if (decoded.isEmpty()) return "/"
            return if (decoded.startsWith('/')) decoded else "/$decoded"
        }
    }
}