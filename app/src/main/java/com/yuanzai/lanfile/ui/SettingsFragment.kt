package com.yuanzai.lanfile.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.AppInfo
import com.yuanzai.lanfile.core.ExternalOpener
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.NetworkUtils
import com.yuanzai.lanfile.core.SettingsStore
import com.yuanzai.lanfile.core.StorageManager
import com.yuanzai.lanfile.service.LanServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：端口、运行方式、存储目录、权限与基础信息。
 * 任何影响服务的改动都会自动重启服务使其生效。
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var settings: SettingsStore
    private var suppressListeners = false

    private var portInput: TextInputEditText? = null
    private var autostartSwitch: MaterialSwitch? = null
    private var backgroundSwitch: MaterialSwitch? = null
    private var notificationSwitch: MaterialSwitch? = null
    private var publicDirSwitch: MaterialSwitch? = null
    private var storageInfo: TextView? = null
    private var statusInfo: TextView? = null
    private var aboutInfo: TextView? = null
    private var grantButton: MaterialButton? = null
    private var themeValue: TextView? = null
    private var externalAppValue: TextView? = null
    private var startTabValue: TextView? = null
    private var webTitleValue: TextView? = null
    private var webLimitValue: TextView? = null
    private var webUploadSwitch: MaterialSwitch? = null
    private var webDeleteSwitch: MaterialSwitch? = null
    private var webModifySwitch: MaterialSwitch? = null
    private var webTextSwitch: MaterialSwitch? = null
    private var batteryButton: MaterialButton? = null

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                onStorageSettingChanged("已获得存储权限")
            } else {
                UiUtils.toast(requireContext(), "未授予存储权限，将使用应用专属目录")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        portInput = view.findViewById(R.id.et_port)
        autostartSwitch = view.findViewById(R.id.sw_autostart)
        backgroundSwitch = view.findViewById(R.id.sw_background)
        notificationSwitch = view.findViewById(R.id.sw_notification)
        publicDirSwitch = view.findViewById(R.id.sw_public_dir)
        storageInfo = view.findViewById(R.id.tv_storage_info)
        statusInfo = view.findViewById(R.id.tv_settings_status)
        aboutInfo = view.findViewById(R.id.tv_settings_about)
        grantButton = view.findViewById(R.id.btn_grant_permission)
        themeValue = view.findViewById(R.id.tv_theme_value)
        externalAppValue = view.findViewById(R.id.tv_external_app_value)
        startTabValue = view.findViewById(R.id.tv_start_tab_value)
        UiUtils.addPressFeedbackRecursive(view)
        webTitleValue = view.findViewById(R.id.tv_web_title_value)
        webLimitValue = view.findViewById(R.id.tv_web_limit_value)
        webUploadSwitch = view.findViewById(R.id.sw_web_upload)
        webDeleteSwitch = view.findViewById(R.id.sw_web_delete)
        webModifySwitch = view.findViewById(R.id.sw_web_modify)
        webTextSwitch = view.findViewById(R.id.sw_web_text)
        batteryButton = view.findViewById(R.id.btn_battery)
        batteryButton?.setOnClickListener { UiUtils.requestIgnoreBatteryOptimizations(requireContext()) }

        view.findViewById<View>(R.id.row_theme).setOnClickListener {
            UiUtils.chooseDialog(
                requireContext(),
                "主题",
                SettingsStore.THEME_LABELS,
                settings.themeMode
            ) { index ->
                settings.themeMode = index
                // setDefaultNightMode 会让当前 Activity 自动重建，界面立即换肤
                UiUtils.applyTheme(settings)
                render()
            }
        }

        view.findViewById<View>(R.id.row_external_app).setOnClickListener {
            val context = requireContext()
            UiUtils.chooseExternalApp(context, StorageManager.root(), settings.externalAppComponent) { chosen ->
                settings.externalAppComponent = chosen
                render()
                UiUtils.toast(
                    context,
                    if (chosen.isEmpty()) {
                        "已设为每次询问"
                    } else {
                        "已选择：${ExternalOpener.labelOf(context, chosen)}"
                    }
                )
            }
        }

        view.findViewById<View>(R.id.row_start_tab).setOnClickListener {
            UiUtils.chooseDialog(
                requireContext(),
                "默认打开页面",
                SettingsStore.START_TAB_LABELS,
                settings.startTab
            ) { index ->
                settings.startTab = index
                render()
                UiUtils.toast(requireContext(), "已设置：下次打开默认进入「${SettingsStore.startTabLabel(index)}」")
            }
        }

        view.findViewById<View>(R.id.row_web_title).setOnClickListener {
            UiUtils.inputDialog(
                requireContext(),
                "网页端标题",
                "显示在网页顶部",
                settings.webTitle,
                confirmText = "保存"
            ) { value ->
                settings.webTitle = value
                render()
                UiUtils.toast(requireContext(), "网页标题已保存，刷新网页后生效")
            }
        }

        view.findViewById<View>(R.id.row_web_limit).setOnClickListener {
            UiUtils.chooseDialog(
                requireContext(),
                "单个上传文件上限",
                SettingsStore.WEB_LIMIT_LABELS,
                SettingsStore.webLimitIndex(settings.webUploadLimitMb)
            ) { index ->
                settings.webUploadLimitMb = SettingsStore.WEB_LIMIT_OPTIONS[index]
                render()
                UiUtils.toast(requireContext(), "上传上限已设为 ${SettingsStore.webUploadLimitLabel(settings.webUploadLimitMb)}")
            }
        }

        webUploadSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.webUploadEnabled = checked
            UiUtils.toast(requireContext(), if (checked) "已允许网页端上传文件" else "已禁止网页端上传文件")
        }
        webDeleteSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.webDeleteEnabled = checked
            UiUtils.toast(requireContext(), if (checked) "已允许网页端删除文件" else "已禁止网页端删除文件")
        }
        webModifySwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.webModifyEnabled = checked
            UiUtils.toast(requireContext(), if (checked) "已允许网页端改动文件" else "已禁止网页端改动文件")
        }
        webTextSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.webTextEnabled = checked
            UiUtils.toast(requireContext(), if (checked) "已允许网页端文字互传" else "已禁止网页端文字互传")
        }

        view.findViewById<MaterialButton>(R.id.btn_save_port).setOnClickListener {
            val value = portInput?.text?.toString()?.trim()?.toIntOrNull()
            if (value == null || value < SettingsStore.MIN_PORT || value > SettingsStore.MAX_PORT) {
                UiUtils.toast(
                    requireContext(),
                    "端口号需在 ${SettingsStore.MIN_PORT} - ${SettingsStore.MAX_PORT} 之间"
                )
                return@setOnClickListener
            }
            settings.port = value
            settings.bumpRevision()
            applyAndRestart("端口已改为 $value")
        }

        view.findViewById<MaterialButton>(R.id.btn_apply_runtime).setOnClickListener {
            applyAndRestart("设置已应用")
        }

        view.findViewById<MaterialButton>(R.id.btn_stop_service).setOnClickListener {
            LanServerService.stop(requireContext())
            UiUtils.toast(requireContext(), "局域网服务已停止")
        }

        grantButton?.setOnClickListener { requestAllFilesAccess() }

        view.findViewById<MaterialButton>(R.id.btn_init_dirs).setOnClickListener {
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) { StorageManager.ensureInitialized() }
                if (!isAdded) return@launch
                render()
                if (error == null) {
                    UiUtils.toast(context, "目录已就绪：${StorageManager.root().absolutePath}")
                } else {
                    UiUtils.toast(context, error, long = true)
                }
            }
        }

        autostartSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.autoStartServer = checked
            settings.bumpRevision()
            UiUtils.toast(requireContext(), if (checked) "已开启：打开 App 时自动启动服务" else "已关闭：打开 App 时自动启动服务")
        }
        backgroundSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.backgroundRun = checked
            settings.bumpRevision()
            applyAndRestart(if (checked) "已开启后台运行，服务将持续运行" else "已关闭后台运行，退出 App 后服务将停止")
        }
        notificationSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.showNotification = checked
            settings.bumpRevision()
            applyAndRestart(if (checked) "已开启服务状态通知" else "已关闭服务状态通知")
        }
        publicDirSwitch?.setOnCheckedChangeListener { _, checked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            settings.usePublicDir = checked
            settings.bumpRevision()
            StorageManager.refreshRoot()
            onStorageSettingChanged(
                if (checked) "存储目录已切换为「内部存储 / 局域网文件」" else "存储目录已切换为应用专属目录"
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LanServerService.status.collect { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        portInput = null
        autostartSwitch = null
        backgroundSwitch = null
        notificationSwitch = null
        publicDirSwitch = null
        storageInfo = null
        statusInfo = null
        aboutInfo = null
        grantButton = null
        themeValue = null
        externalAppValue = null
        startTabValue = null
        webTitleValue = null
        webLimitValue = null
        webUploadSwitch = null
        webDeleteSwitch = null
        webModifySwitch = null
        webTextSwitch = null
        batteryButton = null
        super.onDestroyView()
    }

    private fun applyAndRestart(message: String) {
        UiUtils.toast(requireContext(), message)
        LanServerService.restart(requireContext(), settings.backgroundRun)
    }

    /** 目录相关设置变化：重建目录结构并重启服务。 */
    private fun onStorageSettingChanged(message: String) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { StorageManager.ensureInitialized() }
            if (!isAdded) return@launch
            UiUtils.toast(context, error ?: message, long = error != null)
            render()
            LanServerService.restart(requireContext(), settings.backgroundRun)
        }
    }

    private fun requestAllFilesAccess() {
        val intent = StorageManager.allFilesAccessIntent()
        if (intent != null) {
            try {
                startActivity(intent)
                UiUtils.toast(requireContext(), "请在系统设置页中允许本应用访问所有文件")
                return
            } catch (e: Exception) {
                // 某些机型没有该页面，退化为普通权限申请
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            UiUtils.toast(
                requireContext(),
                "无法打开权限设置页，请在系统设置中手动授予「所有文件访问权限」",
                long = true
            )
        }
    }

    private fun render() {
        val view = view ?: return
        val context = context ?: return
        suppressListeners = true

        portInput?.setText(settings.port.toString())
        autostartSwitch?.isChecked = settings.autoStartServer
        backgroundSwitch?.isChecked = settings.backgroundRun
        notificationSwitch?.isChecked = settings.showNotification
        publicDirSwitch?.isChecked = settings.usePublicDir
        themeValue?.text = "当前：" + SettingsStore.themeLabel(settings.themeMode)
        externalAppValue?.text = if (settings.externalAppComponent.isEmpty()) {
            "未设置：首次点击时询问"
        } else {
            val label = ExternalOpener.labelOf(context, settings.externalAppComponent)
            if (ExternalOpener.isInstalled(context, settings.externalAppComponent)) {
                "当前：$label"
            } else {
                "当前：$label（应用已卸载，点击重新选择）"
            }
        }
        startTabValue?.text = "当前：" + SettingsStore.startTabLabel(settings.startTab)
        webTitleValue?.text = "当前：" + settings.webTitle
        webLimitValue?.text = "当前：" + SettingsStore.webUploadLimitLabel(settings.webUploadLimitMb)
        webUploadSwitch?.isChecked = settings.webUploadEnabled
        webDeleteSwitch?.isChecked = settings.webDeleteEnabled
        webModifySwitch?.isChecked = settings.webModifyEnabled
        webTextSwitch?.isChecked = settings.webTextEnabled
        batteryButton?.let { button ->
            val ignoring = UiUtils.isIgnoringBatteryOptimizations(context)
            button.text = if (ignoring) "已允许后台运行 ✔" else "允许后台运行（关闭电池优化，推荐）"
            button.isEnabled = !ignoring
        }

        val status = LanServerService.status.value
        statusInfo?.text = buildString {
            append("服务状态：").append(if (status.running) "运行中" else "已停止")
            append(" · 端口 ").append(status.port)
            append(" · ").append(if (NetworkUtils.hasLanConnection(context)) "已连接局域网" else "未连接局域网")
            status.url?.let { append("\n访问地址：").append(it) }
        }

        val usingPublic = StorageManager.isUsingPublicDir()
        val access = if (StorageManager.hasAllFilesAccess()) "已授权" else "未授权"
        val (total, free) = StorageManager.volumeStats()
        storageInfo?.text = buildString {
            append("当前位置：").append(StorageManager.root().absolutePath)
            append("\n所有文件访问权限：").append(access)
            if (!usingPublic) {
                append("\n（未能使用「内部存储/局域网文件」，已改为应用专属目录）")
            }
            if (total > 0) {
                append("\n可用空间 ").append(FormatUtils.formatSize(free))
                append(" / 共 ").append(FormatUtils.formatSize(total))
            }
        }

        val needPermission = settings.usePublicDir && !StorageManager.hasAllFilesAccess()
        grantButton?.visibility = if (needPermission) View.VISIBLE else View.GONE

        aboutInfo?.text = buildString {
            append(AppInfo.APP_NAME).append(" ").append(AppInfo.versionName(context))
            append("\n文件保存在手机本地，网页端与手机端操作同一个目录。")
            append("\n默认仅监听局域网，仅供同一 Wi-Fi 下的设备访问。")
            append("\n\n提示：Android 15+ 对前台服务有运行时长限制，")
            append("长时间后台运行后重新打开 App 即可恢复服务。")
        }

        view.contentDescription = status.statusLabel
        suppressListeners = false
    }
}