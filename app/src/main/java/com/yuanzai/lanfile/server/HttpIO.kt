package com.yuanzai.lanfile.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 字节来源抽象：可以是 socket，也可以是已经解码过的请求体。 */
internal interface ByteSource {
    /** 读取若干字节，返回读取长度；-1 表示结束。 */
    fun read(dst: ByteArray, offset: Int, length: Int): Int
}

/** 直接读 socket。 */
internal class SocketByteSource(private val input: InputStream) : ByteSource {
    override fun read(dst: ByteArray, offset: Int, length: Int): Int = input.read(dst, offset, length)
}

/** 把已解码的请求体当成字节来源（multipart 解析用）。 */
internal class BodyByteSource(private val body: HttpBody) : ByteSource {
    override fun read(dst: ByteArray, offset: Int, length: Int): Int = body.read(dst, offset, length)
}

internal data class CopyResult(val bytes: Int, val finished: Boolean)

/**
 * 带缓冲的读取器。支持：
 *  - 读一行（HTTP 头部 / multipart 分片头）
 *  - 拷贝直到分隔符（multipart 分片内容）
 *  - 分段拷贝（把分片内容暴露成 InputStream）
 *
 * 注意：只在 start == end 时才会向底层读取，保证缓冲区内数据不会被丢。
 */
internal class BufferedSource(
    private val source: ByteSource,
    private val bufferSize: Int = 32 * 1024
) : ByteSource {

    private val buffer = ByteArray(bufferSize)
    private var start = 0
    private var end = 0

    private fun fill(): Boolean {
        start = 0
        end = 0
        val read = source.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        end = read
        return true
    }

    override fun read(dst: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (start == end && !fill()) return -1
        val count = minOf(end - start, length)
        System.arraycopy(buffer, start, dst, offset, count)
        start += count
        return count
    }

    /** 读一行（去掉结尾的 \r\n）。返回 null 表示流结束且无数据。 */
    fun readLine(maxLength: Int = 8192): String? {
        val builder = StringBuilder(128)
        var gotAny = false
        while (true) {
            if (start == end && !fill()) {
                return if (gotAny) builder.toString() else null
            }
            val value = buffer[start++].toInt() and 0xFF
            gotAny = true
            if (value == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') {
                    builder.setLength(builder.length - 1)
                }
                return builder.toString()
            }
            // HTTP 头部按 ISO-8859-1 解析：一个字节对应一个字符
            builder.append(value.toChar())
            if (builder.length > maxLength) throw IOException("请求头过长")
        }
    }

    fun readFully(dst: ByteArray, offset: Int, length: Int): Boolean {
        var got = 0
        while (got < length) {
            val read = read(dst, offset + got, length - got)
            if (read < 0) return false
            got += read
        }
        return true
    }

    /** 拷贝全部数据到 out（直到流结束）。 */
    fun copyTo(out: OutputStream) {
        val scratch = ByteArray(32 * 1024)
        while (true) {
            val read = read(scratch, 0, scratch.size)
            if (read < 0) return
            out.write(scratch, 0, read)
        }
    }

    /** 从当前缓冲中查找分隔符，返回绝对下标（相对 buffer），未找到返回 -1。 */
    private fun indexOf(needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty()) return from
        val limit = to - needle.size
        var index = from
        outer@ while (index <= limit) {
            if (buffer[index] == needle[0]) {
                var offset = 1
                while (offset < needle.size) {
                    if (buffer[index + offset] != needle[offset]) {
                        index++
                        continue@outer
                    }
                    offset++
                }
                return index
            }
            index++
        }
        return -1
    }

    /**
     * 拷贝数据到 out，遇到 [delim] 立即停止（[delim] 被消费但不写入 out）。
     * @return true 表示找到了分隔符；false 表示流提前结束。
     */
    fun copyUntil(delim: ByteArray, out: OutputStream?): Boolean {
        val keep = delim.size - 1
        while (true) {
            if (start == end && !fill()) return false
            val index = indexOf(delim, start, end)
            if (index >= 0) {
                val count = index - start
                if (count > 0) out?.write(buffer, start, count)
                start = index + delim.size
                return true
            }
            val available = end - start
            val writable = available - keep
            if (writable > 0) {
                out?.write(buffer, start, writable)
                start += writable
            }
            if (!refillKeepingBuffer()) return false
        }
    }

    /**
     * 分段拷贝：最多 [max] 字节写入 out[offset..]，遇到 [delim] 立即结束。
     */
    fun copySome(delim: ByteArray, out: ByteArray, offset: Int, max: Int): CopyResult {
        val keep = delim.size - 1
        var produced = 0
        while (produced < max) {
            if (start == end && !fill()) return CopyResult(produced, false)
            val index = indexOf(delim, start, end)
            if (index >= 0) {
                val count = minOf(index - start, max - produced)
                if (count > 0) {
                    System.arraycopy(buffer, start, out, offset + produced, count)
                    start += count
                    produced += count
                }
                if (start >= index) {
                    start = index + delim.size
                    return CopyResult(produced, true)
                }
                continue
            }
            val available = end - start
            val writable = minOf(available - keep, max - produced)
            if (writable > 0) {
                System.arraycopy(buffer, start, out, offset + produced, writable)
                start += writable
                produced += writable
            } else {
                if (!refillKeepingBuffer()) return CopyResult(produced, false)
            }
        }
        return CopyResult(produced, false)
    }

    /** 压缩缓冲并读取更多数据；返回 false 表示流结束。 */
    private fun refillKeepingBuffer(): Boolean {
        val remaining = end - start
        if (remaining > 0 && start > 0) {
            System.arraycopy(buffer, start, buffer, 0, remaining)
        }
        start = 0
        end = remaining
        if (end >= buffer.size) throw IOException("分隔符过长，无法继续解析")
        val read = source.read(buffer, end, buffer.size - end)
        if (read <= 0) return false
        end += read
        return true
    }
}

