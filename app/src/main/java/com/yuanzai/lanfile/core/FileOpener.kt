package com.yuanzai.lanfile.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 调用系统应用打开 / 分享文件（通过 FileProvider，适配 Android 7.0+ 的文件 URI 限制）。
 */
object FileOpener {

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** 用系统应用打开单个文件。 */
    fun openFile(context: Context, file: File, mime: String) {
        val uri = try {
            uriFor(context, file)
        } catch (e: Exception) {
            Toast.makeText(context, "无法生成文件链接：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "选择打开方式"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "没有可以打开该文件的应用", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "打开失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    /** 分享一个或多个文件。 */
    fun shareFiles(context: Context, files: List<File>, mime: String) {
        if (files.isEmpty()) return
        val uris = ArrayList<Uri>(files.size)
        for (file in files) {
            try {
                uris.add(uriFor(context, file))
            } catch (e: Exception) {
                // 跳过无法分享的文件
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(context, "没有可分享的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (mime.isBlank()) "*/*" else mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, "分享到"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "没有可以接收分享的应用", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    /** 把文本分享/发送出去（用于分享访问地址）。 */
    fun shareText(context: Context, text: String, subject: String = "") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, "分享"))
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }
}