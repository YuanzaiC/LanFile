package com.yuanzai.lanfile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.LocalNetworkPermission
import com.yuanzai.lanfile.core.SettingsStore
import com.yuanzai.lanfile.model.ServerStatus
import com.yuanzai.lanfile.server.LanServerHolder
import com.yuanzai.lanfile.service.LanServerService
import kotlinx.coroutines.launch

/**
 * 主界面：底部导航（首页 / 文件 / 消息 / 设置）。
 * 打开 App 自动启动局域网服务，切到后台时由前台服务继续运行。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var bottomNav: BottomNavigationView
    private var serviceBall: FloatingActionButton? = null
    private val fragments = HashMap<Int, Fragment>()
    private var currentTab = R.id.tab_home
    private var suppressTabListener = false
    /** 小球的基础底边距：正好落在底部导航栏上方，绝不压住导航栏。 */
    private var ballBaseBottom = 0
    /** 上一次的小球状态色，用于只在颜色变化时播放淡入动画。 */
    private var lastBallColorRes = 0

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                UiUtils.toast(this, "未授予通知权限：后台运行时通知将不显示")
            }
        }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                UiUtils.toast(this, "已获得本地网络权限，正在重启服务…")
                // 授权后必须重建监听 socket，权限才对这条监听生效
                LanServerService.restart(this, settings.backgroundRun)
            } else {
                UiUtils.toast(this, "未授权本地网络权限：其他设备将无法访问，可在系统设置里手动开启")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        settings = SettingsStore(this)
        UiUtils.applyTheme(settings)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bottomNav = findViewById(R.id.bottom_nav)
        bottomNav.setOnItemSelectedListener { item ->
            if (!suppressTabListener) showTab(item.itemId)
            true
        }
        setupServiceBall()
        // 量出底部导航栏的真实高度后再摆小球，避免它压住导航栏上的按钮
        bottomNav.post {
            ballBaseBottom = bottomNav.height + dp(10)
            updateBallPosition(currentTab)
        }

        if (savedInstanceState == null) {
            showTab(tabIdOf(settings.startTab))
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.tab_home)
            showTab(currentTab)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTab == R.id.tab_files) {
                    val files = fragments[R.id.tab_files] as? FilesFragment
                    if (files != null && files.handleBackPressed()) return
                }
                if (currentTab != R.id.tab_home) {
                    // 先回到首页，再让用户按一次退出（showTab 内部会同步底部导航选中状态）
                    showTab(R.id.tab_home)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        requestNotificationPermissionIfNeeded()
        requestLocalNetworkPermissionIfNeeded()
        ensureServiceRunning()
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onDestroy() {
        // 用户主动退出 App 且未开启“后台运行”时，停止服务
        if (isFinishing && !settings.backgroundRun) {
            LanServerService.stop(this)
        }
        super.onDestroy()
    }

    /** 切换到指定底部导航页面。 */
    fun showTab(itemId: Int) {
        val tag = tagOf(itemId)
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        for ((id, fragment) in fragments) {
            if (id != itemId && fragment.isAdded) transaction.hide(fragment)
        }
        val existing = manager.findFragmentByTag(tag)
        if (existing == null) {
            val created = createFragment(itemId)
            fragments[itemId] = created
            transaction.add(R.id.main_container, created, tag)
        } else {
            fragments[itemId] = existing
            transaction.show(existing)
        }
        transaction.setCustomAnimations(R.anim.frag_in, R.anim.frag_out)
        val fromTab = currentTab
        transaction.commit()
        currentTab = itemId
        // 进场动画自己用 ViewPropertyAnimator 做一遍：不依赖 Fragment 自带动画，确保一定看得到
        // 往右边的页切就从右边滑进来，往左边的页切就从左边滑进来，方向感更明显
        animatePageIn(itemId, forward = menuIndexOf(itemId) >= menuIndexOf(fromTab))
        animateNavIcon(itemId)
        updateBallPosition(itemId)
        if (bottomNav.selectedItemId != itemId) {
            // 防止设置选中项时回调再次进入本方法（会重复添加 Fragment）
            suppressTabListener = true
            try {
                bottomNav.selectedItemId = itemId
            } finally {
                suppressTabListener = false
            }
        }
    }

    fun currentTabId(): Int = currentTab

    private fun tagOf(itemId: Int): String = "tab-$itemId"

    /** 设置里的「默认打开页面」→ 底部导航项。 */
    private fun tabIdOf(index: Int): Int = when (index) {
        SettingsStore.TAB_FILES -> R.id.tab_files
        SettingsStore.TAB_MESSAGES -> R.id.tab_messages
        SettingsStore.TAB_DEVICES -> R.id.tab_devices
        SettingsStore.TAB_SETTINGS -> R.id.tab_settings
        else -> R.id.tab_home
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.tab_files -> FilesFragment()
        R.id.tab_messages -> MessagesFragment()
        R.id.tab_devices -> DevicesFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private fun ensureServiceRunning() {
        if (!settings.autoStartServer) return
        if (LanServerService.status.value.running) return
        LanServerService.start(this, settings.backgroundRun)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Android 16（API 36）起，App 访问局域网需要 ACCESS_LOCAL_NETWORK 权限，
     * 没有它系统会直接拦掉局域网访问（包括接受局域网连入的连接）。
     * 只在真的存在该权限的系统上申请，老系统完全不受影响。
     */
    private fun requestLocalNetworkPermissionIfNeeded() {
        if (!LocalNetworkPermission.isRequired(this)) return
        if (LocalNetworkPermission.isGranted(this)) return
        requestLocalNetworkPermission()
    }

    /**
     * 右下角服务小球：
     *  - 单击：开启 / 关闭服务
     *  - 长按：重启服务
     *  - 颜色随状态变化（绿=运行中、灰=已停止、红=出错 / 缺本地网络权限）
     */
    private fun setupServiceBall() {
        val ball = findViewById<FloatingActionButton>(R.id.service_ball)
        serviceBall = ball
        ball.setOnClickListener {
            // 点击反馈：轻微缩放
            it.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
            val running = LanServerService.status.value.running || LanServerHolder.isRunning()
            if (running) {
                LanServerService.stop(this)
                UiUtils.toast(this, "局域网服务已关闭")
            } else {
                LanServerService.start(this, settings.backgroundRun)
                UiUtils.toast(this, "正在开启局域网服务…")
            }
        }
        ball.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            LanServerService.restart(this, settings.backgroundRun)
            UiUtils.toast(this, "局域网服务已重启")
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LanServerService.status.collect { status -> refreshServiceBall(status) }
            }
        }
        refreshServiceBall(LanServerService.status.value)
    }

    private fun refreshServiceBall(status: ServerStatus) {
        val ball = serviceBall ?: return
        val alert = needsLocalNetworkPermission() || status.error != null
        val running = status.running || LanServerHolder.isRunning()
        val colorRes = when {
            alert -> R.color.ball_alert_bg
            running -> R.color.ball_running_bg
            else -> R.color.ball_idle_bg
        }
        if (lastBallColorRes != colorRes) {
            // 状态色变化时缩放 + 淡入，避免颜色「啪」地一下跳过去
            lastBallColorRes = colorRes
            ball.alpha = 0.4f
            ball.scaleX = 0.7f
            ball.scaleY = 0.7f
            ball.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320L)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        }
        ball.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        ball.contentDescription = when {
            alert -> "服务异常或缺少本地网络权限，长按重启服务"
            running -> "点击关闭服务，长按重启服务"
            else -> "点击开启服务"
        }
        updateBallPosition(currentTab)
    }

    /**
     * 页面进场：淡入 + 从下往上轻微归位。
     * 用 ViewPropertyAnimator 直接作用在 Fragment 的根视图上，
     * 这样即使 Fragment 事务动画在某些机型上不生效，也能看到明显的过渡。
     */
    private fun animatePageIn(itemId: Int, forward: Boolean) {
        // 新添加的 Fragment 视图要在事务执行完才存在
        runCatching { supportFragmentManager.executePendingTransactions() }
        val target = fragments[itemId]?.view ?: return
        target.animate().cancel()
        target.alpha = 0f
        target.translationX = dp(if (forward) 28 else -28).toFloat()
        target.translationY = dp(8).toFloat()
        target.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(300L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun menuIndexOf(itemId: Int): Int {
        val menu = bottomNav.menu
        for (i in 0 until menu.size()) {
            if (menu.getItem(i).itemId == itemId) return i
        }
        return 0
    }

    /** 底部导航切换时，让选中的这一项弹一下（整个图标 + 文字一起）。 */
    private fun animateNavIcon(itemId: Int) {
        val menu = bottomNav.menu
        var index = -1
        for (i in 0 until menu.size()) {
            if (menu.getItem(i).itemId == itemId) {
                index = i
                break
            }
        }
        if (index < 0 || index >= bottomNav.childCount) return
        val itemView = bottomNav.getChildAt(index)
        // 整项弹一下（不依赖 Material 内部 id，稳）
        itemView.animate().cancel()
        itemView.scaleX = 0.82f
        itemView.scaleY = 0.82f
        itemView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(OvershootInterpolator(1.9f))
            .start()
        // 图标再单独放大一点，动感更明显
        itemView.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
            ?.let { icon ->
                icon.animate().cancel()
                icon.scaleX = 0.7f
                icon.scaleY = 0.7f
                icon.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(220L)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .withEndAction {
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(110L).start()
                    }
                    .start()
            }
    }

    /**
     * 小球的落点：始终在底部导航栏之上；文件页有「添加」按钮、消息页底部是输入框，
     * 在这两页再往上让一段，保证不遮挡任何可点的控件。
     */
    private fun updateBallPosition(itemId: Int) {
        val ball = serviceBall ?: return
        val params = ball.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val base = if (ballBaseBottom > 0) ballBaseBottom else bottomNav.height + dp(10)
        val extra = when (itemId) {
            R.id.tab_files -> dp(74)
            R.id.tab_messages -> dp(78)
            else -> 0
        }
        val bottom = base + extra
        if (params.bottomMargin == bottom) return
        params.bottomMargin = bottom
        ball.layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 供首页提示点击时调用：申请本地网络权限。 */
    fun requestLocalNetworkPermission() {
        if (!LocalNetworkPermission.isRequired(this)) {
            UiUtils.toast(this, "当前系统无需申请本地网络权限")
            return
        }
        if (LocalNetworkPermission.isGranted(this)) {
            UiUtils.toast(this, "本地网络权限已获得")
            return
        }
        runCatching { localNetworkPermissionLauncher.launch(LocalNetworkPermission.PERMISSION) }
            .onFailure {
                // 系统不再弹窗（用户选过“不再询问”）时，只能去设置页手动开
                UiUtils.toast(this, "请在本应用的系统权限里手动允许「附近的设备/本地网络」")
                LocalNetworkPermission.openAppSettings(this)
            }
    }

    /** 首页用来判断是否要显示醒目提示。 */
    fun needsLocalNetworkPermission(): Boolean =
        LocalNetworkPermission.isRequired(this) && !LocalNetworkPermission.isGranted(this)

    companion object {
        private const val KEY_TAB = "current_tab"
    }
}