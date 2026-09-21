package com.yuanzai.lanfile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.ExternalOpener
import com.yuanzai.lanfile.core.SettingsStore

/** 通用 UI 小工具：Toast / Snackbar / 各种弹窗 / 主题 / 外部应用选择。 */
object UiUtils {

    /** 「从全部已安装应用中选择…」这一项的内部标记。 */
    private const val ALL_APPS_MARKER = "\u0000all-apps"

    /** 应用主题设置（跟随系统 / 浅色 / 深色）。 */
    fun applyTheme(settings: SettingsStore) {
        AppCompatDelegate.setDefaultNightMode(
            when (settings.themeMode) {
                SettingsStore.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SettingsStore.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * 选择用哪个外部应用打开目录（文件管理器）。
     * 值为 `包名/类名|形态`（见 [ExternalOpener.encode]），空字符串表示「每次都询问」。
     */
    fun chooseExternalApp(
        context: Context,
        dir: java.io.File,
        current: String,
        onChosen: (String) -> Unit
    ) {
        if (ExternalOpener.documentUri(context, dir) == null) {
            toast(
                context,
                "当前目录位于应用专属存储，其他应用无权访问。请在「设置 → 存储目录」中切换到内部存储。",
                long = true
            )
            return
        }
        val candidates = ExternalOpener.candidates(context, dir)
        showExternalAppPicker(context, dir, candidates, current, onChosen)
    }

    private fun showExternalAppPicker(
        context: Context,
        dir: java.io.File,
        candidates: List<ExternalOpener.Candidate>,
        current: String,
        onChosen: (String) -> Unit
    ) {
        val labels = ArrayList<String>(candidates.size + 2)
        val values = ArrayList<String>(candidates.size + 2)
        labels.add("每次都询问（交给系统选择器）")
        values.add("")
        for (candidate in candidates) {
            labels.add(if (candidate.component == ExternalOpener.parse(current)?.component) {
                "${candidate.label}（当前）"
            } else {
                candidate.label
            })
            values.add(ExternalOpener.encode(candidate.component, candidate.flavor))
        }
        // 候选里没有目标应用时，允许从全部已安装应用里挑（例如 MT 管理器）
        labels.add("从全部已安装应用中选择…")
        values.add(ALL_APPS_MARKER)

        val selectedComponent = ExternalOpener.parse(current)?.component
        val checked = values.indexOfFirst { it.startsWith("$selectedComponent|") }.takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(context)
            .setTitle("打开文件夹的应用")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val value = values[which]
                if (value == ALL_APPS_MARKER) {
                    chooseFromInstalledApps(context, dir, current, onChosen)
                } else {
                    onChosen(value)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 兜底：列出全部已安装应用，让用户手动指定用哪个打开目录。 */
    private fun chooseFromInstalledApps(
        context: Context,
        dir: java.io.File,
        current: String,
        onChosen: (String) -> Unit
    ) {
        val apps = ExternalOpener.installedApps(context)
        if (apps.isEmpty()) {
            toast(context, "没有查询到可启动的应用", long = true)
            return
        }
        val labels = apps.map { app ->
            if (app.component == ExternalOpener.parse(current)?.component) "${app.label}（当前）" else app.label
        }.toTypedArray()
        val selectedComponent = ExternalOpener.parse(current)?.component
        val checked = apps.indexOfFirst { it.component == selectedComponent }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle("选择应用")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val app = apps[which]
                // 先试标准形态，能打开就直接记住；打不开再提示换一个
                if (ExternalOpener.start(context, dir, app.component, ExternalOpener.Flavor.AUTO)) {
                    onChosen(ExternalOpener.encode(app.component, ExternalOpener.Flavor.AUTO))
                } else {
                    toast(context, "${app.label} 无法打开此目录，请换一个应用", long = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 给按钮 / 可点卡片加一段轻量的按压过渡：按下时轻微缩小，抬起时回弹。
     * 只在触摸过程中插入动画，不影响点击事件与涟漪效果。
     */
    fun addPressFeedback(vararg views: View) {
        val press = DecelerateInterpolator()
        val release = OvershootInterpolator(1.35f)
        for (view in views) {
            view.setOnTouchListener { touched, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touched.animate().cancel()
                        touched.animate()
                            .scaleX(0.93f).scaleY(0.93f)
                            .setDuration(80L).setInterpolator(press).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touched.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(170L).setInterpolator(release).start()
                }
                false
            }
        }
    }

    /**
     * 递归给一个页面里所有 MaterialButton / 可点卡片加上按压过渡动画，
     * 这样每个页面的按钮切换手感都一致。
     */
    fun addPressFeedbackRecursive(root: View?) {
        if (root == null) return
        val isButton = root is android.widget.Button || root is android.widget.ImageButton
        val isCard = root is com.google.android.material.card.MaterialCardView && root.isClickable
        if (isButton || isCard) addPressFeedback(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addPressFeedbackRecursive(root.getChildAt(index))
        }
    }

    /** 列表 / 卡片切换时的淡入上浮，让内容出现得柔和一些。 */
    fun fadeInUp(vararg views: View, startDelay: Long = 0L, duration: Long = 220L) {
        for ((index, view) in views.withIndex()) {
            view.alpha = 0f
            view.translationY = 18f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + index * 40L)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun toast(context: Context?, message: String, long: Boolean = false) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun toast(view: View?, message: String, long: Boolean = false) {
        val ctx = view?.context ?: return
        Toast.makeText(ctx, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun snackbar(view: View, message: String, actionText: String? = null, action: (() -> Unit)? = null) {
        val bar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
        if (actionText != null && action != null) {
            bar.setAction(actionText) { action() }
        }
        bar.show()
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(context, "复制失败：剪贴板不可用")
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(context, "已复制：$text")
    }

    /**
     * 单输入框弹窗（新建文件夹 / 重命名）。
     * 名称为空或包含非法字符时给出提示并不关闭弹窗。
     */
    fun inputDialog(
        context: Context,
        title: String,
        hint: String,
        initial: String? = null,
        confirmText: String = "确定",
        onConfirm: (String) -> Unit
    ) {
        val view = View.inflate(context, R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.et_input)
        input.hint = hint
        if (!initial.isNullOrEmpty()) {
            input.setText(initial)
            input.setSelection(initial.length)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                when {
                    value.isEmpty() -> input.error = "名称不能为空"
                    value.contains('/') || value.contains('\\') -> input.error = "名称不能包含 / 或 \\"
                    value == "." || value == ".." -> input.error = "名称不合法"
                    else -> {
                        hideKeyboard(context, input)
                        dialog.dismiss()
                        onConfirm(value)
                    }
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    fun confirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "删除",
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .show()
    }

    fun hideKeyboard(context: Context, view: View) {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** 是否已经加入电池优化白名单（国产 ROM 上后台稳定运行的前提）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /** 申请加入电池优化白名单；系统不允许弹窗时退回到电池优化设置页。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (inner: Exception) {
                toast(context, "请手动在「电池 → 应用耗电管理」中把本应用设为“无限制”")
            }
        }
    }

    /** 单选弹窗（主题、透明效果这类少量选项）。 */
    fun chooseDialog(
        context: Context,
        title: String,
        options: Array<String>,
        checkedIndex: Int,
        onChosen: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                dialog.dismiss()
                onChosen(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
