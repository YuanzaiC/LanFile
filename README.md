<div align="center">

# 局域网文件 · LanFile

**把手机变成一台局域网文件服务器。**
手机 App 与电脑 / 平板 / 任意设备的浏览器访问的是 **同一个目录**，
文件互传、在线预览、文字互发，全程不经过任何云服务。

[![平台](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#-快速开始)
[![语言](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](#-技术实现)
[![HTTP](https://img.shields.io/badge/HTTP%20%E6%9C%8D%E5%8A%A1-%E6%89%8B%E5%86%99%20ServerSocket-00A9A5)](#为什么手写-http-服务)
[![依赖](https://img.shields.io/badge/%E7%AC%AC%E4%B8%89%E6%96%B9%E4%BE%9D%E8%B5%96-%E4%BB%85%20AndroidX%20%2B%20Material-4C6EF5)](#技术栈)
[![APK](https://img.shields.io/badge/APK-%E7%BA%A6%207%20MB-6b7280)](#-快速开始)

[截图](#-截图) · [功能](#-功能) · [快速开始](#-快速开始) · [使用](#-使用) · [设备管理](#-设备管理) · [技术实现](#-技术实现) · [接口](#-http-%E6%8E%A5%E5%8F%A3) · [常见问题](#-常见问题)

<img src="shot/Main.png" alt="首页" width="320">

</div>

---

## 📸 截图

<table>
  <tr>
    <td align="center" width="25%"><img src="shot/Main.png" alt="首页"><br><b>首页</b><br><sub>状态 / 访问地址 / 快捷入口</sub></td>
    <td align="center" width="25%"><img src="shot/file.jpg" alt="文件"><br><b>文件</b><br><sub>与网页端同一个目录</sub></td>
    <td align="center" width="25%"><img src="shot/message.jpg" alt="消息"><br><b>消息</b><br><sub>聊天气泡 + 来源设备标识</sub></td>
    <td align="center" width="25%"><img src="shot/device.jpg" alt="设备"><br><b>设备</b><br><sub>访问设备 / 封禁 / 单独授权</sub></td>
  </tr>
</table>

**电脑网页端**（浏览器直接打开，无需安装任何东西）

<img src="shot/PCWeb.png" alt="电脑网页端" width="100%">

---

## 💡 这个项目解决什么

手机里存了一堆文件，想传给电脑，常见的做法是：装个「文件传输助手」、插数据线、或者上传到某个网盘再下载。
这个项目换了个思路 —— **手机自己就是服务器**：

1. 手机打开 App，首页直接显示 `http://192.168.x.x:7800`；
2. 电脑浏览器打开这个地址，就看到手机上的「局域网文件」目录；
3. 拖拽上传、点击下载、重命名、删除、在线看图片，手机端刷新即可看到同样结果。

没有账号、没有云端、没有广告，路由器断了外网也能用。适合这些场景：

- 手机 ↔ 电脑 之间倒文件、倒照片、倒安装包；
- 把手机上的电影 / 音乐在电脑或平板上直接播放（支持 `Range` 断点续传）；
- 会议、教室里多台设备共享同一批文件；
- 临时用手机给一台没有装任何工具的设备提供文件下载页。

---

## ✨ 功能

### 亮点

| | |
| --- | --- |
| **同一目录** | 手机端与网页端操作的是**同一个真实目录**，不是两份拷贝：网页上传的文件立刻出现在手机文件管理器里，手机删掉的文件网页刷新后也没了。 |
| **零外部依赖** | 网页端是原生 HTML / CSS / JS，不引用任何 CDN、字体或图片，**完全离线可用**；App 侧 HTTP 服务为手写 `ServerSocket`，没有引入 Ktor / NanoHTTPD 之类的框架。 |
| **大文件友好** | 上传流式解析 multipart、下载流式发送，都不把文件读进内存；支持 `Range` 断点续传与 `206 / 416`。 |
| **设备级管控** | 手机端「设备」页列出所有访问过的设备，可**封禁**（对方整页提示「已被禁止访问」）或**单独授权**（上传 / 删除 / 改动 / 文字互传，还可设为「跟随全局」）。 |
| **用起来顺手** | 聊天气泡式文字互传、页面切换与按钮过渡动画、深浅色主题、服务状态小球（点一下开关服务、长按重启）。 |

### 手机端（App）

| 模块 | 功能 |
| --- | --- |
| 首页 | 服务状态与运行时长、本机 IP、访问地址（一键复制 / 直接打开）、文件管理入口（打开文件页面 / 用其他应用打开此目录）、最近收到的消息、Wi-Fi 与存储权限提示 |
| 文件 | 浏览目录、进入 / 返回、打开（系统应用）、预览（图片 / 文本）、分享、重命名、删除（二次确认）、移动、复制、新建文件夹、多选批量操作、递归搜索、排序（名称 / 大小 / 时间 / 类型）、文件详情、从系统选择器导入并显示进度 |
| 消息 | 聊天气泡（最新在底部），每条带来源设备标识与图标（`本机 · 安卓`、`Windows · Edge`、`Android · Chrome` …），支持复制、删除、清空 |
| 设备 | 访问过网页端的设备列表（IP、设备类型、首次 / 最近访问、请求次数、电脑 / 安卓 / iPhone / 平板分类图标），封禁与单设备授权 |
| 设置 | 主题、默认打开页面、打开文件夹的应用、网页端开关、服务端口、自动启动、后台运行（前台服务）、通知、电池优化白名单、存储目录、所有文件访问权限、初始化目录、停止服务、空间占用 |
| 常驻 | 前台服务 + 通知（显示 `IP:端口`，含「停止服务」按钮）；Wi-Fi 断开 / IP 变化自动刷新状态 |

### 网页端（浏览器，无需安装）

- 目录浏览、面包屑导航、上一级
- 上传：按钮选择 + **整页拖拽**，逐个文件显示进度 / 百分比 / 实时速度 / 剩余时间，可取消
- 下载：流式下载，支持断点续传；多选批量下载
- 删除、重命名、新建文件夹、复制到 / 移动到、递归搜索、批量全选
- 预览：图片与文本直接看，其它类型提示下载
- 文字互传：与手机端一致的**聊天气泡**，带来源设备标识
- 深浅色主题、自动刷新（可开关）、与 App 同款的渐变柔光背景
- **响应式**：手机浏览器打开时自动改为两列按钮布局 + 卡片式文件列表，不用横向滚动

---

## 🚀 快速开始

### 1. 安装 App

到 [Releases](../../releases) 下载 `app-debug.apk`，或用 Android Studio 自行编译（见文末）。

系统要求：**Android 8.0（API 26）及以上**。

### 2. 手机端

1. 打开 App，首页会显示 **本机 IP** 与 **访问地址**（形如 `http://192.168.1.100:7800`）；
2. 状态行为绿色即表示服务已运行；通知栏会出现「局域网文件服务运行中」；
3. 首次使用按提示授予 **通知**、**存储**、Android 16+ 的 **本地网络** 权限。

### 3. 电脑 / 平板 / 其它设备

在浏览器里输入手机上显示的地址（**记得带 `http://`**）：

```
http://192.168.1.100:7800
```

> 同一局域网内任何设备都能访问，无需在对方设备上安装任何软件。

### 4. 网页端能做什么

- 上传（也可把文件直接拖到网页上）、下载、重命名、删除、移动、复制、新建文件夹、搜索；
- 发送文字，手机端「消息」页立刻收到，反之亦然；
- 全部动作直接作用在手机的那个目录上。

---

## 📖 使用

### 「同一个目录」是怎么保证的

根目录由 `StorageManager` 统一决定，手机端与网页端都只认它：

```
内部存储/局域网文件/
├─ 图片/
├─ 视频/
├─ 文档/
├─ 下载/
└─ 其他/
```

网页端**只能**访问这个目录以内的内容：所有路径经 `StorageManager.resolve()` 校验，拒绝 `..`、绝对路径、非法字符与符号链接跳转，越界一律 `403`。

### 权限与存储目录

App 优先使用 **`内部存储/局域网文件/`**（手机文件管理器里可直接看到）。Android 11 及以上需要「所有文件访问权限」：

- 没授权时会自动回退到 **应用专属目录**（`Android/data/com.yuanzai.lanfile/files/局域网文件/`），首页与设置页会给出提示，功能不受影响；
- 授权路径：`设置 → 授予「所有文件访问权限」`，允许后返回并打开「使用内部存储目录」开关（服务会自动重启）。

> Android 10 及以下使用普通存储读写权限（清单已声明，Android 10 额外启用了 `requestLegacyExternalStorage`）。

### 端口

默认 `7800`。若被占用，服务会**自动顺延** `7801 … 7809` 并在首页与通知中提示实际端口；也可以在 `设置 → 服务端口` 里手动指定（1024–65535）。

### 后台运行

打开 `设置 → 后台运行` 后，App 退到后台或锁屏仍继续提供服务（前台服务 + 常驻通知）。关闭时，退出 App 会主动停止服务。

### 网页端的可控开关

`设置 → 网页端` 里可以分别控制网页端能不能**上传 / 删除 / 改动文件 / 文字互传**，也可以改网页标题与单个文件上传上限。
改动立即生效：网页端每 15 秒同步一次配置，被关闭的功能**直接隐藏按钮**；用 `curl` 直接打接口会收到 `403`。

---

## 🛡 设备管理

手机端底部导航「设备」页会记录所有访问过网页端的设备（按客户端 IP 识别，UA 用于显示设备类型）：

- **封禁**：该设备的所有请求返回 `403`。浏览器地址显示**一整页**「此设备已被禁止访问」，接口返回
  `{"ok":false,"error":"此设备已被禁止访问…"}`，正在浏览的页面下一轮轮询就会盖上同样的提示。
- **单独授权**：上传文件 / 删除文件 / 重命名·新建·移动·复制 / 文字互传，每项三态 ——
  **跟随全局设置**（默认）/ **允许** / **禁止**。单设备设置优先于全局开关，`/api/info` 返回的就是该设备生效后的能力。
- **删除记录**：只清掉这台设备（同时解除封禁）；页头可一键清空全部记录。

记录持久化在 `filesDir/clients.json`，最多保留 200 台，超限时淘汰最久未访问且未封禁的记录。

---

## 🎨 界面与交互

- **导航**：首页 / 文件 / 消息 / 设备 / 设置五个页面，切换时页面**按方向左右滑入 + 淡入**，选中项回弹（`ViewPropertyAnimator` 直接驱动，不依赖 Fragment 事务动画）。
- **反馈**：按钮与可点卡片统一「按压缩放 → 回弹」，首页卡片进页面时错落淡入上浮；右下角服务小球在服务状态变化时缩放脉冲。
- **主题**：跟随系统 / 浅色 / 深色，页面底色是「线性渐变 + 两团柔光」，深色单独一套配色。
- **应用图标**：自适应图标（蓝紫渐变底 + 白色文件夹 + 双向传输箭头），并提供 Android 13+ 的单色（主题）图标。

---

## 🧱 技术实现

```
请求 → LanHttpServer（ServerSocket 监听 / 连接池 / 端口回退）
        └─ HttpRequest（请求行 + 头 + chunked / 100-continue 流式 body）
             └─ ApiRouter（/api/* 路由 + 权限校验 + 设备封禁判定）
                  ├─ FileRepository（列表 / 搜索 / 复制 / 移动 / 删除 / 写入）
                  ├─ MessageRepository（文字消息，JSON 持久化）
                  ├─ ClientRegistry（设备记录 / 封禁 / 授权）
                  └─ WebAssets（assets/web 静态资源，内存缓存）
```

### 目录结构

```
LanFile/
├─ app/src/main/
│  ├─ java/com/yuanzai/lanfile/
│  │  ├─ LanFileApp.kt              应用入口：初始化目录、消息、设备记录、通知渠道
│  │  ├─ core/                      与 UI 无关的核心层
│  │  │  ├─ StorageManager.kt       根目录决策 + 路径越权校验（两端唯一数据源）
│  │  │  ├─ FileRepository.kt       列表 / 搜索 / 排序 / 新建 / 重命名 / 删除 / 复制 / 移动 / 写入
│  │  │  ├─ MessageRepository.kt    文字消息（JSON 持久化，最多 500 条）
│  │  │  ├─ ClientRegistry.kt       访问设备（记录 / 封禁 / 单设备授权）
│  │  │  ├─ ExternalOpener.kt       用其他应用打开目录（多形态 Intent 扫描 + 记住选择）
│  │  │  ├─ FileTypes.kt            类型识别、MIME、图标
│  │  │  ├─ FormatUtils.kt          体积 / 速度 / 剩余时间 / 时间格式化
│  │  │  ├─ SettingsStore.kt        设置持久化
│  │  │  ├─ FileImporter.kt         系统文件选择器导入（带进度、可取消）
│  │  │  ├─ FileOpener.kt           系统应用打开 / 分享（FileProvider）
│  │  │  ├─ NotificationHelper.kt   通知渠道 + 前台服务通知
│  │  │  ├─ NetworkUtils.kt         局域网 IP 检测与优选
│  │  │  └─ OpException.kt          带 HTTP 状态码的业务异常
│  │  ├─ model/                     FileEntry / Message / ServerStatus
│  │  ├─ server/                    自研轻量 HTTP/1.1 服务（无第三方依赖）
│  │  │  ├─ LanHttpServer.kt        ServerSocket 监听、连接池、端口回退
│  │  │  ├─ HttpRequest.kt          请求解析（含 chunked、100-continue）
│  │  │  ├─ HttpExchange.kt         响应写出（JSON / 流式文件 / Range / 206）
│  │  │  ├─ HttpIO.kt               缓冲读写 + multipart 流式解析
│  │  │  ├─ ApiRouter.kt            所有 /api/* 路由
│  │  │  ├─ WebAssets.kt            assets/web 静态资源（内存缓存）
│  │  │  └─ LanServerHolder.kt      进程内共享服务器实例
│  │  ├─ service/LanServerService.kt 前台服务（通知、网络回调、启停）
│  │  └─ ui/                        MainActivity + 5 个 Fragment + 适配器 + 预览页
│  ├─ assets/web/                   网页端（原生 HTML / CSS / JS，零外部依赖）
│  └─ res/                          布局 / 图标 / 主题 / FileProvider 路径
└─ gradle/libs.versions.toml        依赖版本目录
```

### 技术栈

| 项目 | 版本 |
| --- | --- |
| JDK | 17 – 21（推荐 21） |
| Gradle | 9.5（仓库自带 wrapper） |
| Android Gradle Plugin | 9.3.3 |
| Kotlin | 2.2 |
| compileSdk / targetSdk | 37 |
| minSdk | 26（Android 8.0） |

依赖只有 AndroidX + Material（`gradle/libs.versions.toml`）：
appcompat 1.8.0、core-ktx 1.19.0、activity-ktx 1.13.0、constraintlayout 2.2.2、
recyclerview 1.2.1、lifecycle-runtime-ktx 2.6.2、kotlinx-coroutines-android 1.9.0、material 1.14.0。

### 为什么手写 HTTP 服务

- 需求很窄：局域网、单目录、流式收发、`Range` 续传、multipart 上传，没有鉴权与并发压测要求；
- 自己实现可以精确控制 **keep-alive、超时、连接池、端口回退、流式写盘**，避免引入不必要的框架与体积；
- 服务只需约 5 个文件（`LanHttpServer` / `HttpRequest` / `HttpExchange` / `HttpIO` / `ApiRouter`），出问题好定位。

### 自己编译

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`，安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 的话：`File → Open` 选择**仓库根目录**（不要打开 `app` 子目录），等待 Sync 完成后点 ▶ Run。

> 若提示缺少 SDK，请在 `local.properties` 写入 `sdk.dir=<你的 Android SDK 路径>`，
> 或在 `Settings → SDK Manager` 里安装 **Platform 37** 与 **Build-Tools 36.0.0**。

---

## 🔌 HTTP 接口

服务只监听局域网，接口自用、无鉴权。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/info` | 设备、版本、根目录、空间、消息数、`clientIp`，以及**该设备**的能力开关（`title` / `allowUpload` / `allowDelete` / `allowModify` / `allowText` / `uploadLimitMb`） |
| GET | `/api/list?path=/` | 目录列表（`entries[]`、`parent`、文件 / 文件夹数量） |
| GET | `/api/search?q=&path=&limit=` | 递归搜索文件名 |
| GET | `/api/stat?path=` | 单个条目详情 |
| GET | `/api/download?path=` | 流式下载（支持 `Range`） |
| GET | `/api/preview?path=` | 内联预览 |
| POST | `/api/mkdir` | `{path, name}` |
| POST | `/api/rename` | `{path, newName}` |
| POST | `/api/delete` | `{paths:[...]}` |
| POST | `/api/copy` `/api/move` | `{paths:[...], dest}` |
| POST | `/api/upload?path=` | `multipart/form-data`，文件字段名 `files`（可多个） |
| GET | `/api/messages?limit=&since=` | 消息列表 |
| POST | `/api/text` | `{text, source}` 发送文字 |
| POST | `/api/messages/delete` | `{ids:[...]}` 或 `{all:true}` |

文件条目 JSON：`{name, path, isDir, size, modified, type, ext, mime}`，
`type` 取值 `dir / image / video / audio / document / archive / apk / text / other`。

命令行的例子：

```bash
# 看目录
curl "http://192.168.1.100:7800/api/list?path=/"

# 传文件
curl -F "files=@./report.pdf" "http://192.168.1.100:7800/api/upload?path=/文档"

# 发一条文字
curl -H "Content-Type: application/json" \
     -d '{"text":"来自命令行的问候"}' \
     "http://192.168.1.100:7800/api/text"
```

---

## ❓ 常见问题

<details>
<summary><b>电脑打不开网页，手机自己却可以</b></summary>

依次排查：

1. 手机是否授予了 **本地网络权限**（Android 16+ 必须，见下节）；
2. 路由器是否开了「AP 隔离 / 客户端隔离」；
3. 电脑是否在访客网络 / 不同 SSID；
4. 电脑防火墙是否拦了局域网入站；
5. 手机是否开着 VPN。

</details>

<details>
<summary><b><code>curl</code> 能看到网页，浏览器却打不开（甚至报 <code>502</code>）</b></summary>

服务和网络都正常，这是**电脑浏览器 / 代理**的问题（`curl` 不走系统代理，浏览器走）：

1. 地址必须带 `http://`；
2. 关闭浏览器的「始终使用安全连接 / HTTPS 优先 / 安全 DNS」；
3. 关闭 Windows 系统代理：设置 → 网络和 Internet → 代理 → 「使用代理服务器」改为**关**；
4. 退出电脑上的 VPN / 加速器 / 安全软件。

`502` 不是本服务返回的（服务只会返回 `200 / 206 / 400 / 404 / 405 / 408 / 413 / 416 / 500`）。
最快的验证方式：`start chrome --no-proxy-server http://<手机IP>:<端口>/`。

</details>

<details>
<summary><b>其它常见现象</b></summary>

| 现象 | 原因与处理 |
| --- | --- |
| 手机连的是没有外网的路由器 | Android 会把默认网络保持为移动数据，典型表现就是「手机能开、电脑不能开」。App 已自动绑定局域网网络；临时关掉移动数据可确认是否属于这一类。 |
| 首页显示「未连接局域网」 | 当前没有可用的局域网网络（Wi-Fi / 以太网都没连），连上后会自动重新监听并更新地址。 |
| 手机有两个 Wi-Fi 地址 | 系统的「双 Wi-Fi / 双路并发」。服务在通配地址上监听，**两个地址都能打开**，指向同一个目录；若「连上了却收不到数据」，关闭「双 Wi-Fi 加速 / WLAN+ / 智能选网」后重启服务。 |
| 对方连得上却传不了数据 | ① 关闭双 Wi-Fi 加速 / 智能选网；② 关闭 VPN 与移动数据；③ 检查 IP 冲突；④ 换一台设备试。 |
| 电脑能打开网页但手机没有外网 | 正常：网页端不引用任何外部 CDN / 字体 / 图片，完全离线可用。 |
| 端口被占用 | 自动顺延 `+1 … +9`，首页与通知会提示实际端口；也可手动改。 |
| 退出 App 后服务停止 | `设置 → 后台运行` 打开即可。 |
| Android 15+ 长时间后台后被回收 | 系统对前台服务有时长限制，重新打开 App 会自动恢复（首页显示实际状态）。 |
| 文件名含 emoji / 中文 | 两端均按 UTF-8 处理，下载使用 RFC 5987 `filename*`，浏览器可正确保存。 |
| 手机上看不到网页刚上传的文件 | 在「文件」页下拉刷新；网页端会自动刷新。 |

</details>

### Android 16+ 的「本地网络访问」权限

从 **Android 16（API 36）** 起，系统对 `targetSdk ≥ 36` 的 App 默认禁止访问本地网络，**包括接受局域网设备连入的 TCP 连接** —— 这是「其他设备连不进来」的头号原因。

- 权限名：`android.permission.ACCESS_LOCAL_NETWORK`（运行时权限）；
- 本项目的处理：清单已声明；首次打开会请求授权，**首页会醒目提示「本地网络权限未授权：其他设备无法访问」**，点提示即可重新授权，授权成功后自动重启服务（需要重建监听 socket 才生效）。

---

## ⚠️ 已知限制

- 网页端**无鉴权**，定位是「同一 Wi-Fi 内互信」；如需更强的隔离，请在路由器上开启访客网络隔离，或把手机连到独立网段。
- 视频 / 音频在 App 内不做自绘播放器，调用系统播放器（网页端可直接在线播放）。
- 拖拽上传不支持文件夹递归，请用系统选择器多选文件。
- 预览仅支持图片与文本，其它类型走「用其他应用打开」。
- `MANAGE_EXTERNAL_STORAGE`（所有文件访问权限）属于敏感权限，Google Play 对它有额外审核要求；不授权时自动回退到应用专属目录，功能不受影响。
- 设备识别基于 **客户端 IP**，同一 NAT 后的多台设备会被视为同一台；IP 变化会被当作新设备。

---

## ✅ 验收清单

<details>
<summary>按顺序验证一遍（点击展开）</summary>

1. 打开 App → 首页显示 IP 与访问地址，通知栏出现「局域网文件服务运行中」。
2. 浏览器打开地址 → 能看到「局域网文件」目录与子目录。
3. 网页上传文件 → 手机「文件」页刷新后能看到**同一个文件**。
4. 手机新建文件夹并放入文件 → 网页刷新后可见。
5. 网页把文件移动到「文档」→ 手机进入「文档」能看到。
6. 网页删除文件 → 手机端同步消失。
7. 网页发送文字 → 手机「消息」页出现，且带来源设备标识。
8. 手机发送文字 → 网页消息列表出现。
9. 网页下载一个几百 MB 的文件 → 进度正常、可断点续传。
10. 上滑退出 App（后台运行已开启）→ 网页仍可访问，通知仍在。
11. 关闭手机 Wi-Fi → 首页提示未连接局域网；重新打开后自动恢复。
12. 端口改成被占用的值 → 自动改用其它端口并提示。
13. 小球：点一下开关服务（绿 / 灰切换），长按重启；五个页面都不遮挡底部导航、添加按钮与输入框。
14. `设置 → 通用 → 默认打开页面` 选「文件」→ 完全退出再打开 → 直接进入文件页。
15. `设置 → 网页端` 改标题 → 网页刷新后标题与标签一起变；上传上限设为 100 MB → 更大文件被拦下。
16. 依次关闭上传 / 删除 / 改动 / 文字互传 → 网页端对应按钮**直接消失**；`curl` 打接口收到 `403`。
17. 首页「用其他应用打开此目录」→ 第一次弹选择框（列表应包含 MT 管理器，没有就用「从全部已安装应用中选择…」）→ 之后直接用它打开。
18. 手机浏览器打开网页：按钮两排、列表卡片化；消息是左右气泡，最新在最下面。
19. 电脑访问后，手机「设备」页出现这台电脑；封禁 → 电脑整页提示「此设备已被禁止访问」；解除后恢复。
20. 只关掉某台电脑的「上传文件」→ 那台电脑上传卡片消失，`curl` 打 `/api/upload` 返回 403。

</details>

---

## 📄 说明

- 本项目为个人自用工具，按现状提供，**未附加开源许可证**；如需转载或二次分发，请先联系作者。
- 所有文件都在你自己的设备与局域网内流转，App 不联网、不上传任何数据、不含统计与广告 SDK。
- 欢迎提 Issue 交流使用中遇到的问题；如果这个项目对你有帮助，点个 ⭐ 就是最好的支持。

<div align="center"><sub>Made with Kotlin &amp; a hand-written HTTP server.</sub></div>