// ------------------------------------------------------------------ 请求体

/** 已解码的请求体（定长或 chunked）。 */
internal abstract class HttpBody {
    /** 声明的长度；-1 表示未知（chunked）。 */
    abstract val length: Long
    abstract val consumed: Long

    abstract fun read(dst: ByteArray, offset: Int, length: Int): Int

    fun remaining(): Long = if (length < 0) -1L else length - consumed

    fun drain() {
        val scratch = ByteArray(32 * 1024)
        while (read(scratch, 0, scratch.size) > 0) {
            // 丢弃
        }
    }

    /** 读取全部内容（超过 maxBytes 抛异常），用于 JSON / 文本请求体。 */
    fun readBytes(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val scratch = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(scratch, 0, scratch.size)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("请求体过大")
            out.write(scratch, 0, read)
        }
        return out.toByteArray()
    }
}

internal class FixedLengthBody(
    private val source: BufferedSource,
    override val length: Long
) : HttpBody() {

    private var readBytes = 0L
    override val consumed: Long get() = readBytes

    override fun read(dst: ByteArray, offset: Int, length: Int): Int {
        val left = this.length - readBytes
        if (left <= 0) return -1
        val want = minOf(length.toLong(), left).toInt()
        val read = source.read(dst, offset, want)
        if (read < 0) throw IOException("请求体提前结束（已接收 $readBytes / ${this.length} 字节）")
        readBytes += read
        return read
    }
}

internal class ChunkedBody(private val source: BufferedSource) : HttpBody() {

    override val length: Long = -1L
    private var readBytes = 0L
    private var chunkLeft = 0
    private var finished = false

    override val consumed: Long get() = readBytes

    override fun read(dst: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (chunkLeft == 0) {
            val line = source.readLine(512) ?: run {
                finished = true
                return -1
            }
            val sizeText = line.substringBefore(';').trim()
            val size = sizeText.toIntOrNull(16)
            if (size == null || size < 0) throw IOException("chunk 长度非法：$line")
            if (size == 0) {
                // 读取 trailer 直到空行
                while (true) {
                    val trailer = source.readLine(1024) ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return -1
            }
            chunkLeft = size
        }
        val want = minOf(length, chunkLeft)
        val read = source.read(dst, offset, want)
        if (read < 0) throw IOException("chunk 数据不完整")
        chunkLeft -= read
        readBytes += read
        if (chunkLeft == 0) source.readLine(8) // chunk 结尾的 CRLF
        return read
    }
}

// ------------------------------------------------------------------ multipart

internal enum class PartEnd { MORE, LAST, EOF }

/**
 * 流式 multipart/form-data 解析器：不把整个上传体读进内存，
 * 每个分片通过 [MultipartPart.read] / [MultipartPart.asInputStream] 边收边写磁盘。
 */
internal class MultipartParser(body: HttpBody, boundary: String) {

    private val reader = BufferedSource(BodyByteSource(body), 16 * 1024)
    private val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
    private val firstBoundary = "--$boundary"

    fun parse(onPart: (MultipartPart) -> Unit) {
        if (!skipPreamble()) return
        while (true) {
            val headers = readHeaders() ?: return
            val disposition = headers["content-disposition"]
            val params = parseDisposition(disposition)
            val part = MultipartPart(
                name = params["name"],
                fileName = extractFileName(params),
                contentType = headers["content-type"],
                reader = reader,
                delimiter = delimiter,
                declaredLength = headers["content-length"]?.toLongOrNull() ?: -1L
            )
            onPart(part)
            when (part.finish()) {
                PartEnd.MORE -> Unit
                PartEnd.LAST, PartEnd.EOF -> return
            }
        }
    }

