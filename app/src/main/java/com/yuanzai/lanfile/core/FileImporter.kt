package com.yuanzai.lanfile.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 手机端「添加文件」：把系统文件选择器返回的 content:// 数据复制到当前目录。
 * 全程流式拷贝并回调进度，支持取消；单个文件失败不会影响其他文件。
 */
object FileImporter {

    fun importUris(
        context: Context,
        uris: List<Uri>,
        destDir: File,
        callback: TransferCallback? = null
    ): BatchResult {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw OpException(OpException.INTERNAL, "目标文件夹不可用：${destDir.absolutePath}")
        }
        val names = ArrayList<String>(uris.size)
        val sizes = LongArray(uris.size)
        for (index in uris.indices) {
            val uri = uris[index]
            names.add(queryName(context, uri) ?: "未命名文件")
            sizes[index] = querySize(context, uri)
        }
        val totalKnown = sizes.filter { it > 0 }.sum()
        val allKnown = sizes.all { it > 0 }
        val total = if (allKnown) totalKnown else Math.max(totalKnown, 0L)

        var copiedTotal = 0L
        var succeeded = 0
        val errors = ArrayList<String>()
        val buffer = ByteArray(FileRepository.COPY_BUFFER)

        for (index in uris.indices) {
            val uri = uris[index]
            val displayName = names[index]
            val expected = sizes[index]
            val target = FileRepository.uniqueTarget(destDir, FileRepository.safeFileName(displayName))
            var copiedThis = 0L
            var input: InputStream? = null
            var output: FileOutputStream? = null
            try {
                input = context.contentResolver.openInputStream(uri)
                    ?: throw OpException(OpException.BAD_REQUEST, "无法读取该文件")
                output = FileOutputStream(target)
                while (true) {
                    if (callback?.isCancelled() == true) throw OperationCancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copiedThis += read
                    copiedTotal += read
                    callback?.onProgress(
                        displayName,
                        copiedTotal,
                        total,
                        index + 1,
                        uris.size,
                        copiedThis,
                        expected
                    )
                }
                output.flush()
                if (expected > 0 && target.length() != expected) {
                    throw OpException(OpException.INTERNAL, "文件不完整（${target.length()} / $expected 字节）")
                }
                succeeded++
            } catch (e: OperationCancelledException) {
                runCatching { target.delete() }
                throw e
            } catch (e: Exception) {
                runCatching { target.delete() }
                errors.add("$displayName：${e.message ?: "复制失败"}")
            } finally {
                runCatching { input?.close() }
                runCatching { output?.close() }
            }
        }
        return BatchResult(succeeded, errors)
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }
}