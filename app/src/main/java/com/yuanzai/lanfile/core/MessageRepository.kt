package com.yuanzai.lanfile.core

import android.content.Context
import com.yuanzai.lanfile.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 文字消息仓库：网页端发来的消息会立刻出现在手机端，反之亦然。
 * 消息以 JSON 文件持久化在应用私有目录（不放进共享目录，避免被外部误删）。
 */
object MessageRepository {

    const val MAX_MESSAGES = 500
    const val MAX_TEXT_LENGTH = 20000
    private const val FILE_NAME = "messages.json"

    private val lock = Any()
    private val messages = ArrayList<Message>() // 新的在前
    private val listeners = CopyOnWriteArrayList<(List<Message>) -> Unit>()
    private var storageFile: File? = null

    fun init(context: Context) {
        storageFile = File(context.applicationContext.filesDir, FILE_NAME)
        load()
    }

    fun all(limit: Int = 200): List<Message> = synchronized(lock) {
        if (limit <= 0 || limit >= messages.size) ArrayList(messages) else ArrayList(messages.subList(0, limit))
    }

    fun count(): Int = synchronized(lock) { messages.size }

    fun add(text: String?, source: String, device: String = ""): Message {
        val content = (text ?: "").trim()
        if (content.isEmpty()) throw OpException(OpException.BAD_REQUEST, "消息内容不能为空")
        if (content.length > MAX_TEXT_LENGTH) {
            throw OpException(OpException.PAYLOAD_TOO_LARGE, "消息过长（最多 $MAX_TEXT_LENGTH 个字符）")
        }
        val message = Message(
            id = UUID.randomUUID().toString(),
            text = content,
            time = System.currentTimeMillis(),
            source = source,
            device = device.ifBlank { defaultDevice(source) }
        )
        synchronized(lock) {
            messages.add(0, message)
            while (messages.size > MAX_MESSAGES) {
                messages.removeAt(messages.size - 1)
            }
        }
        persist()
        notifyChanged()
        return message
    }

    fun delete(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val idSet = ids.toHashSet()
        val removed: Int
        synchronized(lock) {
            val before = messages.size
            messages.removeAll { it.id in idSet }
            removed = before - messages.size
        }
        if (removed > 0) {
            persist()
            notifyChanged()
        }
        return removed
    }

    fun clear(): Int {
        val removed: Int
        synchronized(lock) {
            removed = messages.size
            messages.clear()
        }
        persist()
        notifyChanged()
        return removed
    }

    fun addListener(listener: (List<Message>) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (List<Message>) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 消息来源设备的默认标识（网页端消息会在写入时带上真实 UA 标签）。
     */
    fun defaultDevice(source: String): String = when (source) {
        "android" -> "本机 · 安卓"
        "web" -> "电脑网页"
        "system" -> "系统"
        else -> source
    }

    private fun notifyChanged() {
        val snapshot = all(MAX_MESSAGES)
        for (listener in listeners) {
            runCatching { listener(snapshot) }
        }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val array = JSONArray(content)
            val list = ArrayList<Message>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                list.add(Message.fromJson(obj))
            }
            synchronized(lock) {
                messages.clear()
                messages.addAll(list.sortedByDescending { it.time })
            }
        } catch (e: Exception) {
            // 消息文件损坏不应导致 App 崩溃：忽略并重新开始
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
        }
    }

    private fun persist() {
        val file = storageFile ?: return
        val snapshot = all(MAX_MESSAGES)
        val json = JSONArray()
        for (message in snapshot) {
            json.put(JSONObject().apply {
                put("id", message.id)
                put("text", message.text)
                put("time", message.time)
                put("source", message.source)
                put("device", message.device)
            })
        }
        try {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(json.toString())
                temp.delete()
            }
        } catch (e: Exception) {
            // 写失败时保留内存中的消息，不抛出
        }
    }
}