    private fun skipPreamble(): Boolean {
        var guard = 0
        while (true) {
            val line = reader.readLine(4096) ?: return false
            val trimmed = line.trimEnd()
            if (trimmed.startsWith(firstBoundary)) {
                // 形如 --boundary-- 表示没有任何分片
                return !trimmed.endsWith("--")
            }
            if (++guard > 2000) return false
        }
    }

    private fun readHeaders(): Map<String, String>? {
        val headers = HashMap<String, String>(4)
        while (true) {
            val line = reader.readLine(8192) ?: return if (headers.isEmpty()) null else headers
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    /** 解析 Content-Disposition 参数（正确处理引号内的分号）。 */
    private fun parseDisposition(header: String?): Map<String, String> {
        val result = HashMap<String, String>(4)
        if (header.isNullOrEmpty()) return result
        val tokens = ArrayList<String>(4)
        val current = StringBuilder()
        var inQuotes = false
        for (ch in header) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ch == ';' && !inQuotes -> {
                    tokens.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        tokens.add(current.toString())
        for (token in tokens) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq).trim().lowercase()
            var value = token.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1).replace("\\\"", "\"")
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** 支持 filename= 与 RFC5987 的 filename*=UTF-8''... ；同时兼容老浏览器的完整路径。 */
    private fun extractFileName(params: Map<String, String>): String? {
        val extended = params["filename*"]
        if (!extended.isNullOrEmpty()) {
            val value = extended.substringAfter("''", extended)
            val decoded = runCatching { percentDecode(value) }.getOrDefault(value)
            return cleanUploadName(decoded)
        }
        val plain = params["filename"] ?: return null
        if (plain.isEmpty()) return null
        // 头部按 ISO-8859-1 读取，这里还原成 UTF-8 字节再解码，中文文件名才不会乱码
        val bytes = plain.toByteArray(Charsets.ISO_8859_1)
        val utf8 = String(bytes, Charsets.UTF_8)
        val best = if (utf8.contains('\uFFFD')) plain else utf8
        return cleanUploadName(best)
    }

    private fun cleanUploadName(raw: String): String {
        val withoutPath = raw.substringAfterLast('/').substringAfterLast('\\')
        return withoutPath.trim()
    }
}

/** 单个 multipart 分片。 */
internal class MultipartPart(
    val name: String?,
    val fileName: String?,
    val contentType: String?,
    private val reader: BufferedSource,
    private val delimiter: ByteArray,
    val declaredLength: Long
) {

    private var finished = false
    var bytesRead: Long = 0
        private set

    fun read(dst: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (length == 0) return 0
        val result = reader.copySome(delimiter, dst, offset, length)
        bytesRead += result.bytes
        if (result.finished) finished = true
        if (result.bytes == 0 && finished) return -1
        return result.bytes
    }

    /** 把分片内容当作 InputStream（用于边收边写磁盘）。 */
    fun asInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            val read = this@MultipartPart.read(single, 0, 1)
            return if (read <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = this@MultipartPart.read(b, off, len)
    }

    /** 读取纯文本分片（表单字段），最多 256KB。 */
    fun readText(maxBytes: Int = 256 * 1024): String {
        val out = ByteArrayOutputStream()
        val scratch = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(scratch, 0, scratch.size)
            if (read < 0) break
            total += read
            if (total > maxBytes) break
            out.write(scratch, 0, read)
        }
        drain()
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun drain() {
        if (finished) return
        val scratch = ByteArray(32 * 1024)
        while (read(scratch, 0, scratch.size) > 0) {
            // 丢弃
        }
    }

    /** 消费剩余内容与分隔符，返回分片结束后的状态。 */
    fun finish(): PartEnd {
        drain()
        val separator = reader.readLine(64) ?: return PartEnd.EOF
        return if (separator.startsWith("--")) PartEnd.LAST else PartEnd.MORE
    }
}

/** 只解码 %XX，不把 '+' 当成空格（用于路径与 RFC5987 编码值）。 */
internal fun percentDecode(raw: String): String {
    if (!raw.contains('%')) return raw
    val out = ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        if (ch == '%' && index + 2 < raw.length) {
            val value = raw.substring(index + 1, index + 3).toIntOrNull(16)
            if (value != null) {
                out.write(value)
                index += 3
                continue
            }
        }
        out.write(ch.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}