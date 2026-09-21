package com.yuanzai.lanfile.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Android 16（API 36）起的「本地网络访问」权限。
 *
 * 从 Android 17（API 37）+ targetSdk 37 开始，系统**默认禁止** App 访问本地网络，
 * 包括**接受局域网设备连入的 TCP 连接**。没有这个权限时表现为：
 * 手机自己访问自己的地址正常（同设备内部通信不受限制），
 * 但同一局域网里的电脑/手机 `ping` 得通、`Test-NetConnection -Port` 却是 False。
 *
 * 该权限在 API 37 平台清单里的 protectionLevel = 0x1（dangerous），
 * 也就是**必须在运行时申请并由用户点「允许」**。
 */
object LocalNetworkPermission {

    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** 首次出现该权限的 API 级别。 */
    private const val MIN_API = 36

    /** 这台设备是否真的需要这个权限（老系统或没有该权限的机型返回 false）。 */
    fun isRequired(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_API) return false
        return runCatching {
            context.packageManager.getPermissionInfo(PERMISSION, 0)
            true
        }.getOrDefault(false)
    }

    /** 是否已授权。不需要该权限时视为已授权。 */
    fun isGranted(context: Context): Boolean {
        if (!isRequired(context)) return true
        return runCatching {
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
    }

    /** 一句可直接显示给人看的权限状态。 */
    fun describe(context: Context): String = when {
        !isRequired(context) -> "无需此权限（系统版本低于 Android 16）"
        isGranted(context) -> "已授权"
        else -> "未授权 —— 未授权时局域网设备连不进来（手机自己访问仍会成功，具有欺骗性）"
    }

    /** 跳转到本应用的系统设置页（用户之前点了“拒绝且不再询问”时用）。 */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}