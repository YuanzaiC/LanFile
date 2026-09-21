package com.yuanzai.lanfile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.model.FileEntry

/** 目录选择器（复制 / 移动的目标目录）。 */
class DirPickerAdapter(
    private val onClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<DirPickerAdapter.DirViewHolder>() {

    private val items = ArrayList<FileEntry>()

    fun submit(dirs: List<FileEntry>) {
        items.clear()
        items.addAll(dirs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DirViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dir, parent, false)
        return DirViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DirViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class DirViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val name: TextView = itemView.findViewById(R.id.tv_dir_name)

        fun bind(entry: FileEntry) {
            name.text = entry.name
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}