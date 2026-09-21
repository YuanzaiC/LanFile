package com.yuanzai.lanfile.model

import org.json.JSONObject

/**
 * 一条文字消息。
 * [source] 为 "web" / "android" / "system"；
 * [device] 是来源设备的标识，例如 `Windows · Edge`、`Android · Chrome`、`本机 · 安卓`。
 */
data class Message(
    val id: String,
    val text: String,
    val time: Long,
    val source: String,
    val device: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("time", time)
        put("source", source)
        put("device", device)
    }

    companion object {
        fun fromJson(json: JSONObject): Message = Message(
            id = json.optString("id"),
            text = json.optString("text"),
            time = json.optLong("time"),
            source = json.optString("source", "unknown"),
            device = json.optString("device")
        )
    }
}