package com.yuanzai.lanfile.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 展示格式化工具：文件大小、时间、速度。 */
object FormatUtils {

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024
    private const val TB = GB * 1024

    /** 1.52 GB / 18.5 MB / 512 B */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "-"
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < MB -> format(bytes / KB, "KB")
            bytes < GB -> format(bytes / MB, "MB")
            bytes < TB -> format(bytes / GB, "GB")
            else -> format(bytes / TB, "TB")
        }
    }

    private fun format(value: Double, unit: String): String {
        val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
        return String.format(Locale.US, pattern, value, unit)
    }

    /** 传输速度：12.3 MB/s */
    fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0 || bytesPerSecond.isNaN() || bytesPerSecond.isInfinite()) return "0 B/s"
        return formatSize(bytesPerSecond.toLong()) + "/s"
    }

    /** 剩余时间：约 12 秒 / 约 3 分钟 */
    fun formatEta(seconds: Double): String = when {
        seconds <= 0 || seconds.isNaN() || seconds.isInfinite() -> "-"
        seconds < 60 -> String.format(Locale.US, "%.0f 秒", seconds)
        seconds < 3600 -> String.format(Locale.US, "%.0f 分钟", seconds / 60)
        else -> String.format(Locale.US, "%.1f 小时", seconds / 3600)
    }

    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())

    /** 2026-09-20 11:20 */
    fun formatTime(millis: Long): String {
        if (millis <= 0) return "-"
        return formatter("yyyy-MM-dd HH:mm").format(Date(millis))
    }

    fun formatTimeWithSeconds(millis: Long): String {
        if (millis <= 0) return "-"
        return formatter("yyyy-MM-dd HH:mm:ss").format(Date(millis))
    }

    /** 今天 11:20 / 昨天 11:20 / 2026-09-20 11:20 */
    fun formatMessageTime(millis: Long): String {
        if (millis <= 0) return "-"
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
        val dayDiff = dayOfYear(now) - dayOfYear(target) + (now.get(Calendar.YEAR) - target.get(Calendar.YEAR)) * 366
        val hm = formatter("HH:mm").format(Date(millis))
        return when {
            dayDiff == 0 -> "今天 $hm"
            dayDiff == 1 -> "昨天 $hm"
            sameYear -> formatter("MM-dd HH:mm").format(Date(millis))
            else -> formatter("yyyy-MM-dd HH:mm").format(Date(millis))
        }
    }

    private fun dayOfYear(calendar: Calendar): Int = calendar.get(Calendar.DAY_OF_YEAR)

    /** 相对时间：刚刚 / 3 分钟前 / 2 小时前 / 3 天前 / 2026-09-20 11:20 */
    fun formatAgo(fromMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (fromMillis <= 0) return "-"
        val diff = (nowMillis - fromMillis).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
            else -> formatMessageTime(fromMillis)
        }
    }

    /** 运行时长：1 小时 3 分钟 */
    fun formatDuration(fromMillis: Long, toMillis: Long = System.currentTimeMillis()): String {
        val seconds = abs(toMillis - fromMillis) / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h} 小时 ${m} 分钟"
            m > 0 -> "${m} 分钟 ${s} 秒"
            else -> "${s} 秒"
        }
    }

    fun formatCount(count: Int, unit: String): String = "$count $unit"
}