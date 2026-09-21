package com.yuanzai.lanfile.core

import com.yuanzai.lanfile.model.FileEntry
import com.yuanzai.lanfile.model.FileKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * 文件仓库：手机端与网页端共用的唯一文件操作入口。
 * 所有路径都会经过 [StorageManager.resolve] 校验，越界路径直接抛 [OpException]。
 */
object FileRepository {

    const val COPY_BUFFER = 128 * 1024

    // ---------------------------------------------------------------- 查询

    fun toEntry(file: File, relPath: String): FileEntry {
        val isDir = file.isDirectory
        val name = file.name
        val kind = FileTypes.kindOf(name, isDir)
        return FileEntry(
            name = name,
            path = relPath,
            isDir = isDir,
            size = if (isDir) 0L else file.length(),
            modified = file.lastModified(),
            kind = kind,
            ext = if (isDir) "" else FileTypes.extensionOf(name),
            mime = FileTypes.mimeOf(name, isDir)
        )
    }

    /** 列出目录内容（文件夹在前，然后按名称不区分大小写排序）。 */
    fun list(relPath: String?): List<FileEntry> {
        val dir = StorageManager.resolveDir(relPath)
        if (!dir.canRead()) throw OpException(OpException.FORBIDDEN, "没有权限读取该文件夹")
        val children = dir.listFiles() ?: return emptyList()
        val result = ArrayList<FileEntry>(children.size)
        for (child in children) {
            // relative() 同时充当符号链接越界保护：不在根目录内的条目不展示
            val rel = StorageManager.relative(child) ?: continue
            result.add(toEntry(child, rel))
        }
        return sort(result, SettingsStore.SORT_NAME, true)
    }

    fun stat(relPath: String?): FileEntry {
        val file = StorageManager.resolveExisting(relPath)
        val rel = StorageManager.relative(file)
            ?: throw OpException(OpException.FORBIDDEN, "路径不合法")
        return toEntry(file, rel)
    }

    data class SearchResult(val entries: List<FileEntry>, val truncated: Boolean)

    /** 递归搜索文件名 / 文件夹名。 */
    fun search(query: String, scopeRel: String?, limit: Int = 500): SearchResult {
        val keyword = query.trim()
        if (keyword.isEmpty()) return SearchResult(emptyList(), false)
        val scope = StorageManager.resolveDir(scopeRel)
        val results = ArrayList<FileEntry>()
        var truncated = false

        fun walk(dir: File, depth: Int) {
            if (truncated || depth > 32) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                val rel = StorageManager.relative(child) ?: continue
                if (child.name.contains(keyword, ignoreCase = true)) {
                    if (results.size >= limit) {
                        truncated = true
                        return
                    }
                    results.add(toEntry(child, rel))
                }
                if (child.isDirectory) walk(child, depth + 1)
                if (truncated) return
            }
        }

