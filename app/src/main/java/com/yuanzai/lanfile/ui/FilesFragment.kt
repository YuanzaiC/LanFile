package com.yuanzai.lanfile.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yuanzai.lanfile.R
import com.yuanzai.lanfile.core.BatchResult
import com.yuanzai.lanfile.core.FileImporter
import com.yuanzai.lanfile.core.FileOpener
import com.yuanzai.lanfile.core.FileRepository
import com.yuanzai.lanfile.core.FileTypes
import com.yuanzai.lanfile.core.FormatUtils
import com.yuanzai.lanfile.core.OperationCancelledException
import com.yuanzai.lanfile.core.OpException
import com.yuanzai.lanfile.core.SettingsStore
import com.yuanzai.lanfile.core.StorageManager
import com.yuanzai.lanfile.model.FileEntry
import com.yuanzai.lanfile.model.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机端文件管理器：浏览、搜索、多选、新建/重命名/删除/复制/移动/导入。
 * 与网页端操作同一个真实目录（[StorageManager.root]），任何一端改动立刻可见。
 */
class FilesFragment : Fragment(R.layout.fragment_files) {

    private lateinit var settings: SettingsStore
    private lateinit var adapter: FileListAdapter

    private val selection = LinkedHashSet<String>()
    private var currentPath: String = "/"
    private var searchActive = false
    private var searchQuery = ""
    private var currentList: List<FileEntry> = emptyList()
    private var loading = false
    private var pendingReload = false
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { if (searchActive) reload() }

    /** 正在显示的传输进度弹窗（视图销毁时必须主动关闭，避免遗留窗口）。 */
    private var activeProgress: TransferProgressDialog? = null

