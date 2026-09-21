package com.yuanzai.lanfile.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.ClientRegistry
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.SettingsStore
import com.yuanzai.lanfile.core.WebClient

/**
 * 设备页：列出访问过网页端的设备，可以对单台设备封禁 / 解除封禁，
 * 也可以给它单独分配权限（覆盖「设置 → 网页端」的全局开关）。
 */
class DevicesFragment : Fragment(R.layout.fragment_devices) {

    private lateinit var settings: SettingsStore
    private lateinit var adapter: DeviceListAdapter

    private var recycler: RecyclerView? = null
    private var emptyView: View? = null
    private var summary: TextView? = null

    private val registryListener: (List<WebClient>) -> Unit = { clients ->
        if (isAdded) render(clients)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler_devices)
        emptyView = view.findViewById(R.id.layout_devices_empty)
        summary = view.findViewById(R.id.tv_devices_summary)

        adapter = DeviceListAdapter { client -> showDeviceDialog(client) }
        recycler?.layoutManager = LinearLayoutManager(requireContext())
        recycler?.adapter = adapter
        UiUtils.addPressFeedbackRecursive(view)

        view.findViewById<View>(R.id.btn_devices_clear).setOnClickListener {
            if (ClientRegistry.count() == 0) {
                UiUtils.toast(requireContext(), "当前没有访问记录")
                return@setOnClickListener
            }
            UiUtils.confirmDialog(
                requireContext(),
                "清空访问记录",
                "将删除全部设备的访问记录与单独授权设置（已封禁的设备也会一并移除）。此操作不可恢复。",
                confirmText = "清空"
            ) {
                ClientRegistry.clear()
                UiUtils.toast(requireContext(), "访问记录已清空")
            }
        }

        render(ClientRegistry.all())
    }

    override fun onStart() {
        super.onStart()
        ClientRegistry.addListener(registryListener)
        render(ClientRegistry.all())
    }

    override fun onStop() {
        ClientRegistry.removeListener(registryListener)
        super.onStop()
    }

    override fun onDestroyView() {
        recycler?.adapter = null
        recycler = null
        emptyView = null
        summary = null
        super.onDestroyView()
    }

    private fun render(clients: List<WebClient>) {
        if (!isAdded) return
        adapter.submit(clients)
        emptyView?.visibility = if (clients.isEmpty()) View.VISIBLE else View.GONE
        val blocked = clients.count { it.blocked }
        summary?.text = if (clients.isEmpty()) {
            "访问过网页端设备的访问记录与权限"
        } else {
            "共 ${clients.size} 台设备" + if (blocked > 0) " · 已封禁 $blocked 台" else ""
        }
    }

    // ------------------------------------------------------------- 设备详情

    private fun showDeviceDialog(client: WebClient) {
        val context = context ?: return
        val view = View.inflate(context, R.layout.dialog_device, null)
        val info = view.findViewById<TextView>(R.id.tv_device_dialog_info)
        val blockSwitch = view.findViewById<MaterialSwitch>(R.id.sw_device_block)
        val hint = view.findViewById<TextView>(R.id.tv_device_dialog_hint)

        val valueViews = mapOf(
            ClientRegistry.KIND_UPLOAD to view.findViewById<TextView>(R.id.tv_device_perm_upload),
            ClientRegistry.KIND_DELETE to view.findViewById<TextView>(R.id.tv_device_perm_delete),
            ClientRegistry.KIND_MODIFY to view.findViewById<TextView>(R.id.tv_device_perm_modify),
            ClientRegistry.KIND_TEXT to view.findViewById<TextView>(R.id.tv_device_perm_text)
        )

        var suppress = true

        fun refresh() {
            info.text = buildString {
                append("设备地址：").append(client.ip)
                append("\n设备类型：").append(client.deviceLabel())
                append("\n首次访问：").append(FormatUtils.formatMessageTime(client.firstSeen))
                append("\n最近访问：").append(FormatUtils.formatAgo(client.lastSeen))
                append("\n累计请求：").append(client.requests).append(" 次")
            }
            suppress = true
            blockSwitch.isChecked = client.blocked
            suppress = false
            valueViews.forEach { (kind, textView) ->
                textView.text = client.permissionLabel(kind, globalAllowed(kind))
            }
            hint.text = if (client.blocked) {
                "该设备的所有请求都会被拒绝，网页端显示「此设备已被禁止访问」。"
            } else {
                "权限改动立即生效：该设备刷新网页后按新权限显示可用功能。"
            }
        }

        fun pickPermission(kind: String, title: String) {
            val global = globalAllowed(kind)
            val labels = arrayOf(
                "跟随全局设置（${if (global) "允许" else "禁止"}）",
                "允许",
                "禁止"
            )
            val checked = when (client.permission(kind)) {
                null -> 0
                true -> 1
                false -> 2
            }
            UiUtils.chooseDialog(context, title, labels, checked) { index ->
                val value = when (index) {
                    1 -> true
                    2 -> false
                    else -> null
                }
                // 写回仓库会同步到同一个 client 实例上
                ClientRegistry.setPermission(client.ip, kind, value)
                refresh()
            }
        }

        view.findViewById<View>(R.id.row_device_block).setOnClickListener {
            blockSwitch.isChecked = !blockSwitch.isChecked
        }
        blockSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            client.blocked = checked
            ClientRegistry.setBlocked(client.ip, checked)
            refresh()
            UiUtils.toast(
                context,
                if (checked) {
                    "已禁止 ${client.ip} 访问，该设备刷新网页后将看到禁止提示"
                } else {
                    "已恢复 ${client.ip} 的访问权限"
                }
            )
        }
        view.findViewById<View>(R.id.row_device_perm_upload).setOnClickListener {
            pickPermission(ClientRegistry.KIND_UPLOAD, "上传文件")
        }
        view.findViewById<View>(R.id.row_device_perm_delete).setOnClickListener {
            pickPermission(ClientRegistry.KIND_DELETE, "删除文件")
        }
        view.findViewById<View>(R.id.row_device_perm_modify).setOnClickListener {
            pickPermission(ClientRegistry.KIND_MODIFY, "重命名 / 新建 / 移动 / 复制")
        }
        view.findViewById<View>(R.id.row_device_perm_text).setOnClickListener {
            pickPermission(ClientRegistry.KIND_TEXT, "文字互传")
        }

        refresh()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("设备详情")
            .setView(view)
            .setNeutralButton("删除记录", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                UiUtils.confirmDialog(
                    context,
                    "删除访问记录",
                    "将删除 ${client.ip} 的访问记录与单独授权设置。",
                    confirmText = "删除"
                ) {
                    ClientRegistry.remove(client.ip)
                    dialog.dismiss()
                    UiUtils.toast(context, "该设备的访问记录已删除")
                }
            }
        }
        dialog.show()
    }

    /** 该权限在全局设置里是否允许。 */
    private fun globalAllowed(kind: String): Boolean = when (kind) {
        ClientRegistry.KIND_UPLOAD -> settings.webUploadEnabled
        ClientRegistry.KIND_DELETE -> settings.webDeleteEnabled
        ClientRegistry.KIND_MODIFY -> settings.webModifyEnabled
        else -> settings.webTextEnabled
    }
}