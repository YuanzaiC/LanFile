package com.yuanzai.lanfile.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.FileRepository
import com.yuanzai.lanfile.core.StorageManager

/**
 * 目录选择弹窗：用于「复制到 / 移动到」。
 * 只能在「局域网文件」目录树内选择，根目录为起点。
 */
object DirPicker {

    fun show(
        context: Context,
        title: String,
        startPath: String,
        confirmText: String = "选择此目录",
        onPick: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_dir_picker, null)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_picker)
        val pathView = view.findViewById<TextView>(R.id.tv_picker_path)
        val upButton = view.findViewById<ImageButton>(R.id.btn_picker_up)
        val emptyView = view.findViewById<TextView>(R.id.tv_picker_empty)

        var currentPath = if (StorageManager.resolve(startPath)?.isDirectory == true) {
            StorageManager.normalize(startPath)
        } else {
            "/"
        }

        var adapter: DirPickerAdapter? = null

        fun load() {
            pathView.text = currentPath
            upButton.isEnabled = currentPath != "/"
            val dirs = runCatching {
                FileRepository.list(currentPath).filter { it.isDir }
            }.getOrDefault(emptyList())
            adapter?.submit(dirs)
            emptyView.visibility = if (dirs.isEmpty()) View.VISIBLE else View.GONE
        }

        adapter = DirPickerAdapter { entry ->
            currentPath = entry.path
            load()
        }
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter

        upButton.setOnClickListener {
            currentPath = StorageManager.parentOf(currentPath) ?: "/"
            load()
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ -> onPick(currentPath) }
            .create()
        load()
        dialog.show()
    }
}