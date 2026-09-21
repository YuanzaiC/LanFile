package com.yuanzai.lanfile.server

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 一次请求 / 响应的写回封装。
 *
 * 大文件下载使用 [sendFile]，它只做流式拷贝（支持 Range 断点续传），
 * 绝不会把整个文件读进内存。
 */
internal class HttpExchange(
    private val socket: Socket,
    val request: HttpRequest,
    private val serverName: String = "LanFile/1.0"
) {

    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

    var keepAlive: Boolean = request.keepAlive
    var committed: Boolean = false
        private set

    /** 已写出的响应状态码（尚未写出时为 0），用于日志与诊断。 */
    var statusCode: Int = 0
        private set

    // ------------------------------------------------------------- JSON / 文本

    fun sendJson(json: JSONObject, status: Int = 200) {
        sendBytes(
            json.toString().toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8",
            status,
            cacheControl = "no-store"
        )
    }

    fun sendJsonRaw(raw: String, status: Int = 200) {
        sendBytes(
            raw.toByteArray(Charsets.UTF_8),
            "application/json; charset=utf-8",
            status,
            cacheControl = "no-store"
        )
    }

    fun sendError(status: Int, message: String) {
        if (committed) {
            // 响应已经开始写出，无法再改状态码，只能断开连接
            keepAlive = false
            return
        }
        val json = JSONObject().apply {
            put("ok", false)
            put("error", message)
        }
        sendJson(json, status)
    }

    fun sendText(text: String, contentType: String, status: Int = 200, cacheControl: String? = null) {
        sendBytes(text.toByteArray(Charsets.UTF_8), contentType, status, cacheControl = cacheControl)
    }

    fun sendNoContent(status: Int = 204) {
        writeHeaders(status, emptyList())
    }

    /** 回应客户端 `Expect: 100-continue`，必须在任何正式响应之前调用。 */
    fun sendContinue() {
        runCatching {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    fun sendBytes(
        bytes: ByteArray,
        contentType: String,
        status: Int = 200,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        cacheControl: String? = "no-store"
    ) {
        val headers = ArrayList<Pair<String, String>>(extraHeaders.size + 3)
        headers.add("Content-Type" to contentType)
        headers.add("Content-Length" to bytes.size.toString())
        cacheControl?.let { headers.add("Cache-Control" to it) }
        headers.addAll(extraHeaders)
        writeHeaders(status, headers)
        if (request.method != "HEAD" && bytes.isNotEmpty()) {
            output.write(bytes)
        }
    }

    // ------------------------------------------------------------- 文件下载

    /**
     * 流式发送文件，支持 Range 请求（断点续传 / 音视频拖动）。
     * 文件不存在会抛出 [IOException]，由调用方转换 404。
     */
    fun sendFile(file: File, contentType: String, attachment: Boolean, downloadName: String) {
        val total = file.length()
        val disposition = contentDisposition(downloadName, attachment)
        val isHead = request.method == "HEAD"

        if (total <= 0L) {
            writeHeaders(
                200,
                listOf(
                    "Content-Type" to contentType,
                    "Content-Length" to "0",
                    "Accept-Ranges" to "bytes",
                    "Content-Disposition" to disposition
                )
            )
            return
        }

        when (val outcome = parseRange(request.header("range"), total)) {
            is RangeOutcome.Partial -> {
                val length = outcome.end - outcome.start + 1
                writeHeaders(
                    206,
                    listOf(
                        "Content-Type" to contentType,
                        "Content-Length" to length.toString(),
                        "Content-Range" to "bytes ${outcome.start}-${outcome.end}/$total",
                        "Accept-Ranges" to "bytes",
                        "Content-Disposition" to disposition
                    )
                )
                if (!isHead) streamRange(file, outcome.start, length)
            }

            RangeOutcome.Unsatisfiable -> {
                keepAlive = false
                writeHeaders(
                    416,
                    listOf(
                        "Content-Type" to "text/plain; charset=utf-8",
                        "Content-Length" to "0",
                        "Content-Range" to "bytes */$total",
                        "Accept-Ranges" to "bytes"
                    )
                )
            }

            RangeOutcome.None -> {
                writeHeaders(
                    200,
                    listOf(
                        "Content-Type" to contentType,
                        "Content-Length" to total.toString(),
                        "Accept-Ranges" to "bytes",
                        "Content-Disposition" to disposition
                    )
                )
                if (!isHead) streamRange(file, 0L, total)
            }
        }
    }

    private fun streamRange(file: File, offset: Long, length: Long) {
        val buffer = ByteArray(128 * 1024)
        FileInputStream(file).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val step = input.skip(offset - skipped)
                if (step <= 0) break
                skipped += step
            }
            var remaining = length
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        output.flush()
        committed = true
    }

    private sealed class RangeOutcome {
        object None : RangeOutcome()
        object Unsatisfiable : RangeOutcome()
        data class Partial(val start: Long, val end: Long) : RangeOutcome()
    }

    private fun parseRange(header: String?, size: Long): RangeOutcome {
        if (header.isNullOrBlank()) return RangeOutcome.None
        val value = header.trim().lowercase()
        if (!value.startsWith("bytes=")) return RangeOutcome.None
        val spec = value.removePrefix("bytes=").trim()
        // 多段 Range 不处理，直接返回完整内容
        if (spec.contains(',')) return RangeOutcome.None
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeOutcome.None
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return try {
            if (startText.isEmpty()) {
                // bytes=-500 结尾 500 字节
                val suffix = endText.toLong()
                if (suffix <= 0) return RangeOutcome.Unsatisfiable
                val start = if (suffix >= size) 0L else size - suffix
                RangeOutcome.Partial(start, size - 1)
            } else {
                val start = startText.toLong()
                if (start >= size) return RangeOutcome.Unsatisfiable
                val end = if (endText.isEmpty()) size - 1 else minOf(endText.toLong(), size - 1)
                if (end < start) return RangeOutcome.Unsatisfiable
                RangeOutcome.Partial(start, end)
            }
        } catch (e: NumberFormatException) {
            RangeOutcome.None
        }
    }

    // ------------------------------------------------------------- 内部

    fun finish() {
        output.flush()
    }

    fun close() {
        runCatching { output.flush() }
        runCatching { socket.close() }
    }

    /** 丢弃请求体中尚未读取的数据，保证 keep-alive 连接的下一个请求能正确解析。 */
    fun drainRequestBody() {
        val body = request.body ?: return
        if (body.remaining() == 0L) return
        runCatching { body.drain() }
    }

    private fun writeHeaders(status: Int, headers: List<Pair<String, String>>) {
        statusCode = status
        val builder = StringBuilder(320)
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        builder.append("Date: ").append(httpDate()).append("\r\n")
        builder.append("Server: ").append(serverName).append("\r\n")
        for ((name, value) in headers) {
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        if (!headers.any { it.first.equals("X-Content-Type-Options", ignoreCase = true) }) {
            builder.append("X-Content-Type-Options: nosniff\r\n")
        }
        builder.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        committed = true
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    companion object {

        private val HTTP_DATE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        fun httpDate(): String = synchronized(HTTP_DATE_FORMAT) {
            HTTP_DATE_FORMAT.format(Date())
        }

        /**
         * RFC 6266 的 Content-Disposition：同时给出 ASCII 回退名与 UTF-8 文件名，
         * 保证中文文件名在任何浏览器里都能正确保存。
         */
        fun contentDisposition(name: String, attachment: Boolean): String {
            val fallback = buildString(name.length) {
                for (ch in name) {
                    append(if (ch.code in 32..126 && ch != '"' && ch != '\\' && ch != ';') ch else '_')
                }
            }.ifEmpty { "download" }
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val type = if (attachment) "attachment" else "inline"
            return "$type; filename=\"$fallback\"; filename*=UTF-8''$encoded"
        }
    }
}