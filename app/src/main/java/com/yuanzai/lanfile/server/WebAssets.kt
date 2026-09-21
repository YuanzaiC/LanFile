package com.yuanzai.lanfile.server

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 网页端静态资源（打包在 assets/web/ 下）。
 * 文件很小，第一次读取后缓存到内存，避免每次请求都走 AssetManager。
 */
internal class WebAssets(private val context: Context) {

    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun read(name: String): ByteArray? {
        val safe = sanitize(name) ?: return null
        cache[safe]?.let { return it }
        val bytes = try {
            context.assets.open("web/$safe").use { input ->
                val out = ByteArrayOutputStream(64 * 1024)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            return null
        }
        cache[safe] = bytes
        return bytes
    }

    private fun sanitize(name: String): String? {
        val trimmed = name.trimStart('/')
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("..")) return null
        if (trimmed.contains('\\')) return null
        return trimmed
    }

    fun contentType(name: String): String = when {
        name.endsWith(".html", true) -> "text/html; charset=utf-8"
        name.endsWith(".css", true) -> "text/css; charset=utf-8"
        name.endsWith(".js", true) -> "application/javascript; charset=utf-8"
        name.endsWith(".webmanifest", true) -> "application/manifest+json; charset=utf-8"
        name.endsWith(".json", true) -> "application/json; charset=utf-8"
        name.endsWith(".svg", true) -> "image/svg+xml"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".ico", true) -> "image/x-icon"
        else -> "application/octet-stream"
    }
}