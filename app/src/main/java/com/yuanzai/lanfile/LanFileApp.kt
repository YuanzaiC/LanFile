package com.yuanzai.lanfile

import android.app.Application
import com.yuanzai.lanfile.core.ClientRegistry
import com.yuanzai.lanfile.core.MessageRepository
import com.yuanzai.lanfile.core.NotificationHelper
import com.yuanzai.lanfile.core.StorageManager

/**
 * 应用入口：初始化存储目录、消息仓库与通知渠道。
 * 目录初始化失败不会崩溃，只会在 UI 上提示，保证 App 始终可用。
 */
class LanFileApp : Application() {

    /** 最近一次目录初始化错误（UI 可读取展示）。 */
    @Volatile
    var storageError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        StorageManager.init(this)
        MessageRepository.init(this)
        ClientRegistry.init(this)
        NotificationHelper.createChannels(this)
        storageError = runCatching { StorageManager.ensureInitialized() }
            .getOrElse { "初始化目录失败：${it.message ?: it.javaClass.simpleName}" }
    }

    companion object {
        lateinit var instance: LanFileApp
            private set
    }
}