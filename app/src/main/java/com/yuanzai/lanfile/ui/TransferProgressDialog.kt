package com.yuanzai.lanfile.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.TransferCallback

/**
 * 传输进度弹窗（复制 / 移动 / 导入文件）。
 * 自身实现 [TransferCallback]，可直接传给文件仓库在后台线程回调，
 * 内部自动切回主线程刷新 UI，并统计速度与剩余时间。
 */
class TransferProgressDialog(
    private val context: Context,
    private val title: String
) : TransferCallback {

    private val handler = Handler(Looper.getMainLooper())
    private val content: View = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
    private val titleView: TextView = content.findViewById(R.id.tv_progress_title)
    private val fileView: TextView = content.findViewById(R.id.tv_progress_file)
    private val statsView: TextView = content.findViewById(R.id.tv_progress_stats)
    private val indicator: LinearProgressIndicator = content.findViewById(R.id.progress_transfer)

    @Volatile
    private var cancelled = false

    private var dialog: AlertDialog? = null
    private var lastTime = 0L
    private var lastBytes = 0L
    private var speed = 0.0

    fun show() {
        if (dialog != null) return
        titleView.text = title
        fileView.text = "准备中…"
        statsView.text = ""
        indicator.isIndeterminate = true
        val created = MaterialAlertDialogBuilder(context)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                cancelled = true
                fileView.text = "正在取消…"
            }
            .create()
        dialog = created
        created.show()
    }

    fun dismissSafely() {
        handler.post {
            runCatching { dialog?.dismiss() }
            dialog = null
        }
    }

    override fun isCancelled(): Boolean = cancelled

    override fun onProgress(
        name: String,
        copied: Long,
        total: Long,
        fileIndex: Int,
        fileCount: Int,
        currentFileBytes: Long,
        currentFileSize: Long
    ) {
        // 计算速度（基于两次回调之间的字节差）
        val now = System.currentTimeMillis()
        if (lastTime > 0 && now > lastTime) {
            val deltaBytes = copied - lastBytes
            val deltaSeconds = (now - lastTime) / 1000.0
            if (deltaSeconds > 0 && deltaBytes >= 0) {
                val instant = deltaBytes / deltaSeconds
                speed = if (speed <= 0) instant else speed * 0.6 + instant * 0.4
            }
        }
        lastTime = now
        lastBytes = copied

        handler.post {
            val active = dialog ?: return@post
            if (!active.isShowing) return@post
            fileView.text = if (fileCount > 1) "第 $fileIndex/$fileCount 个：$name" else name
            if (total > 0) {
                indicator.isIndeterminate = false
                val progress = ((copied.toDouble() / total) * 1000).toInt().coerceIn(0, 1000)
                indicator.setProgressCompat(progress, true)
                val percent = progress / 10.0
                val remaining = total - copied
                val eta = if (speed > 0 && remaining > 0) remaining / speed else 0.0
                statsView.text = buildString {
                    append(FormatUtils.formatSize(copied))
                    append(" / ").append(FormatUtils.formatSize(total))
                    append("  ").append(String.format(java.util.Locale.US, "%.1f%%", percent))
                    if (speed > 0) {
                        append("  ").append(FormatUtils.formatSpeed(speed))
                        if (eta > 0) append("  剩余 ").append(FormatUtils.formatEta(eta))
                    }
                }
            } else {
                indicator.isIndeterminate = true
                statsView.text = "已处理 " + FormatUtils.formatSize(copied)
                if (currentFileSize > 0) {
                    statsView.text = statsView.text.toString() + "（当前文件 " +
                        FormatUtils.formatSize(currentFileBytes) + " / " + FormatUtils.formatSize(currentFileSize) + "）"
                }
            }
        }
    }
}