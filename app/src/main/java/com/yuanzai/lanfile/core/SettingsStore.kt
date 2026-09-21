package com.yuanzai.lanfile.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置（使用 SharedPreferences 持久化）。
 *
 * 所有设置项都做成属性读写，读取时带上默认值，避免第一次启动时出现空值。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 局域网服务监听端口，默认 8080。 */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).let {
            if (it in MIN_PORT..MAX_PORT) it else DEFAULT_PORT
        }
        set(value) {
            prefs.edit().putInt(KEY_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply()
        }

    /** 是否把文件存放在「内部存储/局域网文件」（需要“所有文件访问权限”）。 */
    var usePublicDir: Boolean
        get() = prefs.getBoolean(KEY_USE_PUBLIC_DIR, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_PUBLIC_DIR, value).apply()

    /** 打开 App 时是否自动启动服务。 */
    var autoStartServer: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 是否使用前台服务在后台保持运行（切到后台、锁屏后继续服务）。 */
    var backgroundRun: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()

    /** 是否显示服务运行通知。 */
    var showNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION, value).apply()

    /** 手机端文件管理器上次停留的目录。 */
    var lastPath: String
        get() = prefs.getString(KEY_LAST_PATH, "/") ?: "/"
        set(value) = prefs.edit().putString(KEY_LAST_PATH, value).apply()

    /** 手机端文件列表排序方式：name / size / time / type。 */
    var sortMode: String
        get() = prefs.getString(KEY_SORT, SORT_NAME) ?: SORT_NAME
        set(value) = prefs.edit().putString(KEY_SORT, value).apply()

    var sortAscending: Boolean
        get() = prefs.getBoolean(KEY_SORT_ASC, true)
        set(value) = prefs.edit().putBoolean(KEY_SORT_ASC, value).apply()

    /** 主题模式：0=跟随系统 1=浅色 2=深色。 */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, THEME_SYSTEM).coerceIn(THEME_SYSTEM, THEME_DARK)
        set(value) = prefs.edit().putInt(KEY_THEME, value.coerceIn(THEME_SYSTEM, THEME_DARK)).apply()

    /** 打开 App 时默认显示的页面：0=首页 1=文件 2=消息 3=设置。 */
    var startTab: Int
        get() = prefs.getInt(KEY_START_TAB, TAB_HOME).coerceIn(TAB_HOME, TAB_DEVICES)
        set(value) = prefs.edit().putInt(KEY_START_TAB, value.coerceIn(TAB_HOME, TAB_DEVICES)).apply()

    /**
     * 用来打开「局域网文件」目录的外部应用（存的是 `包名/Activity` 全名）。
     * 空字符串表示还没设置过 —— 这时第一次点击会弹出选择框让用户挑一个。
     */
    var externalAppComponent: String
        get() = prefs.getString(KEY_EXTERNAL_APP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_EXTERNAL_APP, value).apply()

    // ------------------------------------------------------------------ 网页端

    /** 网页端顶部显示的标题。 */
    var webTitle: String
        get() = prefs.getString(KEY_WEB_TITLE, DEFAULT_WEB_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_WEB_TITLE
        set(value) = prefs.edit().putString(KEY_WEB_TITLE, value.trim().take(40)).apply()

    /** 网页端是否允许上传文件。 */
    var webUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_UPLOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_UPLOAD, value).apply()

    /** 网页端是否允许删除文件 / 文件夹。 */
    var webDeleteEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_DELETE, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_DELETE, value).apply()

    /** 网页端是否允许改动文件结构：重命名、新建文件夹、移动、复制。 */
    var webModifyEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_MODIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_MODIFY, value).apply()

    /** 网页端是否允许文字互传（收发与清空消息）。 */
    var webTextEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_TEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_TEXT, value).apply()

    /** 网页端单个上传文件的大小上限（MB），0 表示不限制。 */
    var webUploadLimitMb: Int
        get() = prefs.getInt(KEY_WEB_LIMIT, 0).coerceIn(0, 10240)
        set(value) = prefs.edit().putInt(KEY_WEB_LIMIT, value.coerceIn(0, 10240)).apply()

    /** 上传上限换算成字节数（0 表示不限）。 */
    fun webUploadLimitBytes(): Long {
        val mb = webUploadLimitMb
        return if (mb <= 0) 0L else mb.toLong() * 1024L * 1024L
    }

    /** 端口 / 目录等关键配置变化时递增，用于让 UI 知道需要重启服务。 */
    var configRevision: Int
        get() = prefs.getInt(KEY_REVISION, 0)
        set(value) = prefs.edit().putInt(KEY_REVISION, value).apply()

    fun bumpRevision() {
        configRevision = configRevision + 1
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        const val SORT_NAME = "name"
        const val SORT_SIZE = "size"
        const val SORT_TIME = "time"
        const val SORT_TYPE = "type"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val TAB_HOME = 0
        const val TAB_FILES = 1
        const val TAB_MESSAGES = 2
        const val TAB_SETTINGS = 3
        const val TAB_DEVICES = 4

        const val DEFAULT_WEB_TITLE = "局域网文件"

        val THEME_LABELS = arrayOf("跟随系统", "浅色", "深色")
        val START_TAB_LABELS = arrayOf("首页", "文件", "消息", "设置", "设备")
        val WEB_LIMIT_OPTIONS = intArrayOf(0, 100, 500, 1024, 2048)
        val WEB_LIMIT_LABELS = arrayOf("不限制", "100 MB", "500 MB", "1 GB", "2 GB")

        fun themeLabel(mode: Int): String = THEME_LABELS.getOrElse(mode) { THEME_LABELS[0] }

        fun startTabLabel(index: Int): String = START_TAB_LABELS.getOrElse(index) { START_TAB_LABELS[0] }

        /** 上传上限对应的选项下标（找不到时落到“不限制”）。 */
        fun webLimitIndex(mb: Int): Int = WEB_LIMIT_OPTIONS.indexOf(mb).takeIf { it >= 0 } ?: 0

        fun webUploadLimitLabel(mb: Int): String {
            val index = WEB_LIMIT_OPTIONS.indexOf(mb)
            return if (index >= 0) WEB_LIMIT_LABELS[index] else "$mb MB"
        }

        private const val PREFS_NAME = "lanfile_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_START_TAB = "start_tab"
        private const val KEY_EXTERNAL_APP = "external_open_app"
        private const val KEY_WEB_TITLE = "web_title"
        private const val KEY_WEB_UPLOAD = "web_allow_upload"
        private const val KEY_WEB_DELETE = "web_allow_delete"
        private const val KEY_WEB_MODIFY = "web_allow_modify"
        private const val KEY_WEB_TEXT = "web_allow_text"
        private const val KEY_WEB_LIMIT = "web_upload_limit_mb"
        private const val KEY_PORT = "port"
        private const val KEY_USE_PUBLIC_DIR = "use_public_dir"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_BACKGROUND = "background_run"
        private const val KEY_NOTIFICATION = "show_notification"
        private const val KEY_LAST_PATH = "last_path"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_SORT_ASC = "sort_ascending"
        private const val KEY_REVISION = "config_revision"
    }
}