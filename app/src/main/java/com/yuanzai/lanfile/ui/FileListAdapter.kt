package com.yuanzai.lanfile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.FileTypes
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.model.FileEntry

/**
 * 手机端文件列表适配器：支持单点打开、长按多选、每项更多菜单。
 */
class FileListAdapter(
    private val onItemClick: (FileEntry) -> Unit,
    private val onItemLongClick: (FileEntry) -> Unit,
    private val onMoreClick: (FileEntry, View) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    private val items = ArrayList<FileEntry>()
    private var selected: Set<String> = emptySet()
    private var multiSelect = false

    /** 搜索结果中显示所在目录。 */
    var showPath: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, items.size)
            }
        }

    fun submit(newItems: List<FileEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelection(paths: Set<String>, multiSelectMode: Boolean) {
        if (selected == paths && multiSelect == multiSelectMode) return
        // 必须存副本：调用方传入的是同一个可变集合，共享引用会导致后续比较恒等而不再刷新
        selected = HashSet(paths)
        multiSelect = multiSelectMode
        notifyDataSetChanged()
    }

    fun itemCount(): Int = items.size

    fun itemAt(position: Int): FileEntry? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val name: TextView = itemView.findViewById(R.id.tv_name)
        private val meta: TextView = itemView.findViewById(R.id.tv_meta)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_select)
        private val more: ImageButton = itemView.findViewById(R.id.btn_item_more)

        fun bind(entry: FileEntry) {
            icon.setImageResource(FileTypes.iconOf(entry.kind))
            name.text = entry.name
            meta.text = buildMeta(entry)

            val isSelected = selected.contains(entry.path)
            itemView.isActivated = isSelected
            checkBox.visibility = if (multiSelect) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            more.visibility = if (multiSelect) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onItemClick(entry) }
            itemView.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }
            more.setOnClickListener { view -> onMoreClick(entry, view) }
        }

        private fun buildMeta(entry: FileEntry): String {
            val parts = ArrayList<String>(3)
            if (showPath) {
                val parent = entry.path.substringBeforeLast('/', "/")
                parts.add(if (parent.isEmpty()) "/" else parent)
            }
            parts.add(if (entry.isDir) "文件夹" else FormatUtils.formatSize(entry.size))
            parts.add(FormatUtils.formatTime(entry.modified))
            return parts.joinToString(" · ")
        }
    }
}