    private var pathText: TextView? = null
    private var recycler: RecyclerView? = null
    private var emptyLayout: LinearLayout? = null
    private var emptyText: TextView? = null
    private var progress: LinearProgressIndicator? = null
    private var selectionBar: LinearLayout? = null
    private var selectionText: TextView? = null
    private var searchCard: View? = null
    private var searchInput: EditText? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) importFiles(uris)
        }

    private data class LoadResult(
        val entries: List<FileEntry>,
        val truncated: Boolean,
        val error: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pathText = view.findViewById(R.id.tv_path)
        recycler = view.findViewById(R.id.recycler_files)
        emptyLayout = view.findViewById(R.id.layout_empty)
        emptyText = view.findViewById(R.id.tv_empty)
        progress = view.findViewById(R.id.progress_files)
        selectionBar = view.findViewById(R.id.layout_selection)
        selectionText = view.findViewById(R.id.tv_selection)
        searchCard = view.findViewById(R.id.card_search)
        searchInput = view.findViewById(R.id.et_search)
        UiUtils.addPressFeedbackRecursive(view)

        adapter = FileListAdapter(
            onItemClick = { entry -> onItemClick(entry) },
            onItemLongClick = { entry -> toggleSelection(entry) },
            onMoreClick = { entry, anchor -> showItemMenu(entry, anchor) }
        )
        recycler?.layoutManager = LinearLayoutManager(requireContext())
        recycler?.adapter = adapter

        view.findViewById<ImageButton>(R.id.btn_up).setOnClickListener { navigateUp() }
        view.findViewById<ImageButton>(R.id.btn_more).setOnClickListener { anchor -> showToolbarMenu(anchor) }
        view.findViewById<ImageButton>(R.id.btn_search).setOnClickListener { toggleSearch() }
        view.findViewById<ImageButton>(R.id.btn_search_close).setOnClickListener { closeSearch() }
        view.findViewById<ExtendedFloatingActionButton>(R.id.fab_add).setOnClickListener { pickFiles() }

        selectionBar?.findViewById<ImageButton>(R.id.btn_sel_copy)?.setOnClickListener {
            val paths = selection.toList()
            if (paths.isNotEmpty()) {
                DirPicker.show(requireContext(), "复制到", currentPath) { dest ->
                    runTransfer(paths, dest, move = false)
                }
            }
        }
        selectionBar?.findViewById<ImageButton>(R.id.btn_sel_move)?.setOnClickListener {
            val paths = selection.toList()
            if (paths.isNotEmpty()) {
                DirPicker.show(requireContext(), "移动到", currentPath) { dest ->
                    runTransfer(paths, dest, move = true)
                }
            }
        }
        selectionBar?.findViewById<ImageButton>(R.id.btn_sel_share)?.setOnClickListener { shareSelection() }
        selectionBar?.findViewById<ImageButton>(R.id.btn_sel_delete)?.setOnClickListener { confirmDeleteSelection() }
        selectionBar?.findViewById<ImageButton>(R.id.btn_sel_close)?.setOnClickListener { clearSelection() }

        searchInput?.doAfterTextChanged { text ->
            searchQuery = text?.toString().orEmpty()
            if (searchActive) {
                // 输入防抖：停止输入 300ms 后再搜索
                searchHandler.removeCallbacks(searchRunnable)
                searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
            }
        }
        searchInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchInput?.let { UiUtils.hideKeyboard(requireContext(), it) }
                reload()
                true
            } else {
                false
            }
        }

        currentPath = validatePath(settings.lastPath)
        updateSelectionUi()
        reload()
    }

    override fun onDestroyView() {
        searchHandler.removeCallbacks(searchRunnable)
        // 旋转屏幕 / 切换深色模式时视图会被销毁：复制仍在后台继续，但进度弹窗必须关掉，
        // 否则会留下一个无法关闭的窗口（结果由重新创建的列表自动刷新体现）。
        activeProgress?.dismissSafely()
        activeProgress = null
        pathText = null
        recycler = null
        emptyLayout = null
        emptyText = null
        progress = null
        selectionBar = null
        selectionText = null
        searchCard = null
        searchInput = null
        super.onDestroyView()
    }

    /** 返回键处理：先退出多选 / 搜索，再回到上一级目录。 */
    fun handleBackPressed(): Boolean {
        if (selection.isNotEmpty()) {
            clearSelection()
            return true
        }
        if (searchActive) {
            closeSearch()
            return true
        }
        if (currentPath != "/") {
            openDirectory(StorageManager.parentOf(currentPath) ?: "/")
            return true
        }
        return false
    }

    // ------------------------------------------------------------- 加载

    private fun validatePath(path: String): String {
        val resolved = StorageManager.resolve(path)
        return if (resolved != null && resolved.isDirectory) StorageManager.normalize(path) else "/"
    }

    private fun reload() {
        if (loading) {
            // 正在加载时排一次队，保证最后一次输入总能生效
            pendingReload = true
            return
        }
        val path = currentPath
        val query = searchQuery.trim()
        val searching = searchActive && query.isNotEmpty()
        loading = true
        progress?.visibility = View.VISIBLE
        val context = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (searching) {
                        val found = FileRepository.search(query, path, SEARCH_LIMIT)
                        LoadResult(found.entries, found.truncated, null)
                    } else {
                        LoadResult(FileRepository.list(path), false, null)
                    }
                }.getOrElse { error ->
                    LoadResult(emptyList(), false, describeError(error))
                }
            }
            if (!isAdded) return@launch
            loading = false
            progress?.visibility = View.INVISIBLE

            result.error?.let { UiUtils.toast(context, it, long = true) }
            currentList = FileRepository.sort(result.entries, settings.sortMode, settings.sortAscending)
            adapter.showPath = searching
            adapter.submit(currentList)
            // 清理已经不存在的选中项
            selection.retainAll(currentList.map { it.path }.toSet())
            updateSelectionUi()
            updateHeader(result)

            if (currentList.isEmpty()) {
                emptyLayout?.visibility = View.VISIBLE
                emptyText?.text = when {
                    searching -> "没有找到匹配「$query」的文件"
                    else -> "此文件夹为空"
                }
            } else {
                emptyLayout?.visibility = View.GONE
            }

            if (pendingReload) {
                pendingReload = false
                reload()
            }
        }
    }

    private fun updateHeader(result: LoadResult) {
        val label = if (searchActive && searchQuery.isNotBlank()) {
            "搜索：${searchQuery}"
        } else {
            currentPath
        }
        val count = if (currentList.isEmpty()) "" else " (${currentList.size})"
        pathText?.text = label + count
        pathText?.contentDescription = result.error
    }

    private fun describeError(error: Throwable): String = when (error) {
        is OpException -> error.message ?: "操作失败"
        is SecurityException -> "没有权限访问该目录"
        else -> error.message ?: "读取目录失败"
    }

    private fun openDirectory(path: String) {
        currentPath = StorageManager.normalize(path)
        searchActive = false
        searchQuery = ""
        searchInput?.setText("")
        searchCard?.visibility = View.GONE
        selection.clear()
        adapter.showPath = false
        settings.lastPath = currentPath
        updateSelectionUi()
        reload()
    }

    private fun navigateUp() {
        if (currentPath == "/") {
            UiUtils.toast(requireContext(), "已位于根目录")
            return
        }
        openDirectory(StorageManager.parentOf(currentPath) ?: "/")
    }

    private fun toggleSearch() {
        if (searchActive) {
            closeSearch()
            return
        }
        searchActive = true
        searchCard?.visibility = View.VISIBLE
        searchInput?.requestFocus()
        UiUtils.toast(requireContext(), "输入关键字可搜索当前目录及其子目录")
    }

    private fun closeSearch() {
        searchActive = false
        searchQuery = ""
        searchHandler.removeCallbacks(searchRunnable)
        searchInput?.setText("")
        searchCard?.visibility = View.GONE
        adapter.showPath = false
        UiUtils.hideKeyboard(requireContext(), searchInput ?: requireView())
        reload()
    }

    // ------------------------------------------------------------- 交互

    private fun onItemClick(entry: FileEntry) {
        if (selection.isNotEmpty()) {
            toggleSelection(entry)
            return
        }
        if (entry.isDir) {
            openDirectory(entry.path)
        } else {
            showDetails(entry)
        }
    }

    private fun toggleSelection(entry: FileEntry) {
        if (!selection.remove(entry.path)) {
            selection.add(entry.path)
        }
        updateSelectionUi()
    }

    private fun clearSelection() {
        selection.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        adapter.setSelection(selection, selection.isNotEmpty())
        selectionBar?.visibility = if (selection.isNotEmpty()) View.VISIBLE else View.GONE
        selectionText?.text = "已选择 ${selection.size} 项"
    }

    private fun selectedEntries(): List<FileEntry> =
        currentList.filter { selection.contains(it.path) }

    // ------------------------------------------------------------- 工具栏菜单

    private fun showToolbarMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_NEW_FOLDER, 0, "新建文件夹")
        popup.menu.add(0, MENU_IMPORT, 1, "添加文件…")
        val allSelected = currentList.isNotEmpty() && selection.size == currentList.size
        popup.menu.add(0, MENU_SELECT_ALL, 2, if (allSelected) "取消全选" else "全选")
        popup.menu.add(0, MENU_REFRESH, 3, "刷新")

        val sortMenu = popup.menu.addSubMenu(0, MENU_SORT_GROUP, 4, "排序方式")
        sortMenu.add(0, MENU_SORT_NAME, 0, sortLabel("名称", SettingsStore.SORT_NAME))
        sortMenu.add(0, MENU_SORT_SIZE, 1, sortLabel("大小", SettingsStore.SORT_SIZE))
        sortMenu.add(0, MENU_SORT_TIME, 2, sortLabel("修改时间", SettingsStore.SORT_TIME))
        sortMenu.add(0, MENU_SORT_TYPE, 3, sortLabel("类型", SettingsStore.SORT_TYPE))
        sortMenu.add(0, MENU_SORT_ORDER, 4, if (settings.sortAscending) "改为降序" else "改为升序")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NEW_FOLDER -> createFolder()
                MENU_IMPORT -> pickFiles()
                MENU_SELECT_ALL -> {
                    if (allSelected) {
                        clearSelection()
                    } else {
                        currentList.forEach { selection.add(it.path) }
                        updateSelectionUi()
                    }
                }
                MENU_REFRESH -> reload()
                MENU_SORT_NAME -> applySort(SettingsStore.SORT_NAME)
                MENU_SORT_SIZE -> applySort(SettingsStore.SORT_SIZE)
                MENU_SORT_TIME -> applySort(SettingsStore.SORT_TIME)
                MENU_SORT_TYPE -> applySort(SettingsStore.SORT_TYPE)
                MENU_SORT_ORDER -> {
                    settings.sortAscending = !settings.sortAscending
                    reload()
                }
            }
            true
        }
        popup.show()
    }

    private fun sortLabel(name: String, mode: String): String =
        if (settings.sortMode == mode) "✓ $name" else name

    private fun applySort(mode: String) {
        settings.sortMode = mode
        reload()
    }

    // ------------------------------------------------------------- 单项菜单

    private fun showItemMenu(entry: FileEntry, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        var id = 0
        val openId = id++
        val previewId = id++
        val shareId = id++
        val renameId = id++
        val moveId = id++
        val copyId = id++
        val detailId = id++
        val deleteId = id++

        if (entry.isDir) {
            popup.menu.add(0, openId, 0, "打开")
        } else {
            popup.menu.add(0, openId, 0, "用其他应用打开")
            if (isPreviewable(entry)) popup.menu.add(0, previewId, 1, "预览")
            popup.menu.add(0, shareId, 2, "分享")
        }
        popup.menu.add(0, renameId, 3, "重命名")
        popup.menu.add(0, moveId, 4, "移动")
        popup.menu.add(0, copyId, 5, "复制")
        popup.menu.add(0, detailId, 6, "详情")
        popup.menu.add(0, deleteId, 7, "删除")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                openId -> if (entry.isDir) openDirectory(entry.path) else openWithSystem(entry)
                previewId -> openPreview(entry)
                shareId -> shareEntries(listOf(entry))
                renameId -> renameEntry(entry)
                moveId -> DirPicker.show(requireContext(), "移动「${entry.name}」到", currentPath) { dest ->
                    runTransfer(listOf(entry.path), dest, move = true)
                }
                copyId -> DirPicker.show(requireContext(), "复制「${entry.name}」到", currentPath) { dest ->
                    runTransfer(listOf(entry.path), dest, move = false)
                }
                detailId -> showDetails(entry)
                deleteId -> confirmDelete(listOf(entry))
            }
            true
        }
        popup.show()
    }

    private fun isPreviewable(entry: FileEntry): Boolean =
        entry.kind == FileKind.IMAGE || entry.kind == FileKind.TEXT ||
            (entry.kind == FileKind.OTHER && FileTypes.isTextExtension(entry.ext))

    // ------------------------------------------------------------- 操作

    private fun createFolder() {
        UiUtils.inputDialog(requireContext(), "新建文件夹", "文件夹名称") { name ->
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching { FileRepository.mkdir(currentPath, name) }
                        .exceptionOrNull()?.let { describeError(it) }
                }
                if (!isAdded) return@launch
                if (error == null) {
                    UiUtils.toast(context, "已创建：$name")
                    reload()
                } else {
                    UiUtils.toast(context, error, long = true)
                }
            }
        }
    }

    private fun renameEntry(entry: FileEntry) {
        UiUtils.inputDialog(requireContext(), "重命名", "新名称", entry.name) { name ->
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val error = withContext(Dispatchers.IO) {
                    runCatching { FileRepository.rename(entry.path, name) }
                        .exceptionOrNull()?.let { describeError(it) }
                }
                if (!isAdded) return@launch
                if (error == null) {
                    UiUtils.toast(context, "已重命名为：$name")
                    reload()
                } else {
                    UiUtils.toast(context, error, long = true)
                }
            }
        }
    }

    private fun confirmDeleteSelection() {
        confirmDelete(selectedEntries())
    }

    private fun confirmDelete(entries: List<FileEntry>) {
        if (entries.isEmpty()) return
        val names = entries.take(5).joinToString("、") { it.name }
        val more = if (entries.size > 5) " 等 ${entries.size} 项" else ""
        val hasDir = entries.any { it.isDir }
        val message = buildString {
            append("确定要删除「$names」$more 吗？")
            if (hasDir) append("\n\n文件夹内的所有内容都会被一起删除。")
            append("\n\n此操作不可恢复。")
        }
        UiUtils.confirmDialog(requireContext(), "删除确认", message) {
            val paths = entries.map { it.path }
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { FileRepository.delete(paths) }
                if (!isAdded) return@launch
                clearSelection()
                val summary = "已删除 ${result.succeeded} 项" +
                    if (result.failed > 0) "，${result.failed} 项失败：${result.errors.first()}" else ""
                UiUtils.toast(context, summary, long = result.failed > 0)
                reload()
            }
        }
    }

    private fun shareSelection() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        shareEntries(entries)
    }

    private fun shareEntries(entries: List<FileEntry>) {
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) {
            UiUtils.toast(requireContext(), "文件夹无法直接分享，请选择文件")
            return
        }
        if (files.size < entries.size) {
            UiUtils.toast(requireContext(), "已跳过 ${entries.size - files.size} 个文件夹（暂不支持分享文件夹）")
        }
        val resolved = files.mapNotNull { StorageManager.resolve(it.path) }
        val mime = if (files.size == 1) files.first().mime else "*/*"
        FileOpener.shareFiles(requireContext(), resolved, mime)
    }

    private fun openWithSystem(entry: FileEntry) {
        val file = StorageManager.resolve(entry.path)
        if (file == null || !file.exists()) {
            UiUtils.toast(requireContext(), "文件不存在或已被移动")
            reload()
            return
        }
        FileOpener.openFile(requireContext(), file, entry.mime)
    }

    private fun openPreview(entry: FileEntry) {
        startActivity(PreviewActivity.intent(requireContext(), entry.path))
    }

    private fun pickFiles() {
        try {
            importLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            UiUtils.toast(requireContext(), "无法打开文件选择器：${e.message ?: "未知错误"}")
        }
    }

    /** 从系统文件选择器导入文件到当前目录。 */
    private fun importFiles(uris: List<Uri>) {
        val destDir = StorageManager.resolve(currentPath)
        if (destDir == null || !destDir.isDirectory) {
            UiUtils.toast(requireContext(), "当前目录不可用，无法导入文件")
            return
        }
        val context = requireContext().applicationContext
        val dialog = TransferProgressDialog(requireContext(), "正在复制文件")
        activeProgress = dialog
        dialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileImporter.importUris(context, uris, destDir, dialog) }
            }
            activeProgress = null
            dialog.dismissSafely()
            if (!isAdded) return@launch
            result.onSuccess { batch ->
                val summary = "导入完成：成功 ${batch.succeeded} 个" +
                    if (batch.failed > 0) "，失败 ${batch.failed} 个（${batch.errors.first()}）" else ""
                UiUtils.toast(context, summary, long = batch.failed > 0)
                reload()
            }.onFailure { error ->
                if (error is OperationCancelledException) {
                    UiUtils.toast(context, "已取消导入操作")
                } else {
                    UiUtils.toast(context, "导入失败：${describeError(error)}", long = true)
                }
                reload()
            }
        }
    }

    /** 复制 / 移动（带进度弹窗）。 */
    private fun runTransfer(paths: List<String>, dest: String, move: Boolean) {
        val context = requireContext().applicationContext
        val dialog = TransferProgressDialog(requireContext(), if (move) "正在移动文件" else "正在复制文件")
        activeProgress = dialog
        dialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { FileRepository.transfer(paths, dest, move, dialog) }
            }
            activeProgress = null
            dialog.dismissSafely()
            if (!isAdded) return@launch
            result.onSuccess { batch: BatchResult ->
                afterTransfer(context, batch, move, dest)
            }.onFailure { error ->
                if (error is OperationCancelledException) {
                    UiUtils.toast(context, "已取消操作")
                } else {
                    UiUtils.toast(context, "操作失败：${describeError(error)}", long = true)
                }
                reload()
            }
        }
    }

    private fun afterTransfer(
        context: android.content.Context,
        batch: BatchResult,
        move: Boolean,
        dest: String
    ) {
        val verb = if (move) "移动" else "复制"
        val summary = buildString {
            append("$verb 完成：成功 ${batch.succeeded} 项")
            append(" → ${StorageManager.normalize(dest)}")
            if (batch.failed > 0) append("，失败 ${batch.failed} 项（${batch.errors.first()}）")
        }
        UiUtils.toast(context, summary, long = batch.failed > 0)
        clearSelection()
        reload()
    }

    // ------------------------------------------------------------- 文件详情

    private fun showDetails(entry: FileEntry) {
        val content = layoutInflater.inflate(R.layout.dialog_details, null)
        content.findViewById<TextView>(R.id.tv_detail_name).text = entry.name
        content.findViewById<TextView>(R.id.tv_detail_size).text =
            if (entry.isDir) "—" else FormatUtils.formatSize(entry.size)
        content.findViewById<TextView>(R.id.tv_detail_type).text =
            FileTypes.detailTypeOf(entry.kind, entry.ext)
        content.findViewById<TextView>(R.id.tv_detail_location).text =
            entry.path.substringBeforeLast('/', "/") + "/"
        content.findViewById<TextView>(R.id.tv_detail_time).text =
            FormatUtils.formatTime(entry.modified)

        val previewButton = content.findViewById<MaterialButton>(R.id.btn_detail_preview)
        val openButton = content.findViewById<MaterialButton>(R.id.btn_detail_open)
        val shareButton = content.findViewById<MaterialButton>(R.id.btn_detail_share)
        val renameButton = content.findViewById<MaterialButton>(R.id.btn_detail_rename)
        val moveButton = content.findViewById<MaterialButton>(R.id.btn_detail_move)
        val copyButton = content.findViewById<MaterialButton>(R.id.btn_detail_copy)
        val deleteButton = content.findViewById<MaterialButton>(R.id.btn_detail_delete)

        previewButton.visibility = if (isPreviewable(entry)) View.VISIBLE else View.GONE

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("文件详情")
            .setView(content)
            .setNegativeButton("关闭", null)
            .create()

        previewButton.setOnClickListener {
            dialog.dismiss()
            openPreview(entry)
        }
        openButton.setOnClickListener {
            dialog.dismiss()
            if (entry.isDir) openDirectory(entry.path) else openWithSystem(entry)
        }
        shareButton.setOnClickListener {
            dialog.dismiss()
            shareEntries(listOf(entry))
        }
        renameButton.setOnClickListener {
            dialog.dismiss()
            renameEntry(entry)
        }
        moveButton.setOnClickListener {
            dialog.dismiss()
            DirPicker.show(requireContext(), "移动「${entry.name}」到", currentPath) { dest ->
                runTransfer(listOf(entry.path), dest, move = true)
            }
        }
        copyButton.setOnClickListener {
            dialog.dismiss()
            DirPicker.show(requireContext(), "复制「${entry.name}」到", currentPath) { dest ->
                runTransfer(listOf(entry.path), dest, move = false)
            }
        }
        deleteButton.setOnClickListener {
            dialog.dismiss()
            confirmDelete(listOf(entry))
        }
        dialog.show()
    }

    companion object {
        private const val SEARCH_LIMIT = 500
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MENU_NEW_FOLDER = 1001
        private const val MENU_IMPORT = 1002
        private const val MENU_SELECT_ALL = 1003
        private const val MENU_REFRESH = 1004
        private const val MENU_SORT_GROUP = 1005
        private const val MENU_SORT_NAME = 1006
        private const val MENU_SORT_SIZE = 1007
        private const val MENU_SORT_TIME = 1008
        private const val MENU_SORT_TYPE = 1009
        private const val MENU_SORT_ORDER = 1010
    }
}
