package com.yuanzai.lanfile.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.model.ServerStatus
import com.yuanzai.lanfile.service.LanServerService
import com.yuanzai.lanfile.ui.MainActivity

/** 通知渠道与前台服务通知。 */
object NotificationHelper {

    const val CHANNEL_SERVICE = "lanfile_service"
    const val SERVICE_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_SERVICE)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            "局域网文件服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示局域网文件服务的运行状态与访问地址"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** 服务运行通知：标题 + 访问地址 + 停止按钮。 */
    fun buildServiceNotification(
        context: Context,
        status: ServerStatus,
        detailed: Boolean = true
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, LanServerService::class.java).setAction(LanServerService.ACTION_STOP)
        val stopPending = PendingIntent.getService(
            context, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = when {
            status.error != null -> status.error
            status.url != null -> "${status.ipLabel}:${status.port}"
            else -> "等待局域网连接（请检查 Wi-Fi）"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle("局域网文件服务运行中")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (detailed) {
            val expanded = buildString {
                append("访问地址：").append(status.url ?: "无（未连接局域网）")
                append('\n')
                append("存储目录：").append(status.rootPath)
            }
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            builder.addAction(0, "停止服务", stopPending)
        }

        return builder.build()
    }

    /** 服务启动失败时的一条普通通知（非前台）。 */
    fun notifyError(context: Context, message: String) {
        if (!areNotificationsAllowed(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_info)
            .setContentTitle("局域网文件服务未启动")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SERVICE_NOTIFICATION_ID + 1, notification)
        }
    }

    fun areNotificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}