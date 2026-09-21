package com.yuanzai.lanfile.server

import android.content.Context
import android.os.Build
import com.yuanzai.lanfile.core.ClientRegistry
import com.yuanzai.lanfile.core.FileRepository
import com.yuanzai.lanfile.core.FileTypes
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.MessageRepository
import com.yuanzai.lanfile.core.OpException
import com.yuanzai.lanfile.core.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 路由分发：网页端 API + 静态资源。
 *
 * 所有涉及路径的接口都通过 [StorageManager] 做越权校验，
 * 网页端无法访问「局域网文件」目录之外的任何内容。
 */
internal class ApiRouter(
    private val context: Context,
    private val assets: WebAssets,
    private val portProvider: () -> Int
) {

    private val settings = com.yuanzai.lanfile.core.SettingsStore(context)

    /** 当前请求来自哪台设备（每个请求一条线程，用 ThreadLocal 传递最省事）。 */
    private val currentClient = ThreadLocal<com.yuanzai.lanfile.core.WebClient?>()

    fun handle(exchange: HttpExchange) {
        val path = exchange.request.path
        // 记一笔访问记录：设备列表、封禁、单设备授权都靠它
        val client = ClientRegistry.touch(exchange.request.clientIp, exchange.request.userAgent)
        currentClient.set(client)
        try {
            if (client?.blocked == true) {
                serveBlocked(exchange)
                return
            }
            when {
                path == "/" -> serveStatic(exchange, "index.html")
                path.startsWith("/api/") -> handleApi(exchange)
                path.startsWith("/static/") -> serveStatic(exchange, path.removePrefix("/static/"))
                !path.contains("/api") && isStaticName(path) -> serveStatic(exchange, path.trimStart('/'))
                else -> exchange.sendError(404, "页面不存在：$path")
            }
        } catch (e: OpException) {
            exchange.sendError(e.status, e.message ?: "操作失败")
        } catch (e: Throwable) {
            exchange.sendError(500, "服务器内部错误：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { exchange.drainRequestBody() }
            currentClient.remove()
        }
    }

    /** 已被封禁的设备：网页访问给一个正式的提示页面，接口请求给 JSON。 */
    private fun serveBlocked(exchange: HttpExchange) {
        val ip = exchange.request.clientIp
        if (exchange.request.path.startsWith("/api/")) {
            exchange.sendError(OpException.FORBIDDEN, "此设备已被禁止访问，请在手机端「设备」页面解除封禁")
            return
        }
        val title = settings.webTitle
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val html = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>访问已被禁止</title>
<style>
:root { color-scheme: light dark; }
body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px;
  font:16px/1.7 -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans SC", "Microsoft YaHei", sans-serif;
  background:#f3f5fb; color:#1f2330; }
.box { max-width:540px; width:100%; background:#fff; border:1px solid #e3e7f0; border-radius:18px;
  padding:28px 26px; box-shadow:0 12px 34px rgba(20,30,60,.08); }
.badge { display:inline-block; padding:4px 12px; border-radius:999px; background:rgba(198,40,40,.12);
  color:#c62828; font-size:13px; font-weight:600; }
h1 { margin:14px 0 10px; font-size:21px; }
p { margin:8px 0; color:#5b6478; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background:rgba(120,130,160,.14); padding:2px 8px; border-radius:6px; }
@media (prefers-color-scheme: dark) {
  body { background:#12151c; color:#e7eaf3; }
  .box { background:#1b1f29; border-color:#2a3040; }
  p { color:#9aa4bb; }
}
</style>
</head>
<body>
  <div class="box">
    <span class="badge">403 · 禁止访问</span>
    <h1>此设备已被禁止访问</h1>
    <p><b>$title</b> 的管理者已在手机端停用此设备的访问权限。</p>
    <p>设备地址：<code>$ip</code></p>
    <p>如需恢复访问，请在手机端「设备」页面解除封禁，然后刷新本页面。</p>
  </div>
</body>
</html>"""
        exchange.sendText(html, "text/html; charset=utf-8", OpException.FORBIDDEN, cacheControl = "no-store")
    }

    private fun isStaticName(path: String): Boolean {
        val name = path.trimStart('/')
        return name == "index.html" || name == "style.css" || name == "app.js" ||
            name == "manifest.webmanifest"
    }

    private fun serveStatic(exchange: HttpExchange, name: String) {
        val bytes = assets.read(name)
        if (bytes == null) {
            exchange.sendError(404, "资源不存在：$name")
            return
        }
        // 手机端可能随时更新网页资源，禁用缓存保证刷新即最新
        exchange.sendBytes(bytes, assets.contentType(name), 200, cacheControl = "no-cache")
    }

    // ------------------------------------------------------------- API 分发

    private fun handleApi(exchange: HttpExchange) {
        val api = exchange.request.path.removePrefix("/api/").trim('/').lowercase()
        when (api) {
            "info", "status" -> requireGet(exchange) { info(exchange) }
            "list" -> requireGet(exchange) { list(exchange) }
            "search" -> requireGet(exchange) { search(exchange) }
            "stat" -> requireGet(exchange) { stat(exchange) }
            "messages" -> requireGet(exchange) {
                if (textAllowed()) messages(exchange) else emptyMessages(exchange)
            }
            "download" -> requireGet(exchange) { download(exchange, attachment = true) }
            "preview" -> requireGet(exchange) { download(exchange, attachment = false) }
            "mkdir" -> requirePost(exchange) { requireModifyAllowed(); mkdir(exchange) }
            "rename" -> requirePost(exchange) { requireModifyAllowed(); rename(exchange) }
            "delete" -> requirePost(exchange) { requireDeleteAllowed(); delete(exchange) }
            "move" -> requirePost(exchange) { requireModifyAllowed(); transfer(exchange, move = true) }
            "copy" -> requirePost(exchange) { requireModifyAllowed(); transfer(exchange, move = false) }
            "upload" -> requirePost(exchange) { requireUploadAllowed(); upload(exchange) }
            "text" -> requirePost(exchange) { requireTextAllowed(); postText(exchange) }
            "messages/delete" -> requirePost(exchange) {
                requireTextAllowed()
                deleteMessages(exchange)
            }
            else -> exchange.sendError(404, "接口不存在：${exchange.request.path}")
        }
    }

    /** 手机端关掉文字互传后，网页端轮询消息拿到空列表即可，不要报错。 */
    private fun emptyMessages(exchange: HttpExchange) {
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("messages", JSONArray())
            put("total", 0)
        })
    }

    private inline fun requireGet(exchange: HttpExchange, block: () -> Unit) {
        requireMethod(exchange, "GET", block)
    }

    private inline fun requirePost(exchange: HttpExchange, block: () -> Unit) {
        requireMethod(exchange, "POST", block)
    }

    private inline fun requireMethod(exchange: HttpExchange, method: String, block: () -> Unit) {
        val actual = exchange.request.method
        // HEAD 按 GET 处理（只写头部，不写正文）
        val effective = if (actual == "HEAD") "GET" else actual
        if (effective == "OPTIONS") {
            exchange.sendNoContent(204)
            return
        }
        if (effective != method) {
            exchange.sendError(405, "该接口只支持 $method 请求")
            return
        }
        block()
    }

    // ------------------------------------------------------------- 基础信息

    private fun info(exchange: HttpExchange) {
        val (total, free) = StorageManager.volumeStats()
        val client = currentClient.get()
        val json = JSONObject().apply {
            put("ok", true)
            put("app", "LanFile")
            put("version", versionName())
            put("device", Build.MODEL ?: "Android")
            put("brand", Build.BRAND ?: "")
            put("root", StorageManager.root().absolutePath)
            put("rootName", StorageManager.ROOT_NAME)
            put("port", portProvider())
            put("time", System.currentTimeMillis())
            put("total", total)
            put("free", free)
            put("usingPublicDir", StorageManager.isUsingPublicDir())
            put("writable", StorageManager.isRootWritable())
            put("messageCount", if (textAllowed()) MessageRepository.count() else 0)
            // 网页端能力开关（全局设置在「设置 → 网页端」，也可以给单台设备单独授权）
            put("title", settings.webTitle)
            put("allowUpload", ClientRegistry.canUpload(client, settings))
            put("allowDelete", ClientRegistry.canDelete(client, settings))
            put("allowModify", ClientRegistry.canModify(client, settings))
            put("allowText", ClientRegistry.canText(client, settings))
            put("uploadLimitMb", settings.webUploadLimitMb)
            put("clientIp", exchange.request.clientIp)
        }
        exchange.sendJson(json)
    }

    // ------------------------------------------------------------- 网页端权限

    /** 该设备当前是否允许文字互传（单设备设置优先于全局设置）。 */
    private fun textAllowed(): Boolean = ClientRegistry.canText(currentClient.get(), settings)

    private fun requireUploadAllowed() {
        if (!ClientRegistry.canUpload(currentClient.get(), settings)) {
            throw OpException(OpException.FORBIDDEN, "此设备的上传权限已被关闭")
        }
    }

    private fun requireDeleteAllowed() {
        if (!ClientRegistry.canDelete(currentClient.get(), settings)) {
            throw OpException(OpException.FORBIDDEN, "此设备的删除权限已被关闭")
        }
    }

    private fun requireModifyAllowed() {
        if (!ClientRegistry.canModify(currentClient.get(), settings)) {
            throw OpException(
                OpException.FORBIDDEN,
                "此设备的重命名 / 新建 / 移动 / 复制权限已被关闭"
            )
        }
    }

    private fun requireTextAllowed() {
        if (!textAllowed()) throw OpException(OpException.FORBIDDEN, "此设备的文字互传权限已被关闭")
    }

    /** 上传上限保护：超过限制立刻中断，同时让 writeStream 删掉写了一半的文件。 */
    private class LimitedInputStream(
        private val source: java.io.InputStream,
        private val limit: Long
    ) : java.io.InputStream() {

        private var count = 0L

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) {
                count++
                checkLimit()
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                count += read
                checkLimit()
            }
            return read
        }

        private fun checkLimit() {
            if (limit > 0 && count > limit) {
                throw java.io.IOException("单个文件不能超过 ${FormatUtils.formatSize(limit)}")
            }
        }

        override fun available(): Int = source.available()

        override fun close() = source.close()
    }

    private fun versionName(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "1.0"
    }.getOrDefault("1.0")

    // ------------------------------------------------------------- 列表 / 搜索

    private fun list(exchange: HttpExchange) {
        val rel = exchange.request.queryParam("path") ?: "/"
        val entries = FileRepository.list(rel)
        val normalized = StorageManager.normalize(rel)
        var fileCount = 0
        var dirCount = 0
        val array = JSONArray()
        for (entry in entries) {
            if (entry.isDir) dirCount++ else fileCount++
            array.put(entry.toJson())
        }
        val parent = StorageManager.parentOf(normalized)
        val json = JSONObject().apply {
            put("ok", true)
            put("path", normalized)
            put("parent", parent ?: JSONObject.NULL)
            put("name", StorageManager.nameOf(normalized))
            put("entries", array)
            put("fileCount", fileCount)
            put("dirCount", dirCount)
        }
        exchange.sendJson(json)
    }

    private fun search(exchange: HttpExchange) {
        val query = exchange.request.queryParam("q") ?: ""
        val scope = exchange.request.queryParam("path") ?: "/"
        val limit = exchange.request.queryParam("limit")?.toIntOrNull()?.coerceIn(1, 2000) ?: 500
        val result = FileRepository.search(query, scope, limit)
        val array = JSONArray()
        for (entry in result.entries) array.put(entry.toJson())
        val json = JSONObject().apply {
            put("ok", true)
            put("query", query)
            put("entries", array)
            put("total", result.entries.size)
            put("truncated", result.truncated)
        }
        exchange.sendJson(json)
    }

    private fun stat(exchange: HttpExchange) {
        val rel = exchange.request.queryParam("path") ?: throw OpException(400, "缺少 path 参数")
        val entry = FileRepository.stat(rel)
        val json = JSONObject().apply {
            put("ok", true)
            put("entry", entry.toJson())
            put("absPath", StorageManager.absoluteDisplay(rel))
        }
        exchange.sendJson(json)
    }

    // ------------------------------------------------------------- 目录与文件操作

    private fun mkdir(exchange: HttpExchange) {
        val json = readJson(exchange)
        val parent = json.optString("path", "/")
        val name = json.optString("name")
        if (name.isBlank()) throw OpException(400, "缺少文件夹名称")
        val entry = FileRepository.mkdir(parent, name)
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("path", entry.path)
            put("entry", entry.toJson())
        })
    }

    private fun rename(exchange: HttpExchange) {
        val json = readJson(exchange)
        val path = json.optString("path")
        val newName = json.optString("newName").ifBlank { json.optString("name") }
        if (path.isBlank()) throw OpException(400, "缺少 path 参数")
        if (newName.isBlank()) throw OpException(400, "缺少新名称")
        val entry = FileRepository.rename(path, newName)
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("path", entry.path)
            put("entry", entry.toJson())
        })
    }

    private fun delete(exchange: HttpExchange) {
        val json = readJson(exchange)
        val paths = readPaths(json)
        if (paths.isEmpty()) throw OpException(400, "没有指定要删除的内容")
        val result = FileRepository.delete(paths)
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("deleted", result.succeeded)
            put("errors", JSONArray(result.errors))
        })
    }

    private fun transfer(exchange: HttpExchange, move: Boolean) {
        val json = readJson(exchange)
        val paths = readPaths(json)
        if (paths.isEmpty()) throw OpException(400, "没有指定要操作的内容")
        val dest = json.optString("dest").ifBlank { json.optString("destPath", "/") }
        val result = FileRepository.transfer(paths, dest, move)
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put(if (move) "moved" else "copied", result.succeeded)
            put("dest", StorageManager.normalize(dest))
            put("errors", JSONArray(result.errors))
        })
    }

    private fun readPaths(json: JSONObject): List<String> {
        val array = json.optJSONArray("paths")
        if (array != null) {
            val list = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val value = array.optString(index)
                if (value.isNotBlank()) list.add(value)
            }
            return list
        }
        val single = json.optString("path")
        return if (single.isBlank()) emptyList() else listOf(single)
    }

    // ------------------------------------------------------------- 上传

    private fun upload(exchange: HttpExchange) {
        val request = exchange.request
        val targetRel = request.queryParam("path") ?: request.queryParam("dir") ?: "/"
        val dir = StorageManager.resolveDir(targetRel)
        if (!dir.canWrite()) throw OpException(OpException.FORBIDDEN, "目标文件夹不可写")

        val body = request.body ?: throw OpException(400, "缺少上传内容")
        if (body.length == 0L) throw OpException(400, "上传内容为空")
        val boundary = request.boundary() ?: throw OpException(400, "不是 multipart/form-data 请求")

        val free = StorageManager.volumeStats().second
        if (free > 0 && body.length > free) {
            throw OpException(
                OpException.PAYLOAD_TOO_LARGE,
                "存储空间不足：需要 ${FormatUtils.formatSize(body.length)}，可用 ${FormatUtils.formatSize(free)}"
            )
        }

        val uploaded = JSONArray()
        val errors = JSONArray()
        // 手机端设置的单文件上限（0 = 不限）：超了就中断，写了一半的文件会被删掉
        val limitBytes = settings.webUploadLimitBytes()
        MultipartParser(body, boundary).parse { part ->
            val fileName = part.fileName
            if (fileName.isNullOrBlank()) {
                part.drain() // 普通表单字段，忽略
                return@parse
            }
            try {
                val source = part.asInputStream()
                val stream = if (limitBytes > 0) LimitedInputStream(source, limitBytes) else source
                val file = FileRepository.writeStream(dir, fileName, stream)
                val rel = StorageManager.relative(file) ?: StorageManager.normalize(targetRel)
                uploaded.put(JSONObject().apply {
                    put("name", file.name)
                    put("originalName", fileName)
                    put("path", rel)
                    put("size", file.length())
                })
            } catch (e: Exception) {
                errors.put("$fileName：${e.message ?: "上传失败"}")
            }
        }

        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("uploaded", uploaded)
            put("errors", errors)
            put("path", StorageManager.normalize(targetRel))
        })
    }

    // ------------------------------------------------------------- 下载 / 预览

    private fun download(exchange: HttpExchange, attachment: Boolean) {
        val rel = exchange.request.queryParam("path") ?: throw OpException(400, "缺少 path 参数")
        val file = StorageManager.resolveExisting(rel)
        if (file.isDirectory) throw OpException(400, "不能直接下载文件夹")
        if (!file.canRead()) throw OpException(OpException.FORBIDDEN, "没有读取权限")
        val mime = FileTypes.mimeOf(file.name, false)
        exchange.sendFile(file, mime, attachment, file.name)
    }

    // ------------------------------------------------------------- 文字消息

    private fun postText(exchange: HttpExchange) {
        val request = exchange.request
        val type = request.contentType()?.lowercase() ?: ""
        var text: String
        var source = "web"
        if (type.contains("json")) {
            val json = readJson(exchange)
            text = json.optString("text")
            source = json.optString("source", "web").ifBlank { "web" }
        } else {
            val raw = request.bodyText(512 * 1024)
            text = if (type.contains("form-urlencoded")) {
                val pair = raw.split('&').firstOrNull { it.startsWith("text=") }
                val value = pair?.substringAfter('=') ?: ""
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            } else {
                raw
            }
        }
        val deviceLabel = com.yuanzai.lanfile.core.WebClient.describeUserAgent(exchange.request.userAgent)
        val message = MessageRepository.add(text, source, deviceLabel)
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("message", message.toJson())
        })
    }

    private fun messages(exchange: HttpExchange) {
        val limit = exchange.request.queryParam("limit")?.toIntOrNull()?.coerceIn(1, MessageRepository.MAX_MESSAGES)
            ?: 200
        val since = exchange.request.queryParam("since")?.toLongOrNull() ?: 0L
        val all = MessageRepository.all(limit)
        val filtered = if (since > 0) all.filter { it.time > since } else all
        val array = JSONArray()
        for (message in filtered) array.put(message.toJson())
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("messages", array)
            put("total", filtered.size)
            put("serverTime", System.currentTimeMillis())
        })
    }

    private fun deleteMessages(exchange: HttpExchange) {
        val json = readJson(exchange)
        val deleted: Int
        if (json.optBoolean("all", false)) {
            deleted = MessageRepository.clear()
        } else {
            val array = json.optJSONArray("ids") ?: JSONArray()
            val ids = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val value = array.optString(index)
                if (value.isNotBlank()) ids.add(value)
            }
            if (ids.isEmpty()) throw OpException(400, "没有指定要删除的消息")
            deleted = MessageRepository.delete(ids)
        }
        exchange.sendJson(JSONObject().apply {
            put("ok", true)
            put("deleted", deleted)
        })
    }

    // ------------------------------------------------------------- 工具

    private fun readJson(exchange: HttpExchange): JSONObject {
        val type = exchange.request.contentType()?.lowercase() ?: ""
        if (type.isNotEmpty() && !type.contains("json")) {
            throw OpException(400, "请求需要 application/json（当前：$type）")
        }
        return exchange.request.jsonBody()
    }
}