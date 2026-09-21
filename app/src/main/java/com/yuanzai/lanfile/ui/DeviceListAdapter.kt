package com.yuanzai.lanfile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.WebClient

/** 设备列表：显示访问过网页端的设备，可点击进入设备详情（封禁 / 单独授权）。 */
class DeviceListAdapter(
    private val onOpen: (WebClient) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    private val items = ArrayList<WebClient>()

    fun submit(clients: List<WebClient>) {
        items.clear()
        items.addAll(clients)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.iv_device_icon)
        private val ipText: TextView = itemView.findViewById(R.id.tv_device_ip)
        private val infoText: TextView = itemView.findViewById(R.id.tv_device_info)
        private val stateText: TextView = itemView.findViewById(R.id.tv_device_state)
        private val moreButton: ImageButton = itemView.findViewById(R.id.btn_device_more)

        fun bind(client: WebClient) {
            val context = itemView.context
            ipText.text = client.ip
            infoText.text = buildString {
                append(client.deviceLabel())
                append(" · 最近访问 ").append(FormatUtils.formatAgo(client.lastSeen))
                append(" · 共 ").append(client.requests).append(" 次请求")
            }
            // 电脑 / 安卓 / iPhone / 平板用不同图标
            icon.setImageResource(
                when (client.kind) {
                    WebClient.DeviceKind.COMPUTER -> R.drawable.ic_computer
                    WebClient.DeviceKind.ANDROID -> R.drawable.ic_phone_android
                    WebClient.DeviceKind.IPHONE -> R.drawable.ic_phone_iphone
                    WebClient.DeviceKind.IPAD -> R.drawable.ic_tablet
                    WebClient.DeviceKind.OTHER -> R.drawable.ic_devices
                }
            )

            val stateRes: Int
            val stateLabel: String
            when {
                client.blocked -> {
                    stateRes = R.color.status_error
                    stateLabel = "已禁止访问"
                    icon.imageTintList = ContextCompat.getColorStateList(context, R.color.status_error)
                }
                client.hasCustomPermissions() -> {
                    stateRes = R.color.status_running
                    stateLabel = "已单独设置权限"
                }
                else -> {
                    stateRes = R.color.status_stopped
                    stateLabel = "跟随全局设置"
                }
            }
            if (!client.blocked) {
                icon.imageTintList = ContextCompat.getColorStateList(context, R.color.icon_chip_tint)
            }
            stateText.text = stateLabel
            stateText.setTextColor(ContextCompat.getColor(context, stateRes))

            itemView.setOnClickListener { onOpen(client) }
            moreButton.setOnClickListener { onOpen(client) }
        }
    }

    companion object {
        /** 列表判空：没有记录时显示空状态。 */
        fun isEmpty(clients: List<WebClient>): Boolean = clients.isEmpty()
    }
}