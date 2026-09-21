package com.yuanzai.lanfile.core

import android.webkit.MimeTypeMap
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.model.FileKind

/**
 * 扩展名 -> 文件大类 / MIME / 图标。
 * 不依赖任何第三方库，纯字符串表。
 */
object FileTypes {

    private val IMAGE = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "ico", "tif", "tiff", "avif"
    )
    private val VIDEO = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts", "rmvb", "mpg", "mpeg"
    )
    private val AUDIO = setOf(
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "ape", "amr", "mid", "midi"
    )
    private val DOCUMENT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "mobi"
    )
    private val ARCHIVE = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "jar", "war", "lz", "zst"
    )
    private val TEXT = setOf(
        "txt", "log", "md", "json", "xml", "csv", "ini", "cfg", "conf", "yml", "yaml",
        "html", "htm", "js", "css", "kt", "java", "py", "c", "cpp", "h", "sh", "bat", "properties", "srt", "vtt"
    )
    private val APK = setOf("apk", "apks", "xapk", "aab")

    /** 可以当作文本预览的扩展名。 */
    fun isTextExtension(ext: String): Boolean = TEXT.contains(ext.lowercase())

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun kindOf(name: String, isDir: Boolean): FileKind {
        if (isDir) return FileKind.DIR
        val ext = extensionOf(name)
        return when {
            ext.isEmpty() -> FileKind.OTHER
            APK.contains(ext) -> FileKind.APK
            IMAGE.contains(ext) -> FileKind.IMAGE
            VIDEO.contains(ext) -> FileKind.VIDEO
            AUDIO.contains(ext) -> FileKind.AUDIO
            ARCHIVE.contains(ext) -> FileKind.ARCHIVE
            DOCUMENT.contains(ext) -> FileKind.DOCUMENT
            TEXT.contains(ext) -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    fun mimeOf(name: String, isDir: Boolean): String {
        if (isDir) return "inode/directory"
        val ext = extensionOf(name)
        if (ext.isEmpty()) return "application/octet-stream"
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!fromMap.isNullOrBlank()) return fromMap
        return when (ext) {
            "apk", "apks", "xapk", "aab" -> "application/vnd.android.package-archive"
            "md" -> "text/markdown"
            "log", "ini", "cfg", "conf", "properties" -> "text/plain"
            "mkv" -> "video/x-matroska"
            "flac" -> "audio/flac"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            else -> "application/octet-stream"
        }
    }

    fun iconOf(kind: FileKind): Int = when (kind) {
        FileKind.DIR -> R.drawable.ic_type_folder
        FileKind.IMAGE -> R.drawable.ic_type_image
        FileKind.VIDEO -> R.drawable.ic_type_video
        FileKind.AUDIO -> R.drawable.ic_type_audio
        FileKind.DOCUMENT -> R.drawable.ic_type_document
        FileKind.ARCHIVE -> R.drawable.ic_type_archive
        FileKind.APK -> R.drawable.ic_type_apk
        FileKind.TEXT -> R.drawable.ic_type_text
        FileKind.OTHER -> R.drawable.ic_type_file
    }

    fun labelOf(kind: FileKind): String = when (kind) {
        FileKind.DIR -> "文件夹"
        FileKind.IMAGE -> "图片"
        FileKind.VIDEO -> "视频"
        FileKind.AUDIO -> "音频"
        FileKind.DOCUMENT -> "文档"
        FileKind.ARCHIVE -> "压缩包"
        FileKind.APK -> "安装包"
        FileKind.TEXT -> "文本"
        FileKind.OTHER -> "普通文件"
    }

    /** 文件详情里的“类型”列，例如 ZIP / JPG。 */
    fun detailTypeOf(kind: FileKind, ext: String): String = when {
        kind == FileKind.DIR -> "文件夹"
        ext.isNotEmpty() -> ext.uppercase()
        else -> "文件"
    }
}