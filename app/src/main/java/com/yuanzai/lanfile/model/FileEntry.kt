package com.yuanzai.lanfile.model

import org.json.JSONObject

/** 文件大类，用于选择图标、预览方式和网页端展示。 */
enum class FileKind(val wire: String) {
    DIR("dir"),
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    DOCUMENT("document"),
    ARCHIVE("archive"),
    APK("apk"),
    TEXT("text"),
    OTHER("other");

    companion object {
        fun fromWire(value: String?): FileKind =
            entries.firstOrNull { it.wire == value } ?: OTHER
    }
}

/**
 * 文件/文件夹条目。路径一律是相对于存储根目录的 "/" 开头路径，
 * 手机端与网页端使用同一份数据。
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: FileKind,
    val ext: String,
    val mime: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("path", path)
        put("isDir", isDir)
        put("size", size)
        put("modified", modified)
        put("type", kind.wire)
        put("ext", ext)
        put("mime", mime)
    }

    companion object {
        fun fromJson(json: JSONObject): FileEntry = FileEntry(
            name = json.optString("name"),
            path = json.optString("path"),
            isDir = json.optBoolean("isDir"),
            size = json.optLong("size"),
            modified = json.optLong("modified"),
            kind = FileKind.fromWire(json.optString("type")),
            ext = json.optString("ext"),
            mime = json.optString("mime")
        )
    }
}