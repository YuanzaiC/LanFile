package com.yuanzai.lanfile.core

import android.content.Context
import android.os.Build

/** 应用信息（不依赖 BuildConfig）。 */
object AppInfo {

    const val APP_NAME = "局域网文件"

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")

    fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android 设备"
}