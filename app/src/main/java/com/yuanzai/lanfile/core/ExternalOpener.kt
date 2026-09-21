package com.yuanzai.lanfile.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/**
 * 「用其他应用打开目录」。
 *
 * Android 没有「打开文件夹」的统一标准，各家文件管理器认的 Intent 形态并不一样：
 *  - 系统系（文件、DocumentsUI）与多数管理器：文档提供者的目录 URI +
 *    `vnd.android.document/directory`
 *  - MT 管理器 / Solid / MiXplorer 一类：更认 `resource/folder`，或者干脆不带类型
 *  - 少数只看 content:// 的应用：只能靠本应用 FileProvider 的分享 URI
 *
 * 所以这里准备了几种「形态」（[Flavor]），查询时全都试一遍并合并去重——
 * 这是之前扫不到 MT 管理器的原因：只查了一种形态，而它不在那个结果集里。
 * 选中的应用会连「用哪种形态」一起记住，下次直接用能成功的那个形态启动。
 */
object ExternalOpener {

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val DIR_MIME = "vnd.android.document/directory"
    private const val FOLDER_MIME = "resource/folder"

    /** 打开目录的 Intent 形态。 */
    enum class Flavor(val key: String) {
        /** 系统文档目录 URI + 目录 MIME（最标准） */
        DOCUMENT_DIRECTORY("doc"),

        /** 系统文档目录 URI + 文件夹 MIME（MT 管理器等认这个） */
        DOCUMENT_FOLDER_MIME("docfolder"),

        /** 系统文档目录 URI，不带类型 */
        DOCUMENT_PLAIN("plain"),

        /** 本应用 FileProvider 分享 URI + 文件夹 MIME */
        FILEPROVIDER_FOLDER("share"),

        /** 任意应用：启动时按上面的顺序依次尝试 */
        AUTO("auto")
    }

    /** 候选应用 + 它是被哪种形态扫出来的。 */
    data class Candidate(val component: String, val label: String, val flavor: Flavor)

    /** 记住的选择：用哪个应用 + 用哪种形态。 */
    data class Selection(val component: String, val flavor: Flavor)

    /** 参与候选扫描的形态（都指向用户要打开的那个目录）。 */
    private val PICKER_FLAVORS = listOf(
        Flavor.DOCUMENT_DIRECTORY,
        Flavor.DOCUMENT_FOLDER_MIME,
        Flavor.DOCUMENT_PLAIN,
        Flavor.FILEPROVIDER_FOLDER
    )

    /** 启动时依次尝试的顺序：最标准的排前面。 */
    private val LAUNCH_CHAIN = PICKER_FLAVORS

    // ------------------------------------------------------------- URI 构造

