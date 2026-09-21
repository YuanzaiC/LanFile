package com.yuanzai.lanfile.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.MessageRepository
import com.yuanzai.lanfile.core.WebClient
import com.yuanzai.lanfile.model.Message

/** 文字消息列表适配器：本机发出的靠右（主题色气泡），网页发来的靠左（浅色气泡）。 */
class MessageListAdapter(
    private val onCopy: (Message) -> Unit,
    private val onDelete: (Message) -> Unit
) : RecyclerView.Adapter<MessageListAdapter.MessageViewHolder>() {

    private val items = ArrayList<Message>()

    fun submit(messages: List<Message>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val row: LinearLayout = itemView.findViewById(R.id.message_row)
        private val bubble: LinearLayout = itemView.findViewById(R.id.message_bubble)
        private val text: TextView = itemView.findViewById(R.id.tv_message_text)
        private val meta: TextView = itemView.findViewById(R.id.tv_message_meta)
        private val deviceIcon: android.widget.ImageView = itemView.findViewById(R.id.iv_message_device)
        private val copy: ImageButton = itemView.findViewById(R.id.btn_message_copy)
        private val delete: ImageButton = itemView.findViewById(R.id.btn_message_delete)

        fun bind(message: Message) {
            val outgoing = message.source == "android"

            text.text = message.text
            // 来源标识：安卓 / 电脑 · Edge / iPhone · Safari 等
            val device = message.device.ifBlank { MessageRepository.defaultDevice(message.source) }
            meta.text = "${FormatUtils.formatMessageTime(message.time)} · $device"
            deviceIcon.setImageResource(iconFor(message, device))

            row.gravity = if (outgoing) Gravity.END else Gravity.START
            bubble.setBackgroundResource(if (outgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)

            if (outgoing) {
                text.setTextColor(0xFFFFFFFF.toInt())
                meta.setTextColor(0xCCFFFFFF.toInt())
            } else {
                text.setTextColor(
                    MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurface)
                )
                meta.setTextColor(
                    MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
            val iconColor = if (outgoing) 0xE6FFFFFF.toInt() else
                MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
            copy.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            delete.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            deviceIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)

            copy.setOnClickListener { onCopy(message) }
            delete.setOnClickListener { onDelete(message) }
        }

        /** 按来源设备挑图标：本机=安卓手机，网页端按 UA 判定电脑 / 手机 / 平板。 */
        private fun iconFor(message: Message, device: String): Int {
            if (message.source == "android") return R.drawable.ic_phone_android
            return when (WebClient.deviceKind(device)) {
                WebClient.DeviceKind.COMPUTER -> R.drawable.ic_computer
                WebClient.DeviceKind.ANDROID -> R.drawable.ic_phone_android
                WebClient.DeviceKind.IPHONE -> R.drawable.ic_phone_iphone
                WebClient.DeviceKind.IPAD -> R.drawable.ic_tablet
                WebClient.DeviceKind.OTHER -> R.drawable.ic_devices
            }
        }
    }
}