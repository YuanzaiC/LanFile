package com.yuanzai.lanfile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.yuanzai.lanfile.LanFileApp
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.ExternalOpener
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.MessageRepository
import com.yuanzai.lanfile.core.NetworkUtils
import com.yuanzai.lanfile.core.SettingsStore
import com.yuanzai.lanfile.core.StorageManager
import com.yuanzai.lanfile.model.Message
import com.yuanzai.lanfile.model.ServerStatus
import com.yuanzai.lanfile.server.LanServerHolder
import com.yuanzai.lanfile.service.LanServerService
import kotlinx.coroutines.launch

/**
 * 首页：服务状态、本机 IP、访问地址、最近收到的消息。
 * IP / Wi-Fi 状态每 2 秒刷新一次，网络变化时自动更新。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var settings: SettingsStore
    private val handler = Handler(Looper.getMainLooper())

    private var dot: View? = null
    private var statusText: TextView? = null
    private var uptimeText: TextView? = null
    private var ipText: TextView? = null
    private var urlText: TextView? = null
    private var rootText: TextView? = null
    private var hintText: TextView? = null
    private var networkText: TextView? = null
    private var detailText: TextView? = null
    private var messageCount: TextView? = null
    private var noMessages: TextView? = null
    private var recentContainer: LinearLayout? = null

    private var lastStatus: ServerStatus = ServerStatus()

    private val pollTask = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val messageListener: (List<Message>) -> Unit = {
        handler.post { renderRecentMessages() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dot = view.findViewById(R.id.status_dot)
        statusText = view.findViewById(R.id.tv_status)
        uptimeText = view.findViewById(R.id.tv_uptime)
        ipText = view.findViewById(R.id.tv_ip)
        urlText = view.findViewById(R.id.tv_url)
        rootText = view.findViewById(R.id.tv_root)
        hintText = view.findViewById(R.id.tv_hint)
        networkText = view.findViewById(R.id.tv_network)
        detailText = view.findViewById(R.id.tv_detail)
        messageCount = view.findViewById(R.id.tv_message_count)
        noMessages = view.findViewById(R.id.tv_no_messages)
        recentContainer = view.findViewById(R.id.layout_recent_messages)

        // 过渡动画：按钮按压反馈 + 卡片淡入上浮
        UiUtils.addPressFeedbackRecursive(view)
        UiUtils.fadeInUp(
            view.findViewById(R.id.hero_card),
            view.findViewById(R.id.btn_files),
            view.findViewById(R.id.card_recent_messages)
        )

        view.findViewById<MaterialButton>(R.id.btn_copy_address).setOnClickListener {
            val url = currentStatus().url
            if (url == null) {
                UiUtils.toast(requireContext(), "当前没有可用的局域网地址")
            } else {
                UiUtils.copyToClipboard(requireContext(), "访问地址", url)
            }
        }

        view.findViewById<MaterialButton>(R.id.btn_open_browser).setOnClickListener {
            val url = currentStatus().url
            if (url == null) {
                UiUtils.toast(requireContext(), "当前没有可用的局域网地址")
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                UiUtils.toast(requireContext(), "无法打开浏览器：${e.message ?: "未知错误"}")
            }
        }

        view.findViewById<View>(R.id.btn_files).setOnClickListener {
            (activity as? MainActivity)?.showTab(R.id.tab_files)
        }
        view.findViewById<View>(R.id.btn_open_files).setOnClickListener {
            (activity as? MainActivity)?.showTab(R.id.tab_files)
        }
        // 最近收到的消息：点卡片任意位置都进消息页（不再需要「全部」按钮）
        view.findViewById<View>(R.id.card_recent_messages).setOnClickListener {
            (activity as? MainActivity)?.showTab(R.id.tab_messages)
        }
        view.findViewById<MaterialButton>(R.id.btn_open_folder_app).setOnClickListener {
            openFolderWithOtherApp()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LanServerService.status.collect { status ->
                    lastStatus = status
                    render()
                }
            }
        }
        renderRecentMessages()
    }

    override fun onResume() {
        super.onResume()
        MessageRepository.addListener(messageListener)
        handler.removeCallbacks(pollTask)
        handler.post(pollTask)
    }

    override fun onPause() {
        super.onPause()
        MessageRepository.removeListener(messageListener)
        handler.removeCallbacks(pollTask)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(pollTask)
        dot = null
        statusText = null
        uptimeText = null
        ipText = null
        urlText = null
        rootText = null
        hintText = null
        networkText = null
        detailText = null
        messageCount = null
        noMessages = null
        recentContainer = null
        super.onDestroyView()
    }

    /** 合并服务状态与实时 IP / 计数（定时刷新时也能反映 IP 变化）。 */
    private fun currentStatus(): ServerStatus {
        val context = context ?: return lastStatus
        return lastStatus.copy(
            ip = NetworkUtils.primaryIpv4(),
            port = LanServerHolder.boundPort() ?: lastStatus.port,
            running = lastStatus.running || LanServerHolder.isRunning(),
            rootPath = StorageManager.root().absolutePath,
            usingPublicDir = StorageManager.isUsingPublicDir(),
            networkAvailable = NetworkUtils.hasLanConnection(context)
        )
    }

    private fun render() {
        val view = view ?: return
        val context = context ?: return
        val status = currentStatus()
        val running = status.running

        val needsLocalNetwork = (activity as? MainActivity)?.needsLocalNetworkPermission() == true
        val colorRes = when {
            needsLocalNetwork -> R.color.status_error
            status.error != null -> R.color.status_error
            running && status.url != null -> R.color.status_running
            else -> R.color.status_stopped
        }
        dot?.backgroundTintList = ContextCompat.getColorStateList(context, colorRes)

        statusText?.text = when {
            // 权限被拒绝时绝不能显示“服务正常”——那会误导人
            needsLocalNetwork -> "本地网络权限未授权：其他设备无法访问"
            status.error != null -> status.error
            running && status.url != null -> "服务运行中"
            running -> "服务运行中，但未连接局域网"
            else -> "服务已停止"
        }

        ipText?.text = status.ipLabel
        urlText?.text = status.url ?: "—"
        rootText?.text = "存储目录：${status.rootPath}"

        uptimeText?.text = if (running && status.startedAt > 0) {
            "已运行 " + FormatUtils.formatDuration(status.startedAt)
        } else {
            ""
        }

        val details = ArrayList<String>(3)
        details.add(NetworkUtils.describe(context))
        if (needsLocalNetwork) details.add("本地网络权限：未授权")
        detailText?.text = details.joinToString(" · ")

        val addresses = NetworkUtils.interfaces()
        val networkLines = ArrayList<String>(2)
        networkLines.add(
            if (addresses.size > 1) {
                "本机可用地址：" + addresses.joinToString("、") { "${it.second}（${it.first}）" } +
                    "，其他设备任选其一在浏览器打开"
            } else {
                "其他设备请在浏览器输入上面的访问地址；路由器重新分配 IP 后地址会变，" +
                    "务必以本页显示为准（可点「复制地址」）"
            }
        )
        NetworkUtils.accessWarning(context)?.let { networkLines.add("⚠ " + it) }
        networkText?.text = networkLines.joinToString("\n")

        val hints = ArrayList<String>(3)
        if (needsLocalNetwork) {
            hints.add("⚠ 本地网络权限未授权：Android 16 起，没有它局域网设备连接会被系统拦掉。点此处授权。")
        }
        LanFileApp.instance.storageError?.let { hints.add(it) }
        if (settings.usePublicDir && !StorageManager.hasAllFilesAccess()) {
            hints.add("未获得「所有文件访问权限」，文件暂存于应用专属目录。点击此处去设置页授权。")
        }
        val hint = hints.firstOrNull()
        hintText?.visibility = if (hint == null) View.GONE else View.VISIBLE
        hintText?.text = hint
        hintText?.setOnClickListener {
            if (needsLocalNetwork) {
                (activity as? MainActivity)?.requestLocalNetworkPermission()
            } else {
                (activity as? MainActivity)?.showTab(R.id.tab_settings)
            }
        }

        view.contentDescription = status.statusLabel
    }

    private fun renderRecentMessages() {
        val container = recentContainer ?: return
        val context = context ?: return
        val messages = MessageRepository.all(RECENT_MESSAGE_COUNT)
        container.removeAllViews()
        noMessages?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        val total = MessageRepository.count()
        messageCount?.text = if (total > 0) "共 $total 条" else "与电脑网页互发文字"

        for (message in messages) {
            val row = TextView(context)
            row.text = "${FormatUtils.formatMessageTime(message.time)}  ${message.text}"
            row.textSize = 13f
            row.maxLines = 2
            row.ellipsize = TextUtils.TruncateAt.END
            row.setPadding(0, dp(4), 0, dp(4))
            container.addView(row)
        }
    }

    private fun dp(value: Int): Int =
        (value * (resources.displayMetrics.density)).toInt()

    /**
     * 「用其他应用打开此目录」：
     * 设置里选过就直接用那个应用；未设置（或所选应用已被卸载）时先弹选择框，
     * 选完记住，之后可在「设置 → 通用 → 打开文件夹的应用」中修改。
     */
    private fun openFolderWithOtherApp() {
        val context = context ?: return
        val dir = StorageManager.root()
        val stored = settings.externalAppComponent
        if (stored.isNotEmpty()) {
            if (ExternalOpener.start(context, dir, stored)) return
            // 应用被卸载或打开失败：清掉旧设置再让用户重新选
            if (!ExternalOpener.isInstalled(context, stored)) {
                settings.externalAppComponent = ""
            }
            UiUtils.toast(context, "之前选择的应用已无法打开此目录，请重新选择", long = true)
        }
        UiUtils.chooseExternalApp(context, dir, settings.externalAppComponent) { chosen ->
            if (chosen.isEmpty()) {
                settings.externalAppComponent = ""
                val opened = ExternalOpener.startChooser(context, dir)
                if (!opened) {
                    UiUtils.toast(context, "没有找到可打开此目录的应用，请先安装文件管理器", long = true)
                }
                return@chooseExternalApp
            }
            val selected = ExternalOpener.parse(chosen)
            if (selected != null && ExternalOpener.start(context, dir, selected.component, selected.flavor)) {
                settings.externalAppComponent = chosen
                UiUtils.toast(context, "已记住该应用，可在「设置 → 通用」中修改")
            } else {
                UiUtils.toast(context, "该应用无法打开此目录，请更换其他应用", long = true)
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val RECENT_MESSAGE_COUNT = 3
    }
}
