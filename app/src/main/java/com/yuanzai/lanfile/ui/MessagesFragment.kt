package com.yuanzai.lanfile.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.MessageRepository
import com.yuanzai.lanfile.model.Message

/**
 * 文字消息页：显示网页端发来的文字（也可以从手机发出去），支持复制 / 删除 / 清空。
 */
class MessagesFragment : Fragment(R.layout.fragment_messages) {

    private lateinit var adapter: MessageListAdapter
    private val handler = Handler(Looper.getMainLooper())

    private var recycler: RecyclerView? = null
    private var emptyView: View? = null
    private var input: EditText? = null

    private val repositoryListener: (List<Message>) -> Unit = { messages ->
        handler.post { render(messages) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler_messages)
        emptyView = view.findViewById(R.id.tv_no_messages)
        input = view.findViewById(R.id.et_message)
        UiUtils.addPressFeedbackRecursive(view)

        adapter = MessageListAdapter(
            onCopy = { message ->
                UiUtils.copyToClipboard(requireContext(), "消息", message.text)
            },
            onDelete = { message ->
                MessageRepository.delete(listOf(message.id))
                UiUtils.toast(requireContext(), "该消息已删除")
            }
        )
        // 聊天式布局：最新的消息贴在底部（数据本身是「新的在前」，用 reverseLayout 倒着画）
        recycler?.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }
        recycler?.adapter = adapter

        view.findViewById<ImageButton>(R.id.btn_refresh_messages).setOnClickListener {
            render(MessageRepository.all(MessageRepository.MAX_MESSAGES))
            UiUtils.toast(requireContext(), "消息列表已刷新")
        }
        view.findViewById<ImageButton>(R.id.btn_clear_messages).setOnClickListener {
            if (MessageRepository.count() == 0) {
                UiUtils.toast(requireContext(), "当前没有消息")
                return@setOnClickListener
            }
            UiUtils.confirmDialog(
                requireContext(),
                "清空消息记录",
                "确定要清空全部消息吗？此操作不可恢复。",
                confirmText = "清空"
            ) {
                val removed = MessageRepository.clear()
                UiUtils.toast(requireContext(), "已清空 $removed 条消息")
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_send_message).setOnClickListener { send() }

        render(MessageRepository.all(MessageRepository.MAX_MESSAGES))
    }

    override fun onStart() {
        super.onStart()
        MessageRepository.addListener(repositoryListener)
        render(MessageRepository.all(MessageRepository.MAX_MESSAGES))
    }

    override fun onStop() {
        MessageRepository.removeListener(repositoryListener)
        super.onStop()
    }

    override fun onDestroyView() {
        recycler = null
        emptyView = null
        input = null
        super.onDestroyView()
    }

    private fun send() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            UiUtils.toast(requireContext(), "请输入要发送的文字内容")
            return
        }
        try {
            MessageRepository.add(text, "android")
            input?.setText("")
            UiUtils.hideKeyboard(requireContext(), input ?: requireView())
            UiUtils.toast(requireContext(), "文字已发送")
        } catch (e: Exception) {
            UiUtils.toast(requireContext(), "发送失败：${e.message ?: "未知错误"}")
        }
    }

    private fun render(messages: List<Message>) {
        if (!isAdded) return
        adapter.submit(messages)
        emptyView?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        if (messages.isNotEmpty()) recycler?.scrollToPosition(0)
    }
}
