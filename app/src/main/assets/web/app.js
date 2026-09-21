/* 局域网文件 · 网页端脚本（原生 JS，无第三方依赖） */
'use strict';

(function () {
  // ------------------------------------------------------------ 基础工具

  const $ = function (id) { return document.getElementById(id); };

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      switch (ch) {
        case '&': return '&amp;';
        case '<': return '&lt;';
        case '>': return '&gt;';
        case '"': return '&quot;';
        default: return '&#39;';
      }
    });
  }

  function formatSize(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return n + ' B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = n / 1024;
    let index = 0;
    while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
    return value.toFixed(value >= 100 ? 0 : 1) + ' ' + units[index];
  }

  function formatSpeed(bytesPerSecond) {
    if (!bytesPerSecond || bytesPerSecond <= 0) return '—';
    return formatSize(bytesPerSecond) + '/s';
  }

  function formatEta(seconds) {
    if (!isFinite(seconds) || seconds <= 0) return '—';
    if (seconds < 60) return Math.ceil(seconds) + ' 秒';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return minutes + ' 分 ' + Math.round(seconds % 60) + ' 秒';
    return Math.floor(minutes / 60) + ' 小时 ' + (minutes % 60) + ' 分';
  }

  function pad(value) { return value < 10 ? '0' + value : '' + value; }

  function formatTime(ms) {
    const d = new Date(Number(ms) || Date.now());
    if (isNaN(d.getTime())) return '—';
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
      ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function parentOf(path) {
    const clean = String(path || '/');
    if (clean === '/' || clean === '') return null;
    const index = clean.lastIndexOf('/');
    return index <= 0 ? '/' : clean.substring(0, index);
  }

  function typeIcon(type) {
    switch (type) {
      case 'dir': return '📁';
      case 'image': return '🖼️';
      case 'video': return '🎬';
      case 'audio': return '🎵';
      case 'document': return '📄';
      case 'archive': return '🗜️';
      case 'apk': return '📦';
      case 'text': return '📝';
      default: return '📎';
    }
  }

  let toastTimer = null;
  function toast(message, kind) {
    const box = $('toast');
    box.textContent = message;
    box.className = 'toast' + (kind ? ' ' + kind : '');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { box.className = 'toast hidden'; }, kind === 'error' ? 5000 : 2600);
  }

  async function readJson(response) {
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    if (!response.ok) {
      const message = (data && data.error) || ('请求失败（HTTP ' + response.status + '）');
      if (response.status === 403 && message.indexOf('禁止访问') >= 0) showBlocked(message);
      throw new Error(message);
    }
    if (data && data.ok === false) {
      throw new Error(data.error || '操作失败');
    }
    return data || {};
  }

  /* 被手机端封禁时，页面上盖一层正式的提示，而不是只弹一条 toast */
  function showBlocked(message) {
    if (document.getElementById('blockedOverlay')) return;
    state.autoRefresh = false;
    const overlay = document.createElement('div');
    overlay.id = 'blockedOverlay';
    overlay.className = 'blockedOverlay';
    overlay.innerHTML =
      '<div class="blockedBox">' +
      '<span class="blockedBadge">403 · 禁止访问</span>' +
      '<h2>此设备已被禁止访问</h2>' +
      '<p>' + esc(message || '管理员已在手机端停用此设备的访问权限。') + '</p>' +
      '<p class="meta">如需恢复访问，请在手机端「设备」页面解除封禁，然后刷新本页面。</p>' +
      '</div>';
    document.body.appendChild(overlay);
  }

  async function apiGet(url) {
    const response = await fetch(url, { cache: 'no-store' });
    return readJson(response);
  }

  async function apiPost(url, payload) {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {})
    });
    return readJson(response);
  }

  // ------------------------------------------------------------ 状态

  const state = {
    path: '/',
    parent: null,
    entries: [],
    selection: new Set(),
    searching: false,
    query: '',
    messages: [],
    uploads: [],
    uploadQueue: [],
    uploading: false,
    autoRefresh: true,
    lastReloadAt: 0,
    lastInfoAt: 0,
    // 手机端「设置 → 网页端」里的开关，/api/info 返回后同步
    flags: {
      title: '局域网文件',
      allowUpload: true,
      allowDelete: true,
      allowModify: true,
      allowText: true,
      uploadLimitMb: 0
    }
  };

  // ------------------------------------------------------------ 主题

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    try { localStorage.setItem('lanfile.theme', theme); } catch (e) { /* 忽略 */ }
  }

  function initTheme() {
    let saved = null;
    try { saved = localStorage.getItem('lanfile.theme'); } catch (e) { saved = null; }
    if (saved === 'light' || saved === 'dark') { applyTheme(saved); return; }
    const dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    applyTheme(dark ? 'dark' : 'light');
  }

  // ------------------------------------------------------------ 列表加载

  function setLoading(show) {
    $('loading').classList.toggle('hidden', !show);
  }

  async function loadList(path, keepSelection) {
    setLoading(true);
    try {
      const data = await apiGet('/api/list?path=' + encodeURIComponent(path || '/'));
      state.searching = false;
      state.query = '';
      state.entries = data.entries || [];
      state.path = data.path || path || '/';
      state.parent = data.parent || null;
      state.lastReloadAt = Date.now();
      if (keepSelection) {
        // 刷新后清理已经不存在（被删除/移动）的选中项
        const existing = new Set(state.entries.map(function (entry) { return entry.path; }));
        state.selection.forEach(function (path) {
          if (!existing.has(path)) state.selection.delete(path);
        });
      } else {
        state.selection.clear();
      }
      try { localStorage.setItem('lanfile.path', state.path); } catch (e) { /* 忽略 */ }
      $('searchInput').value = '';
      renderBreadcrumb();
      renderEntries();
      $('listMeta').textContent = '共 ' + state.entries.length + ' 项（文件夹 ' +
        (data.dirCount || 0) + ' / 文件 ' + (data.fileCount || 0) + '）';
    } catch (error) {
      toast('读取目录失败：' + error.message, 'error');
      $('emptyState').classList.remove('hidden');
      $('emptyState').textContent = '读取失败：' + error.message;
      $('fileBody').innerHTML = '';
    } finally {
      setLoading(false);
      updateSelectionBar();
    }
  }

  async function runSearch() {
    const keyword = $('searchInput').value.trim();
    if (!keyword) { loadList(state.path); return; }
    setLoading(true);
    try {
      const data = await apiGet('/api/search?q=' + encodeURIComponent(keyword) +
        '&path=' + encodeURIComponent(state.path) + '&limit=500');
      state.searching = true;
      state.query = keyword;
      state.lastReloadAt = Date.now();
      state.entries = data.entries || [];
      state.selection.clear();
      renderEntries();
      $('listMeta').textContent = '搜索「' + keyword + '」：' + state.entries.length + ' 项' +
        (data.truncated ? '（结果过多，仅显示前 500 项）' : '');
    } catch (error) {
      toast('搜索失败：' + error.message, 'error');
    } finally {
      setLoading(false);
      updateSelectionBar();
    }
  }

  function clearSearch() {
    if (!state.searching) { $('searchInput').value = ''; return; }
    loadList(state.path);
  }

  function renderBreadcrumb() {
    const nav = $('breadcrumb');
    const parts = state.path.split('/').filter(function (item) { return item.length > 0; });
    let html = '<button data-nav="/">根目录</button>';
    let accumulated = '';
    parts.forEach(function (part, index) {
      accumulated += '/' + part;
      const isLast = index === parts.length - 1;
      html += '<span class="crumbSep">/</span>';
      html += isLast
        ? '<span class="current">' + esc(part) + '</span>'
        : '<button data-nav="' + esc(accumulated) + '">' + esc(part) + '</button>';
    });
    nav.innerHTML = html;
    nav.querySelectorAll('button[data-nav]').forEach(function (button) {
      button.onclick = function () { loadList(button.getAttribute('data-nav')); };
    });
    $('btnUp').disabled = state.path === '/';
  }

  function renderEntries() {
    const body = $('fileBody');
    if (!state.entries.length) {
      body.innerHTML = '';
      $('emptyState').classList.remove('hidden');
      $('emptyState').textContent = state.searching ? '没有找到匹配的文件' : '此文件夹为空';
      return;
    }
    $('emptyState').classList.add('hidden');
    body.innerHTML = state.entries.map(function (entry) {
      const checked = state.selection.has(entry.path) ? ' checked' : '';
      const location = state.searching
        ? '<span class="searchPath">' + esc(parentOf(entry.path) || '/') + '</span>'
        : '';
      const fileActions = entry.isDir ? '' :
        '<button class="btn tiny" data-act="download">下载</button>' +
        '<button class="btn tiny" data-act="preview">预览</button>';
      const editActions = (state.flags.allowModify
        ? '<button class="btn tiny" data-act="rename">重命名</button>' +
          '<button class="btn tiny" data-act="move">移动</button>' +
          '<button class="btn tiny" data-act="copy">复制</button>'
        : '') + (state.flags.allowDelete
        ? '<button class="btn tiny danger" data-act="delete">删除</button>'
        : '');
      return '<tr data-path="' + esc(entry.path) + '" data-type="' + esc(entry.type) +
        '" data-name="' + esc(entry.name) + '" class="' + (entry.isDir ? 'isDir' : 'file') + '">' +
        '<td class="cCheck"><input type="checkbox" class="rowCheck"' + checked + '></td>' +
        '<td class="cName"><span class="ficon">' + typeIcon(entry.type) + '</span>' +
        '<button class="link nameBtn">' + esc(entry.name) + '</button>' + location + '</td>' +
        '<td class="cSize">' + (entry.isDir ? '—' : esc(formatSize(entry.size))) + '</td>' +
        '<td class="cTime">' + esc(formatTime(entry.modified)) + '</td>' +
        '<td class="cAct">' + fileActions + editActions + '</td></tr>';
    }).join('');
  }

  function findEntry(path) {
    for (let i = 0; i < state.entries.length; i++) {
      if (state.entries[i].path === path) return state.entries[i];
    }
    return null;
  }

  // ------------------------------------------------------------ 选择

  function toggleSelection(path) {
    if (state.selection.has(path)) { state.selection.delete(path); } else { state.selection.add(path); }
    const row = document.querySelector('tr[data-path="' + cssEscape(path) + '"]');
    if (row) {
      const box = row.querySelector('.rowCheck');
      if (box) box.checked = state.selection.has(path);
    }
    updateSelectionBar();
  }

  function cssEscape(value) {
    return String(value).replace(/["\\]/g, '\\$&');
  }

  function selectedPaths() {
    const list = [];
    state.selection.forEach(function (path) { list.push(path); });
    return list;
  }

  function updateSelectionBar() {
    const count = state.selection.size;
    $('selectionBar').classList.toggle('hidden', count === 0);
    $('selectionInfo').textContent = '已选择 ' + count + ' 项';
  }

  // ------------------------------------------------------------ 表格事件

  function onTableClick(event) {
    const row = event.target.closest ? event.target.closest('tr') : null;
    if (!row || !row.getAttribute('data-path')) return;
    const path = row.getAttribute('data-path');

    if (event.target.classList.contains('rowCheck')) {
      const box = event.target;
      if (box.checked) { state.selection.add(path); } else { state.selection.delete(path); }
      updateSelectionBar();
      return;
    }

    if (event.target.classList.contains('nameBtn')) {
      const entry = findEntry(path);
      if (entry && entry.isDir) { loadList(entry.path); }
      else if (entry) { openPreview(entry); }
      return;
    }

    const action = event.target.getAttribute && event.target.getAttribute('data-act');
    if (!action) return;
    const entry = findEntry(path);
    if (!entry) return;
    if (action === 'download') { downloadFile(entry); }
    else if (action === 'preview') { openPreview(entry); }
    else if (action === 'rename') { promptRename(entry); }
    else if (action === 'move') { openPicker('move', [entry.path]); }
    else if (action === 'copy') { openPicker('copy', [entry.path]); }
    else if (action === 'delete') { confirmDelete([entry]); }
  }

  // ------------------------------------------------------------ 下载

  function downloadFile(entry) {
    const link = document.createElement('a');
    link.href = '/api/download?path=' + encodeURIComponent(entry.path);
    link.download = entry.name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  function downloadMany(paths) {
    const files = paths.map(findEntry).filter(function (entry) { return entry && !entry.isDir; });
    if (!files.length) { toast('没有可下载的文件（文件夹请分别进入后下载）', 'error'); return; }
    if (files.length > 1) { toast('已开始下载 ' + files.length + ' 个文件，浏览器可能提示允许多个下载'); }
    files.forEach(function (entry, index) {
      setTimeout(function () { downloadFile(entry); }, index * 400);
    });
  }

  // ------------------------------------------------------------ 弹窗

  let modalHandler = null;

  function showModal(title, bodyHtml, okText, onOk) {
    $('modalTitle').textContent = title;
    $('modalBody').innerHTML = bodyHtml;
    $('modalOk').textContent = okText || '确定';
    modalHandler = onOk || null;
    $('modal').classList.remove('hidden');
  }

  function closeModal() {
    $('modal').classList.add('hidden');
    modalHandler = null;
  }

  function promptMkdir() {
    showModal('新建文件夹', '<input id="modalInput" class="modalInput" placeholder="请输入文件夹名称" maxlength="200">', '创建', async function () {
      const input = $('modalInput');
      const name = input ? input.value.trim() : '';
      if (!name) { toast('名称不能为空', 'error'); return false; }
      try {
        await apiPost('/api/mkdir', { path: state.path, name: name });
        toast('已创建文件夹：' + name, 'success');
        loadList(state.path);
      } catch (error) { toast('新建失败：' + error.message, 'error'); }
    });
    const input = $('modalInput');
    if (input) { input.focus(); }
  }

  function promptRename(entry) {
    showModal('重命名', '<input id="modalInput" class="modalInput" maxlength="200" value="' + esc(entry.name) + '">', '保存', async function () {
      const input = $('modalInput');
      const name = input ? input.value.trim() : '';
      if (!name) { toast('名称不能为空', 'error'); return false; }
      if (name === entry.name) return;
      try {
        await apiPost('/api/rename', { path: entry.path, newName: name });
        toast('重命名已完成', 'success');
        loadList(state.path);
      } catch (error) { toast('重命名失败：' + error.message, 'error'); }
    });
    const input = $('modalInput');
    if (input) { input.focus(); input.select(); }
  }

  function confirmDelete(entries) {
    const names = entries.slice(0, 5).map(function (entry) { return entry.name; }).join('、');
    const more = entries.length > 5 ? ' 等 ' + entries.length + ' 项' : '';
    const hasDir = entries.some(function (entry) { return entry.isDir; });
    let message = '<p>确定要删除「<b>' + esc(names) + '</b>」' + more + ' 吗？</p>';
    if (hasDir) message += '<p class="meta">文件夹内的所有内容都会被一起删除。</p>';
    message += '<p class="meta">此操作不可恢复。</p>';
    showModal('删除确认', message, '删除', async function () {
      try {
        const data = await apiPost('/api/delete', {
          paths: entries.map(function (entry) { return entry.path; })
        });
        const errors = data.errors || [];
        if (errors.length) { toast('部分删除失败：' + errors[0], 'error'); }
        else { toast('已删除 ' + (data.deleted || entries.length) + ' 项', 'success'); }
        state.selection.clear();
        loadList(state.path);
      } catch (error) { toast('删除失败：' + error.message, 'error'); }
    });
  }

  // ------------------------------------------------------------ 移动 / 复制

  let pickerPaths = [];
  let pickerMode = 'copy';

  function openPicker(mode, paths) {
    pickerMode = mode;
    pickerPaths = paths.slice();
    showModal((mode === 'move' ? '移动到…' : '复制到…') + '（共 ' + pickerPaths.length + ' 项）',
      '<div id="pickerHolder">正在加载…</div>', mode === 'move' ? '移动到这里' : '复制到这里',
      async function () {
        try {
          const data = await apiPost('/api/' + (pickerMode === 'move' ? 'move' : 'copy'), {
            paths: pickerPaths,
            dest: pickerPath
          });
          const errors = data.errors || [];
          if (errors.length) { toast('部分失败：' + errors[0], 'error'); }
          else { toast((pickerMode === 'move' ? '移动' : '复制') + '完成', 'success'); }
          state.selection.clear();
          loadList(state.path);
        } catch (error) { toast('操作失败：' + error.message, 'error'); }
      });
    loadPicker(state.path);
  }

  let pickerPath = '/';

  async function loadPicker(path) {
    pickerPath = path;
    const holder = $('pickerHolder');
    if (!holder) return;
    holder.innerHTML = '<div class="pickerHead"><button id="pickerUp" class="btn tiny">↑ 上一级</button>' +
      '<code>' + esc(path) + '</code></div><div id="pickerList" class="pickerList">正在加载…</div>';
    const up = $('pickerUp');
    up.disabled = path === '/';
    up.onclick = function () { const parent = parentOf(pickerPath); if (parent) loadPicker(parent); };
    try {
      const data = await apiGet('/api/list?path=' + encodeURIComponent(path));
      const dirs = (data.entries || []).filter(function (entry) { return entry.isDir; });
      const list = $('pickerList');
      if (!list) return;
      if (!dirs.length) { list.innerHTML = '<div class="meta">此目录下没有子文件夹，可直接选择当前目录</div>'; return; }
      list.innerHTML = dirs.map(function (dir) {
        return '<button class="pickerItem" data-path="' + esc(dir.path) + '">📁 ' + esc(dir.name) + '</button>';
      }).join('');
      list.querySelectorAll('.pickerItem').forEach(function (button) {
        button.onclick = function () { loadPicker(button.getAttribute('data-path')); };
      });
    } catch (error) {
      const list = $('pickerList');
      if (list) list.textContent = '读取失败：' + error.message;
    }
  }

  // ------------------------------------------------------------ 预览

  async function openPreview(entry) {
    if (entry.isDir) { loadList(entry.path); return; }
    const url = '/api/preview?path=' + encodeURIComponent(entry.path);
    $('previewTitle').textContent = entry.name;
    $('previewDownload').setAttribute('href', '/api/download?path=' + encodeURIComponent(entry.path));
    $('previewDownload').setAttribute('download', entry.name);
    const body = $('previewBody');
    body.innerHTML = '<div class="meta">正在加载…</div>';
    $('preview').classList.remove('hidden');

    if (entry.type === 'image') {
      body.innerHTML = '';
      const image = document.createElement('img');
      image.alt = entry.name;
      image.onerror = function () { body.innerHTML = '<div class="meta">图片加载失败</div>'; };
      image.src = url;
      body.appendChild(image);
      return;
    }

    if (entry.type === 'video' || entry.type === 'audio') {
      body.innerHTML = '';
      const media = document.createElement(entry.type === 'video' ? 'video' : 'audio');
      media.controls = true;
      media.src = url;
      if (entry.type === 'video') {
        media.style.maxWidth = '100%';
        media.style.maxHeight = '60vh';
      } else {
        media.style.width = '100%';
      }
      body.appendChild(media);
      return;
    }

    if (entry.type === 'text') {
      try {
        const response = await fetch(url, { cache: 'no-store' });
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const text = await response.text();
        body.innerHTML = '';
        const pre = document.createElement('pre');
        pre.textContent = text.length > 200000 ? text.slice(0, 200000) + '\n\n……（内容过长，仅显示前 200000 个字符）' : text;
        body.appendChild(pre);
      } catch (error) {
        body.innerHTML = '<div class="meta">无法以文本方式预览：' + esc(error.message) + '，请下载后打开</div>';
      }
      return;
    }

    body.innerHTML = '<div class="meta">该类型暂不支持在线预览，请点击下方按钮下载后用本地应用打开。</div>';
  }

  // ------------------------------------------------------------ 上传

  function pickFiles() { $('fileInput').click(); }

  function startUpload(fileList) {
    if (!state.flags.allowUpload) {
      toast('手机端已停用网页端上传功能', 'error');
      return;
    }
    const files = Array.prototype.slice.call(fileList || []);
    if (!files.length) return;
    // 手机端设置的单文件上限，先在浏览器这边拦一次，省得白传
    const limitMb = state.flags.uploadLimitMb || 0;
    if (limitMb > 0) {
      const oversize = files.filter(function (file) { return (file.size || 0) > limitMb * 1024 * 1024; });
      if (oversize.length) {
        toast('超过 ' + limitMb + ' MB 上限：' + oversize.slice(0, 3).map(function (file) {
          return file.name;
        }).join('、'), 'error');
        return;
      }
    }
    const target = state.path;
    const items = files.map(function (file) {
      return {
        id: 'u' + Math.random().toString(36).slice(2),
        file: file,
        name: file.name,
        size: file.size || 0,
        loaded: 0,
        speed: 0,
        status: '排队中',
        lastTime: 0,
        lastLoaded: 0,
        done: false,
        xhr: null
      };
    });
    state.uploads = state.uploads.concat(items);
    items.forEach(function (item) { state.uploadQueue.push({ item: item, path: target }); });
    renderUploads();
    pumpUploads();
    toast('开始上传 ' + items.length + ' 个文件到 ' + target);
  }

  function pumpUploads() {
    if (state.uploading) return;
    const next = state.uploadQueue.shift();
    if (!next) { renderUploads(); return; }
    state.uploading = true;
    uploadOne(next.item, next.path, function () {
      state.uploading = false;
      setTimeout(pumpUploads, 120);
    });
  }

  function uploadOne(item, targetPath, done) {
    const form = new FormData();
    form.append('files', item.file, item.name);
    const xhr = new XMLHttpRequest();
    item.xhr = xhr;
    item.status = '上传中';
    item.startedAt = Date.now();
    item.lastTime = Date.now();
    item.lastLoaded = 0;
    renderUploads();

    xhr.upload.onprogress = function (event) {
      if (!event.lengthComputable) return;
      item.loaded = event.loaded;
      item.size = event.total || item.size;
      const now = Date.now();
      const seconds = (now - item.lastTime) / 1000;
      if (seconds >= 0.3) {
        const instant = (event.loaded - item.lastLoaded) / seconds;
        item.speed = item.speed > 0 ? item.speed * 0.6 + instant * 0.4 : instant;
        item.lastTime = now;
        item.lastLoaded = event.loaded;
      }
      renderUploads();
    };

    xhr.onload = function () {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
      if (xhr.status >= 200 && xhr.status < 300 && data && data.ok) {
        const errors = (data && data.errors) || [];
        if (errors.length) {
          item.status = '失败：' + errors[0];
          item.failed = true;
        } else {
          item.status = '完成';
          item.loaded = item.size;
        }
      } else {
        item.status = '失败：' + ((data && data.error) || ('HTTP ' + xhr.status));
        item.failed = true;
      }
      item.done = true;
      renderUploads();
      done();
      if (!item.failed) { scheduleReload(); }
    };

    xhr.onerror = function () {
      item.status = '失败：网络中断';
      item.failed = true;
      item.done = true;
      renderUploads();
      done();
    };

    xhr.onabort = function () {
      item.status = '已取消';
      item.done = true;
      renderUploads();
      done();
    };

    xhr.open('POST', '/api/upload?path=' + encodeURIComponent(targetPath), true);
    xhr.send(form);
  }

  let reloadTimer = null;
  function scheduleReload() {
    if (reloadTimer) clearTimeout(reloadTimer);
    reloadTimer = setTimeout(function () {
      if (!state.searching) loadList(state.path, true);
    }, 500);
  }

  function renderUploads() {
    const list = $('uploadList');
    if (!state.uploads.length) {
      list.innerHTML = '<div class="meta">当前没有上传任务。</div>';
      $('uploadSummary').textContent = '';
      return;
    }
    let done = 0;
    let failed = 0;
    let active = 0;
    state.uploads.forEach(function (item) {
      if (item.status === '完成') done++;
      else if (item.failed) failed++;
      else active++;
    });
    $('uploadSummary').textContent = '共 ' + state.uploads.length + ' 个 · 完成 ' + done +
      ' · 进行中 ' + active + (failed ? ' · 失败 ' + failed : '');

    list.innerHTML = state.uploads.map(function (item) {
      const percent = item.size > 0 ? Math.min(100, Math.round(item.loaded / item.size * 100)) : 0;
      const fillClass = item.failed ? 'barFill fail' : (item.status === '完成' ? 'barFill done' : 'barFill');
      const remaining = item.size - item.loaded;
      const eta = item.speed > 0 && remaining > 0 ? formatEta(remaining / item.speed) : '—';
      const stats = item.status === '完成'
        ? esc(formatSize(item.size)) + ' · 完成'
        : (item.failed
          ? esc(item.status)
          : (esc(formatSize(item.loaded)) + ' / ' + esc(formatSize(item.size)) + ' · ' +
            (item.size > 0 ? percent + '%' : '—') + ' · ' + esc(formatSpeed(item.speed)) +
            ' · 剩余 ' + esc(eta)));
      const cancel = (!item.done)
        ? '<button class="btn tiny danger" data-cancel="' + esc(item.id) + '">取消</button>'
        : '';
      return '<div class="uploadItem" data-id="' + esc(item.id) + '">' +
        '<div class="uploadHead"><span class="uploadName">' + esc(item.name) + '</span>' +
        '<span class="uploadStats">' + stats + '</span>' + cancel + '</div>' +
        '<div class="bar"><div class="' + fillClass + '" style="width:' + percent + '%"></div></div>' +
        '</div>';
    }).join('');

    list.querySelectorAll('button[data-cancel]').forEach(function (button) {
      button.onclick = function () {
        const id = button.getAttribute('data-cancel');
        state.uploads.forEach(function (item) {
          if (item.id === id && item.xhr && !item.done) { item.xhr.abort(); }
        });
      };
    });
  }

  // ------------------------------------------------------------ 消息

  async function loadMessages() {
    try {
      const data = await apiGet('/api/messages?limit=200');
      state.messages = data.messages || [];
      renderMessages();
    } catch (error) {
      /* 轮询失败时保持界面，不打扰用户 */
    }
  }

  function renderMessages() {
    const list = $('messageList');
    if (!state.messages.length) {
      list.innerHTML = '<div class="meta">当前没有消息。在手机端或此处发送文字，两端均可看到。</div>';
      $('messageMeta').textContent = '';
      return;
    }
    $('messageMeta').textContent = '共 ' + state.messages.length + ' 条';
    // 接口返回的是「新的在前」，对话流要反过来：旧的在上面、新的贴底部
    const ordered = state.messages.slice().reverse();
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
    list.innerHTML = ordered.map(function (message) {
      const outgoing = message.source !== 'android'; // 本机浏览器发的靠右，手机来的靠左
      // 来源标识：Windows · Edge / Android · Chrome / 本机 · 安卓
      const device = (message.device && message.device.length)
        ? message.device
        : (outgoing ? '电脑网页' : '本机 · 安卓');
      return '<div class="msgRow' + (outgoing ? ' out' : '') + '" data-id="' + esc(message.id) + '">' +
        '<div class="msgBubble">' +
        '<div class="msgText">' + esc(message.text) + '</div>' +
        '<div class="msgFoot"><span class="meta">' + esc(formatTime(message.time)) + ' · ' + esc(device) + '</span>' +
        '<button class="btn tiny" data-mact="copy">复制</button>' +
        '<button class="btn tiny danger" data-mact="delete">删除</button></div>' +
        '</div></div>';
    }).join('');
    if (nearBottom || !list.dataset.ready) {
      list.scrollTop = list.scrollHeight;
      list.dataset.ready = '1';
    }
  }

  async function sendMessage() {
    const input = $('messageInput');
    const text = input.value.trim();
    if (!text) { toast('请输入要发送的文字内容', 'error'); return; }
    $('btnSend').disabled = true;
    try {
      await apiPost('/api/text', { text: text, source: 'web' });
      input.value = '';
      toast('文字已发送', 'success');
      loadMessages();
    } catch (error) {
      toast('发送失败：' + error.message, 'error');
    } finally {
      $('btnSend').disabled = false;
    }
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text)
        .then(function () { toast('已复制到剪贴板', 'success'); })
        .catch(function () { fallbackCopy(text); });
      return;
    }
    fallbackCopy(text);
  }

  function fallbackCopy(text) {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(area);
    toast(ok ? '已复制到剪贴板' : '复制失败，请手动选择文字', ok ? 'success' : 'error');
  }

  function onMessageClick(event) {
    const item = event.target.closest ? event.target.closest('.msgRow') : null;
    if (!item) return;
    const action = event.target.getAttribute && event.target.getAttribute('data-mact');
    if (!action) return;
    const id = item.getAttribute('data-id');
    const message = state.messages.filter(function (item2) { return item2.id === id; })[0];
    if (!message) return;
    if (action === 'copy') { copyText(message.text); return; }
    if (action === 'delete') {
      apiPost('/api/messages/delete', { ids: [id] })
        .then(function () { toast('该消息已删除', 'success'); loadMessages(); })
        .catch(function (error) { toast('删除失败：' + error.message, 'error'); });
    }
  }

  function clearMessages() {
    showModal('清空消息记录', '<p>确定要清空全部消息吗？此操作不可恢复。</p>', '清空', async function () {
      try {
        const data = await apiPost('/api/messages/delete', { all: true });
        toast('已清空 ' + (data.deleted || 0) + ' 条消息', 'success');
        loadMessages();
      } catch (error) { toast('清空失败：' + error.message, 'error'); }
    });
  }

  // ------------------------------------------------------------ 设备信息

  async function loadInfo() {
    try {
      const data = await apiGet('/api/info');
      state.lastInfoAt = Date.now();
      const parts = [];
      parts.push('设备：' + (data.device || 'Android'));
      if (data.total > 0) {
        parts.push('可用空间 ' + formatSize(data.free) + ' / ' + formatSize(data.total));
      }
      parts.push('消息 ' + (data.messageCount || 0) + ' 条');
      if (!data.usingPublicDir) parts.push('（当前使用应用专属目录）');
      state.flags = {
        title: data.title || '局域网文件',
        allowUpload: data.allowUpload !== false,
        allowDelete: data.allowDelete !== false,
        allowModify: data.allowModify !== false,
        allowText: data.allowText !== false,
        uploadLimitMb: data.uploadLimitMb || 0
      };
      applyFlags();
      $('deviceInfo').textContent = parts.join(' · ');
    } catch (error) {
      $('deviceInfo').textContent = '无法获取设备信息：' + error.message;
    }
  }

  /**
   * 按手机端的「网页端」开关调整界面：
   * 关掉的能力连按钮一起隐藏，避免用户点了才被拒。
   */
  function applyFlags() {
    const flags = state.flags;
    const title = flags.title || '局域网文件';
    $('brandTitle').textContent = title;
    document.title = title;

    $('uploadCard').classList.toggle('hidden', !flags.allowUpload);
    $('messageCard').classList.toggle('hidden', !flags.allowText);
    $('btnMkdir').classList.toggle('hidden', !flags.allowModify);
    ['copy', 'move'].forEach(function (action) {
      document.querySelectorAll('[data-batch="' + action + '"]').forEach(function (node) {
        node.classList.toggle('hidden', !flags.allowModify);
      });
    });
    document.querySelectorAll('[data-batch="delete"]').forEach(function (node) {
      node.classList.toggle('hidden', !flags.allowDelete);
    });

    const hint = $('uploadHint');
    if (hint) {
      hint.innerHTML = flags.uploadLimitMb > 0
        ? '把文件直接拖到网页任意位置即可上传到<b>当前目录</b>；单个文件最大 <b>' +
          flags.uploadLimitMb + ' MB</b>，超出会被手机端拒绝。'
        : '把文件直接拖到网页任意位置即可上传到<b>当前目录</b>；上传的文件会立即出现在手机端。';
    }
    renderEntries();
  }

  // ------------------------------------------------------------ 批量操作

  function onBatchClick(event) {
    const action = event.target.getAttribute && event.target.getAttribute('data-batch');
    if (!action) return;
    const paths = selectedPaths();
    if (action === 'clear') { state.selection.clear(); renderEntries(); updateSelectionBar(); return; }
    if (!paths.length) { toast('请先选择文件', 'error'); return; }
    if (action === 'download') { downloadMany(paths); return; }
    if (action === 'delete') {
      confirmDelete(paths.map(findEntry).filter(Boolean));
      return;
    }
    if (action === 'move' || action === 'copy') { openPicker(action, paths); }
  }

  // ------------------------------------------------------------ 拖拽上传

  let dragDepth = 0;

  function initDragAndDrop() {
    const overlay = $('dropOverlay');
    document.addEventListener('dragenter', function (event) {
      event.preventDefault();
      if (!state.flags.allowUpload) return;
      dragDepth++;
      $('dropPath').textContent = state.path;
      overlay.classList.remove('hidden');
    });
    document.addEventListener('dragover', function (event) { event.preventDefault(); });
    document.addEventListener('dragleave', function (event) {
      event.preventDefault();
      dragDepth = Math.max(0, dragDepth - 1);
      if (dragDepth === 0) overlay.classList.add('hidden');
    });
    document.addEventListener('drop', function (event) {
      event.preventDefault();
      dragDepth = 0;
      overlay.classList.add('hidden');
      if (!state.flags.allowUpload) {
        toast('手机端已停用网页端上传功能', 'error');
        return;
      }
      if (event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files.length) {
        startUpload(event.dataTransfer.files);
      }
    });
  }

  // ------------------------------------------------------------ 自动刷新

  function autoRefreshTick() {
    loadMessages();
    if (Date.now() - state.lastInfoAt > 15000) loadInfo();
    if (!state.autoRefresh) return;
    // 正在看搜索结果时不要自动刷新，否则会清掉搜索结果与搜索框
    if (state.searching) return;
    if (state.selection.size > 0) return;
    if (state.uploading || state.uploadQueue.length > 0) return;
    if (!$('modal').classList.contains('hidden')) return;
    if (!$('preview').classList.contains('hidden')) return;
    if (Date.now() - state.lastReloadAt < 6000) return;
    loadList(state.path, true);
  }

  // ------------------------------------------------------------ 初始化

  function init() {
    initTheme();
    applyTheme(document.documentElement.getAttribute('data-theme') || 'light');

    $('btnTheme').onclick = function () {
      const current = document.documentElement.getAttribute('data-theme');
      applyTheme(current === 'dark' ? 'light' : 'dark');
    };
    $('btnRefresh').onclick = function () { loadList(state.path, true); loadInfo(); };
    $('btnUp').onclick = function () { const parent = parentOf(state.path); if (parent) loadList(parent); };
    $('btnMkdir').onclick = promptMkdir;
    $('btnUpload').onclick = pickFiles;
    $('fileInput').onchange = function (event) {
      startUpload(event.target.files);
      event.target.value = '';
    };
    $('btnSelectAll').onclick = function () {
      if (state.selection.size === state.entries.length && state.entries.length > 0) {
        state.selection.clear();
      } else {
        state.entries.forEach(function (entry) { state.selection.add(entry.path); });
      }
      renderEntries();
      updateSelectionBar();
    };
    $('btnSearch').onclick = runSearch;
    $('btnSearchClear').onclick = clearSearch;
    $('searchInput').onkeydown = function (event) {
      if (event.key === 'Enter') { event.preventDefault(); runSearch(); }
    };
    $('selectionBar').onclick = onBatchClick;
    $('fileBody').onclick = onTableClick;
    $('btnClearUploads').onclick = function () {
      state.uploads = state.uploads.filter(function (item) { return !item.done; });
      renderUploads();
    };
    $('btnSend').onclick = sendMessage;
    $('messageInput').onkeydown = function (event) {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); sendMessage(); }
    };
    $('btnMessagesRefresh').onclick = function () { loadMessages(); toast('消息列表已刷新'); };
    $('btnMessagesClear').onclick = clearMessages;
    $('messageList').onclick = onMessageClick;
    $('modalCancel').onclick = closeModal;
    $('modalOk').onclick = async function () {
      const handler = modalHandler;
      if (!handler) { closeModal(); return; }
      const button = $('modalOk');
      button.disabled = true;
      let result;
      try {
        // handler 都是 async 函数：必须 await，否则拿到的永远是 Promise，校验失败也会关掉弹窗
        result = await handler();
      } finally {
        button.disabled = false;
      }
      if (result === false) return;
      closeModal();
    };
    $('modal').onclick = function (event) { if (event.target === $('modal')) closeModal(); };
    $('previewClose').onclick = function () { $('preview').classList.add('hidden'); };
    $('preview').onclick = function (event) { if (event.target === $('preview')) $('preview').classList.add('hidden'); };
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') {
        closeModal();
        $('preview').classList.add('hidden');
      }
    });
    $('btnAuto').onclick = function () {
      state.autoRefresh = !state.autoRefresh;
      $('btnAuto').textContent = '自动刷新：' + (state.autoRefresh ? '开' : '关');
    };

    window.addEventListener('beforeunload', function (event) {
      if (state.uploading) {
        event.preventDefault();
        event.returnValue = '还有文件正在上传，确定要离开吗？';
        return event.returnValue;
      }
      return undefined;
    });

    initDragAndDrop();
    renderUploads();

    let startPath = '/';
    try { startPath = localStorage.getItem('lanfile.path') || '/'; } catch (e) { startPath = '/'; }

    loadInfo();
    loadMessages();
    loadList(startPath);

    setInterval(autoRefreshTick, 3000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();