package com.yuanzai.lanfile.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * 存储根目录管理 + 路径安全校验。
 *
 * 这是手机端和网页端共用的唯一数据源：两端的所有操作都经过这里解析成真实文件路径，
 * 因此任何一端的新增/删除都会立刻被另一端看到。
 *
 * 目录策略（现代存储 API）：
 *  - 优先使用「内部存储/局域网文件」（Android 11+ 需要 MANAGE_EXTERNAL_STORAGE / 所有文件访问权限）；
 *  - 若没有该权限或目录不可写，自动回退到应用专属外部目录
 *    （Android/data/<包名>/files/局域网文件），无需任何权限即可读写。
 */
object StorageManager {

    const val ROOT_NAME = "局域网文件"
    val SUB_DIRS = listOf("图片", "视频", "文档", "下载", "其他")

    private lateinit var appContext: Context
    lateinit var settings: SettingsStore
        private set

    @Volatile
    private var cachedRoot: File? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        settings = SettingsStore(appContext)
        cachedRoot = null
    }

    /** 公共目录：内部存储/局域网文件。 */
    @Suppress("DEPRECATION")
    fun publicRoot(): File = File(Environment.getExternalStorageDirectory(), ROOT_NAME)

    /** 回退目录：应用专属外部目录/局域网文件（不需要权限）。 */
    fun appPrivateRoot(): File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(base, ROOT_NAME)
    }

    /** 当前生效的根目录。 */
    fun root(): File {
        cachedRoot?.let { return it }
        synchronized(this) {
            cachedRoot?.let { return it }
            val computed = computeRoot()
            cachedRoot = computed
            return computed
        }
    }

    /** 设置变化（切换目录、获得权限）后调用，重新决定根目录。 */
    fun refreshRoot() {
        synchronized(this) { cachedRoot = null }
    }

    private fun computeRoot(): File {
        if (!settings.usePublicDir) {
            val dir = appPrivateRoot()
            runCatching { dir.mkdirs() }
            return dir
        }
        val pub = publicRoot()
        if (isUsable(pub)) return pub
        if (runCatching { pub.mkdirs() && pub.canWrite() }.getOrDefault(false)) return pub
        val fallback = appPrivateRoot()
        runCatching { fallback.mkdirs() }
        return fallback
    }

    private fun isUsable(dir: File): Boolean =
        runCatching { dir.isDirectory && dir.canWrite() }.getOrDefault(false)

    /** 当前是否真的在使用公共目录（否则 UI 需要提示用户授权）。 */
    fun isUsingPublicDir(): Boolean {
        val a = runCatching { root().canonicalPath }.getOrNull() ?: return false
        val b = runCatching { publicRoot().canonicalPath }.getOrNull() ?: return false
        return a == b
    }

    /** 是否已获得“所有文件访问权限”。 */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 跳转到“所有文件访问权限”设置页；Android 10 及以下返回 null（改用运行时权限）。 */
    fun allFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        )
        return if (appIntent.resolveActivity(appContext.packageManager) != null) {
            appIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /**
     * 创建根目录及初始子目录。
     * @return 出错时返回中文提示，成功返回 null。
     */
    fun ensureInitialized(): String? {
        return try {
            val r = root()
            if (!r.exists() && !r.mkdirs()) return "无法创建目录：${r.absolutePath}"
            if (!r.canWrite()) return "目录不可写：${r.absolutePath}"
            for (name in SUB_DIRS) {
                val dir = File(r, name)
                if (!dir.exists()) dir.mkdirs()
            }
            null
        } catch (e: Exception) {
            "初始化目录失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 根目录是否存在且可写。 */
    fun isRootWritable(): Boolean = isUsable(root())

    /**
     * 把「相对路径」解析为真实文件。
     *
     * 安全规则（防止 ../ 越权）：
     *  1. 统一分隔符，去掉空段和 "."；
     *  2. 出现 ".." 直接拒绝；
     *  3. 段内不允许再出现分隔符、NUL；
     *  4. 最后用 canonicalPath 校验结果必须位于根目录之内（同时挡住符号链接跳转）。
     *
     * @return 合法时返回 File（可能尚不存在），非法时返回 null。
     */
    fun resolve(relPath: String?): File? {
        val raw = (relPath ?: "/").replace('\\', '/')
        if (raw.contains('\u0000')) return null
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        val rootFile = root()
        var current = rootFile
        for (segment in segments) {
            if (segment.contains('/') || segment.contains('\\')) return null
            current = File(current, segment)
        }
        val canonical = try {
            current.canonicalFile
        } catch (e: IOException) {
            return null
        }
        val rootCanonical = try {
            rootFile.canonicalFile
        } catch (e: IOException) {
            return null
        }
        val rootPath = rootCanonical.path
        return if (canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)) {
            canonical
        } else {
            null
        }
    }

    /** 同 [resolve]，但要求目标必须存在；否则抛出 [OpException]。 */
    fun resolveExisting(relPath: String?): File {
        val file = resolve(relPath) ?: throw OpException(OpException.FORBIDDEN, "路径不合法")
        if (!file.exists()) throw OpException(OpException.NOT_FOUND, "文件或文件夹不存在：${relPath ?: "/"}")
        return file
    }

    /** 同 [resolve]，但要求目标必须是目录。 */
    fun resolveDir(relPath: String?): File {
        val file = resolveExisting(relPath)
        if (!file.isDirectory) throw OpException(OpException.BAD_REQUEST, "不是文件夹：${relPath ?: "/"}")
        return file
    }

    /** 真实文件 -> 相对路径（以 "/" 开头）。不在根目录内时返回 null。 */
    fun relative(file: File): String? {
        val rootPath = runCatching { root().canonicalPath }.getOrNull() ?: return null
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (path == rootPath) return "/"
        if (!path.startsWith(rootPath + File.separator)) return null
        return "/" + path.substring(rootPath.length + 1).replace(File.separatorChar, '/')
    }

    /** 规范化相对路径（用于显示）：保证以 "/" 开头、无重复斜杠。 */
    fun normalize(relPath: String?): String {
        val raw = (relPath ?: "/").replace('\\', '/')
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    /** 父目录相对路径，根目录返回 null。 */
    fun parentOf(relPath: String?): String? {
        val normalized = normalize(relPath)
        if (normalized == "/") return null
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "/" else normalized.substring(0, idx)
    }

    /** 路径最后一段的名字，根目录返回根目录名。 */
    fun nameOf(relPath: String?): String {
        val normalized = normalize(relPath)
        if (normalized == "/") return ROOT_NAME
        return normalized.substringAfterLast('/')
    }

    /** 存储容量：total / free（字节）。 */
    @Suppress("DEPRECATION")
    fun volumeStats(): Pair<Long, Long> {
        return try {
            val stat = StatFs(root().absolutePath)
            stat.totalBytes to stat.availableBytes
        } catch (e: Exception) {
            0L to 0L
        }
    }

    /** 绝对路径（用于文件详情展示）。 */
    fun absoluteDisplay(relPath: String?): String {
        val file = resolve(relPath)
        return file?.absolutePath ?: root().absolutePath
    }
}