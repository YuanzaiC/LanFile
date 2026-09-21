package com.yuanzai.lanfile.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.FileOpener
import com.yuanzai.lanfile.core.FileRepository
import com.yuanzai.lanfile.core.FileTypes
import com.yuanzai.lanfile.core.StorageManager
import com.yuanzai.lanfile.model.FileEntry
import com.yuanzai.lanfile.model.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 简单预览：图片直接显示，文本按文本显示，其它类型提示用系统应用打开。
 */
class PreviewActivity : AppCompatActivity() {

    private var entry: FileEntry? = null

    private var imageView: ImageView? = null
    private var textScroll: ScrollView? = null
    private var textView: TextView? = null
    private var unsupportedLayout: View? = null
    private var messageView: TextView? = null
    private var progress: ProgressBar? = null
    private var titleView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        val root = findViewById<View>(R.id.iv_preview).rootView
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        imageView = findViewById(R.id.iv_preview)
        textScroll = findViewById(R.id.scroll_preview_text)
        textView = findViewById(R.id.tv_preview_text)
        unsupportedLayout = findViewById(R.id.layout_preview_unsupported)
        messageView = findViewById(R.id.tv_preview_message)
        progress = findViewById(R.id.progress_preview)
        titleView = findViewById(R.id.tv_preview_title)

        findViewById<ImageButton>(R.id.btn_preview_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_preview_open).setOnClickListener { openWithSystem() }
        findViewById<ImageButton>(R.id.btn_preview_share).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.btn_preview_unsupported_open).setOnClickListener {
            openWithSystem()
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        load(path)
    }

    private fun load(path: String) {
        progress?.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stat = FileRepository.stat(path)
                    val file = StorageManager.resolve(path)
                    if (file == null || !file.exists()) {
                        throw IllegalStateException("文件不存在")
                    }
                    stat to file
                }
            }
            progress?.visibility = View.GONE
            result.onSuccess { (stat, file) ->
                entry = stat
                titleView?.text = stat.name
                when {
                    stat.kind == FileKind.IMAGE -> showImage(file)
                    stat.kind == FileKind.TEXT ||
                        (stat.kind == FileKind.OTHER && FileTypes.isTextExtension(stat.ext)) -> showText(file)
                    else -> showUnsupported(stat)
                }
            }.onFailure {
                UiUtils.toast(this@PreviewActivity, "无法打开：${it.message ?: "未知错误"}", long = true)
                finish()
            }
        }
    }

    private fun showImage(file: File) {
        val bitmap = decodeSampled(file, MAX_IMAGE_SIZE)
        if (bitmap == null) {
            showUnsupported(null, "图片解码失败，可能是格式不受支持")
            return
        }
        imageView?.setImageBitmap(bitmap)
        imageView?.visibility = View.VISIBLE
    }

    private fun showText(file: File) {
        val text = runCatching {
            val length = file.length()
            if (length <= 0) {
                ""
            } else {
                val limit = minOf(length, MAX_TEXT_BYTES.toLong()).toInt()
                val buffer = ByteArray(limit)
                file.inputStream().use { input ->
                    var read = 0
                    while (read < limit) {
                        val step = input.read(buffer, read, limit - read)
                        if (step < 0) break
                        read += step
                    }
                }
                String(buffer, Charsets.UTF_8)
            }
        }.getOrElse { "读取失败：${it.message ?: "未知错误"}" }

        val suffix = if (file.length() > MAX_TEXT_BYTES) {
            "\n\n……（文件较大，仅预览前 ${MAX_TEXT_BYTES / 1024} KB）"
        } else {
            ""
        }
        textView?.text = text + suffix
        textScroll?.visibility = View.VISIBLE
    }

    private fun showUnsupported(stat: FileEntry?, customMessage: String? = null) {
        unsupportedLayout?.visibility = View.VISIBLE
        val hint = customMessage ?: when (stat?.kind) {
            FileKind.VIDEO -> "视频请使用系统播放器打开"
            FileKind.AUDIO -> "音频请使用系统播放器打开"
            FileKind.ARCHIVE -> "压缩包无法直接预览"
            FileKind.APK -> "安装包无法直接预览"
            FileKind.DOCUMENT -> "文档请使用系统应用打开"
            else -> "该类型暂不支持预览"
        }
        messageView?.text = hint
    }

    private fun openWithSystem() {
        val current = entry ?: return
        val file = StorageManager.resolve(current.path) ?: return
        FileOpener.openFile(this, file, current.mime)
    }

    private fun share() {
        val current = entry ?: return
        val file = StorageManager.resolve(current.path) ?: return
        FileOpener.shareFiles(this, listOf(file), current.mime)
    }

    private fun decodeSampled(file: File, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val MAX_IMAGE_SIZE = 2048
        private const val MAX_TEXT_BYTES = 256 * 1024

        fun intent(context: Context, path: String): Intent =
            Intent(context, PreviewActivity::class.java).putExtra(EXTRA_PATH, path)
    }
}