    /** 共享存储（/storage/emulated/0）的绝对路径；取不到就返回 null。 */
    private fun sharedRoot(context: Context): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val marker = "/Android/data/"
        val index = dir.absolutePath.indexOf(marker)
        return if (index > 0) dir.absolutePath.substring(0, index) else null
    }

    /** 目录在共享存储下的文档 ID（`primary:` 或 `primary:局域网文件/文档`）。 */
    fun documentId(context: Context, dir: File): String? {
        val root = sharedRoot(context) ?: return null
        val path = dir.absolutePath
        if (!path.startsWith(root)) return null
        val relative = path.removePrefix(root).trim('/')
        return if (relative.isEmpty()) "primary:" else "primary:$relative"
    }

    fun documentUri(context: Context, dir: File): Uri? {
        val id = documentId(context, dir) ?: return null
        return runCatching { DocumentsContract.buildDocumentUri(AUTHORITY, id) }.getOrNull()
    }

    private fun shareUri(context: Context, dir: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dir)
    }.getOrNull()

    private fun viewIntent(uri: Uri, type: String?): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        if (type == null) intent.setData(uri) else intent.setDataAndType(uri, type)
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 按指定形态构造 Intent；该形态在当前目录上不可用时返回 null。 */
    fun intentFor(context: Context, dir: File, flavor: Flavor): Intent? = when (flavor) {
        Flavor.DOCUMENT_DIRECTORY -> documentUri(context, dir)?.let { viewIntent(it, DIR_MIME) }
        Flavor.DOCUMENT_FOLDER_MIME -> documentUri(context, dir)?.let { viewIntent(it, FOLDER_MIME) }
        Flavor.DOCUMENT_PLAIN -> documentUri(context, dir)?.let { viewIntent(it, null) }
        Flavor.FILEPROVIDER_FOLDER -> shareUri(context, dir)?.let { viewIntent(it, FOLDER_MIME) }
        Flavor.AUTO -> intentFor(context, dir, Flavor.DOCUMENT_DIRECTORY)
    }

    /** 打开该目录的默认 Intent（标准形态）；目录不可被外部应用访问时返回 null。 */
    fun folderIntent(context: Context, dir: File): Intent? =
        intentFor(context, dir, Flavor.DOCUMENT_DIRECTORY)

    // ------------------------------------------------------------- 候选应用

    fun componentOf(info: ResolveInfo): String =
        info.activityInfo.packageName + "/" + info.activityInfo.name

    fun labelOf(context: Context, info: ResolveInfo): String =
        runCatching { info.loadLabel(context.packageManager).toString() }.getOrElse { componentOf(info) }

    /**
     * 所有可能打开该目录的应用：多种形态分别查询后合并去重，
     * 每个应用记住第一个能扫到它的形态。
     */
    fun candidates(context: Context, dir: File): List<Candidate> {
        val seen = LinkedHashMap<String, Candidate>()
        for (flavor in PICKER_FLAVORS) {
            val intent = intentFor(context, dir, flavor) ?: continue
            val list = runCatching {
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }.getOrElse { emptyList() }
            for (info in list) {
                val component = componentOf(info)
                if (!seen.containsKey(component)) {
                    seen[component] = Candidate(component, labelOf(context, info), flavor)
                }
            }
        }
        return seen.values.toList()
    }

    /** 已安装且可启动的应用：候选列表里找不到目标应用时的兜底选择。 */
    fun installedApps(context: Context): List<Candidate> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrElse { emptyList() }
        val seen = LinkedHashMap<String, Candidate>()
        for (info in list) {
            val component = componentOf(info)
            seen.getOrPut(component) { Candidate(component, labelOf(context, info), Flavor.AUTO) }
        }
        return seen.values.sortedBy { it.label }
    }

    // ------------------------------------------------------------- 记住的选择

    /** 序列化成 `包名/类名|形态` 存进设置。 */
    fun encode(component: String, flavor: Flavor): String = "$component|${flavor.key}"

    /** 解析设置里存的 `包名/类名|形态`。 */
    fun parse(value: String?): Selection? {
        if (value.isNullOrBlank()) return null
        val component = value.substringBefore('|').trim()
        if (component.isBlank() || !component.contains('/')) return null
        val key = value.substringAfter('|', "")
        val flavor = Flavor.entries.firstOrNull { it.key == key } ?: Flavor.AUTO
        return Selection(component, flavor)
    }

    /** 已选应用的显示名（应用被卸载时退化成包名）。 */
    fun labelOf(context: Context, selection: String?): String {
        val component = parse(selection)?.component ?: return "未设置"
        val pkg = component.substringBefore('/')
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { pkg }
    }

    /** 该选择对应的应用当前是否还装着。 */
    fun isInstalled(context: Context, selection: String?): Boolean {
        val component = parse(selection)?.component ?: return false
        val name = ComponentName.unflattenFromString(component) ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(name, 0)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------- 启动

    /**
     * 打开目录。
     * @param selection 设置里记住的 `包名/类名|形态`，为空则交给系统选择器。
     */
    fun start(context: Context, dir: File, selection: String?): Boolean {
        val parsed = parse(selection)
        if (parsed == null) {
            val base = folderIntent(context, dir) ?: return false
            val chooser = Intent.createChooser(base, "选择打开此目录的应用")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }
        return start(context, dir, parsed.component, parsed.flavor)
    }

    /** 用指定应用打开目录；[Flavor.AUTO] 会按标准形态依次尝试。 */
    fun start(context: Context, dir: File, component: String, flavor: Flavor): Boolean {
        val target = ComponentName.unflattenFromString(component) ?: return false
        val chain = if (flavor == Flavor.AUTO) LAUNCH_CHAIN else listOf(flavor) + LAUNCH_CHAIN.filter { it != flavor }
        for (candidateFlavor in chain) {
            val base = intentFor(context, dir, candidateFlavor) ?: continue
            val intent = Intent(base).setComponent(target)
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** 用系统选择器打开目录（不指定应用）。 */
    fun startChooser(context: Context, dir: File): Boolean {
        val base = folderIntent(context, dir) ?: return false
        val chooser = Intent.createChooser(base, "选择打开此目录的应用")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }
}