        walk(scope, 0)
        return SearchResult(sort(results, SettingsStore.SORT_NAME, true), truncated)
    }

    fun sort(entries: List<FileEntry>, mode: String, ascending: Boolean): List<FileEntry> {
        val base: Comparator<FileEntry> = when (mode) {
            SettingsStore.SORT_SIZE -> compareBy { it.size }
            SettingsStore.SORT_TIME -> compareBy { it.modified }
            SettingsStore.SORT_TYPE -> compareBy({ it.kind.ordinal }, { it.name.lowercase(Locale.ROOT) })
            else -> compareBy { it.name.lowercase(Locale.ROOT) }
        }
        val comparator = if (ascending) base else base.reversed()
        val dirFirst = compareByDescending<FileEntry> { it.isDir }
        return entries.sortedWith(dirFirst.then(comparator))
    }

    // ---------------------------------------------------------------- 名称

    /** 用户输入的文件夹名 / 新文件名：非法时抛异常。 */
    fun sanitizeName(raw: String): String {
        val cleaned = cleanName(raw)
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            throw OpException(OpException.BAD_REQUEST, "名称不合法")
        }
        if (cleaned.length > 200) {
            throw OpException(OpException.BAD_REQUEST, "名称过长（最多 200 个字符）")
        }
        return cleaned
    }

    /** 上传文件时使用的宽松版本：始终返回可用名称。 */
    fun safeFileName(raw: String?): String {
        val cleaned = cleanName(raw ?: "")
        return when {
            cleaned.isEmpty() -> "未命名文件"
            cleaned == "." || cleaned == ".." -> "未命名文件"
            cleaned.length > 200 -> cleaned.substring(0, 200)
            else -> cleaned
        }
    }

    private fun cleanName(raw: String): String {
        val replaced = raw.replace('/', '_').replace('\\', '_')
        val filtered = buildString(replaced.length) {
            for (ch in replaced) {
                val illegal = ch.code < 32 || ch in "\\/:*?\"<>|"
                if (!illegal) append(ch)
            }
        }
        return filtered.trim().trimEnd('.', ' ')
    }

    /** 在 dir 中为 name 找一个不冲突的路径：name (1).ext、name (2).ext ... */
    fun uniqueTarget(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (index in 1..9999) {
            val next = File(dir, "$base ($index)$ext")
            if (!next.exists()) return next
        }
        return File(dir, "$base (${System.currentTimeMillis()})$ext")
    }

    // ---------------------------------------------------------------- 写操作

    fun mkdir(parentRel: String?, rawName: String): FileEntry {
        val parent = StorageManager.resolveDir(parentRel)
        if (!parent.canWrite()) throw OpException(OpException.FORBIDDEN, "目标文件夹不可写")
        val name = sanitizeName(rawName)
        val target = File(parent, name)
        if (target.exists()) throw OpException(OpException.CONFLICT, "已存在同名文件或文件夹")
        if (!target.mkdirs()) throw OpException(OpException.INTERNAL, "新建文件夹失败")
        val rel = StorageManager.relative(target)
            ?: throw OpException(OpException.FORBIDDEN, "路径不合法")
        return toEntry(target, rel)
    }

    fun rename(relPath: String?, rawName: String): FileEntry {
        val src = StorageManager.resolveExisting(relPath)
        if (StorageManager.relative(src) == "/") {
            throw OpException(OpException.BAD_REQUEST, "不能重命名根目录")
        }
        val name = sanitizeName(rawName)
        val parent = src.parentFile ?: throw OpException(OpException.INTERNAL, "无法定位父目录")
        val target = File(parent, name)
        if (target.canonicalPath == src.canonicalPath) return toEntry(src, StorageManager.normalize(relPath))
        if (target.exists()) throw OpException(OpException.CONFLICT, "已存在同名文件或文件夹")
        if (!src.renameTo(target)) throw OpException(OpException.INTERNAL, "重命名失败")
        val rel = StorageManager.relative(target)
            ?: throw OpException(OpException.FORBIDDEN, "路径不合法")
        return toEntry(target, rel)
    }

    /** 删除多个路径（文件夹递归删除），返回成功数量与失败原因。 */
    fun delete(paths: List<String>): BatchResult {
        var deleted = 0
        val errors = ArrayList<String>()
        for (path in paths) {
            try {
                val file = StorageManager.resolve(path)
                    ?: throw OpException(OpException.FORBIDDEN, "路径不合法")
                if (StorageManager.relative(file) == "/") {
                    errors.add("不能删除根目录")
                    continue
                }
                if (!file.exists()) {
                    errors.add("${StorageManager.nameOf(path)}：不存在")
                    continue
                }
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) deleted++ else errors.add("${file.name}：删除失败（可能被占用）")
            } catch (e: OpException) {
                errors.add(e.message ?: "删除失败")
            } catch (e: Exception) {
                errors.add("${StorageManager.nameOf(path)}：${e.message ?: "删除失败"}")
            }
        }
        return BatchResult(deleted, errors)
    }

    /** 复制或移动多个路径到 destRel。 */
    fun transfer(
        paths: List<String>,
        destRel: String?,
        move: Boolean,
        callback: TransferCallback? = null
    ): BatchResult {
        val destDir = StorageManager.resolveDir(destRel)
        if (!destDir.canWrite()) throw OpException(OpException.FORBIDDEN, "目标文件夹不可写")

        val sources = ArrayList<File>(paths.size)
        val errors = ArrayList<String>()
        for (path in paths) {
            try {
                val file = StorageManager.resolve(path)
                    ?: throw OpException(OpException.FORBIDDEN, "路径不合法")
                if (!file.exists()) throw OpException(OpException.NOT_FOUND, "不存在")
                if (StorageManager.relative(file) == "/") throw OpException(OpException.BAD_REQUEST, "不能操作根目录")
                if (file.isDirectory && isSameOrAncestor(file, destDir)) {
                    throw OpException(OpException.BAD_REQUEST, "不能移动到自身或其子目录")
                }
                // 文件已经在目标目录里时，移动没有意义（否则会被误重命名成 “名称 (1).ext”）
                if (move && file.isFile && isSameDirectory(file.parentFile, destDir)) {
                    throw OpException(OpException.BAD_REQUEST, "已在目标目录中，无需移动")
                }
                sources.add(file)
            } catch (e: OpException) {
                errors.add("${StorageManager.nameOf(path)}：${e.message}")
            } catch (e: Exception) {
                errors.add("${StorageManager.nameOf(path)}：${e.message ?: "无法访问"}")
            }
        }
        if (sources.isEmpty()) return BatchResult(0, errors)

        val sizes = LongArray(sources.size)
        var total = 0L
        for (index in sources.indices) {
            sizes[index] = sizeOf(sources[index])
            total += sizes[index]
        }
        val counter = ProgressCounter(total, sources.size, callback)

        var succeeded = 0
        for (index in sources.indices) {
            val src = sources[index]
            val srcSize = sizes[index]
            counter.startItem(src.name, srcSize)
            try {
                val target = uniqueTarget(destDir, src.name)
                if (move) {
                    if (!moveFile(src, target, counter, srcSize)) {
                        copyFile(src, target, counter)
                        if (!deleteRecursively(src)) {
                            errors.add("${src.name}：已复制但原文件删除失败")
                        }
                    }
                } else {
                    copyFile(src, target, counter)
                }
                succeeded++
            } catch (e: OperationCancelledException) {
                throw e
            } catch (e: Exception) {
                errors.add("${src.name}：${e.message ?: "操作失败"}")
            }
        }
        return BatchResult(succeeded, errors)
    }

    /** 把内容流写入目录（网页上传用），返回实际写入的文件。 */
    fun writeStream(
        dir: File,
        fileName: String?,
        input: InputStream,
        expectedSize: Long = -1L
    ): File {
        val safeName = safeFileName(fileName)
        val target = uniqueTarget(dir, safeName)
        val buffer = ByteArray(COPY_BUFFER)
        try {
            FileOutputStream(target).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (e: IOException) {
            runCatching { target.delete() }
            throw OpException(OpException.INTERNAL, "写入文件失败：${e.message ?: "IO 错误"}")
        }
        if (expectedSize >= 0 && target.length() != expectedSize) {
            runCatching { target.delete() }
            throw OpException(OpException.INTERNAL, "文件不完整（已接收 ${target.length()} / $expectedSize 字节）")
        }
        return target
    }

    // ---------------------------------------------------------------- 内部实现

    private fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        var sum = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) sum += sizeOf(child)
        return sum
    }

    private fun isSameOrAncestor(parent: File, child: File): Boolean {
        val parentPath = runCatching { parent.canonicalPath }.getOrNull() ?: return false
        val childPath = runCatching { child.canonicalPath }.getOrNull() ?: return false
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }

    private fun isSameDirectory(a: File?, b: File): Boolean {
        if (a == null) return false
        val pathA = runCatching { a.canonicalPath }.getOrNull() ?: return false
        val pathB = runCatching { b.canonicalPath }.getOrNull() ?: return false
        return pathA == pathB
    }

    private fun deleteRecursively(file: File): Boolean =
        if (file.isDirectory) file.deleteRecursively() else file.delete()

    /** 同一分区直接 rename（瞬间完成），跨分区失败时返回 false 由调用方回退为复制。 */
    private fun moveFile(src: File, target: File, counter: ProgressCounter, size: Long): Boolean {
        val ok = runCatching { src.renameTo(target) }.getOrDefault(false)
        if (ok) counter.skipFile(src.name, size)
        return ok
    }

    private fun copyFile(src: File, target: File, counter: ProgressCounter) {
        if (src.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw OpException(OpException.INTERNAL, "无法创建目录 ${target.name}")
            }
            val children = src.listFiles() ?: return
            for (child in children) copyFile(child, File(target, child.name), counter)
            target.setLastModified(src.lastModified())
            return
        }
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        FileInputStream(src).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    if (counter.isCancelled()) {
                        runCatching { output.close() }
                        runCatching { target.delete() }
                        throw OperationCancelledException()
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    counter.addBytes(read.toLong())
                }
                output.flush()
            }
        }
        target.setLastModified(src.lastModified())
    }

    /** 汇总进度并回调 UI（每 ~200ms 一次，避免刷爆主线程）。 */
    private class ProgressCounter(
        private val totalBytes: Long,
        private val fileCount: Int,
        private val callback: TransferCallback?
    ) {
        private var copied = 0L
        private var currentName = ""
        private var currentSize = 0L
        private var currentBytes = 0L
        private var fileIndex = 0
        private var lastNotify = 0L

        fun startItem(name: String, size: Long) {
            currentName = name
            currentSize = size
            currentBytes = 0L
            fileIndex++
            notify(true)
        }

        fun skipFile(name: String, size: Long) {
            copied += size
            currentName = name
            currentBytes = size
            notify(true)
        }

        fun addBytes(count: Long) {
            copied += count
            currentBytes += count
            notify(false)
        }

        fun isCancelled(): Boolean = callback?.isCancelled() ?: false

        private fun notify(force: Boolean) {
            val cb = callback ?: return
            val now = System.currentTimeMillis()
            if (!force && now - lastNotify < 200) return
            lastNotify = now
            cb.onProgress(currentName, copied, totalBytes, fileIndex, fileCount, currentBytes, currentSize)
        }
    }
}