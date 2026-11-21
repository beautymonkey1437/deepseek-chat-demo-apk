# DeepSeek Chat APK（chat-demo 本地版）

把 DeepSeek 官方开源的 chat-demo 静态页面**内置进 APK**，做成独立 Android App。聊天记录和本地图片存在 App 私有存储里，**清浏览器缓存不会丢**。

## 它做了什么

- **内置官方 chat-demo v0.1.3**（MIT 协议，源码在 `github.com/deepseek-ai/chat-demo`），页面文件在 `app/src/main/assets/chat-demo/`，不再依赖 `cdn.deepseek.com`。
- **关闭内容外审**：把源码里的 `safety_check` 从 `high` 改为 `low`（API 默认审核），对话风格更贴近原始体验。
- **本地 HTTP 服务提供页面**：页面由 App 内置的小型 HTTP 服务（`127.0.0.1:8899`）提供，而非 `file://`——后者在老 WebView 上被视为不安全上下文，会导致麦克风、文件选择等能力失效。
- **兼容老 WebView**：页面里内置了 `toSorted`/`at`/`Object.hasOwn` 等新方法的 polyfill 和 `dvh`→`vh` 修复，解决华为等老机型白屏、导入失败问题。
- **附件上传走原生**：老 WebView 的 `fetch`+`FormData` 上传有 bug，改为把文件通过 JS 桥交给 Android 用原生 `HttpURLConnection` 上传，绕开内核问题。
- **图片上传修复**：官方源码在上传图片时会对图片做尺寸检测/resize（`await new Image().decode()`），老 WebView 上这步会永久卡住，导致图片一直"上传中"、无法发送。已直接 patch 内联 JS（`main.*.js`），让图片跳过 resize、和文档走同样的直接上传路径。AI 可正常识别图片内容。
- **麦克风**：补了 `RECORD_AUDIO` 权限申请和 WebView `onPermissionRequest` 授权（语音识别 ASR 仍受老 WebView 限制，可能不可用）。
- **导入 ZIP**：接上系统文件选择器 + FileProvider，选文件稳定。
- **导出 ZIP**：拦截网页的 `blob:` 下载，分片写进手机「下载」目录（大文件也不会失败）。
- 打开 `localStorage` + `IndexedDB`，网页设置、API Key、会话、图片都能存进 App 私有目录。
- 返回键：先在网页内回退，到首页再退出 App。

> 注意：首次使用仍需联网（要调 DeepSeek API 填 Key）；但页面本身是本地的，不依赖外网域名。

## 重要提醒（先看）

1. **API Key 填的是你自己的 DeepSeek Key，按量付费。** 这个网页是官方 API Demo，不是免费聊天。
2. 数据存在 **App 私有存储**。清系统浏览器缓存不会动它，但**卸载 App / 系统里"清除该 App 数据"会丢**。所以还是建议定期用网页自带的「Export all chats」导出 ZIP 备份到网盘。
3. 首次打开需要联网加载网页；之后每次使用也要联网（因为要调 API）。

## 用 GitHub 免费编译 APK

本机没装 Java / Android SDK 也能编译，用 GitHub 的免费云主机。

### 第一步：把项目传到 GitHub

1. 打开 <https://github.com>，登录（没有就注册，免费）。
2. 点右上角 `+` → `New repository`。
3. 仓库名随便填，比如 `deepseek-chat-apk`，选 **Public**（Private 也行，但公开仓库 Cloud 额度最稳），**不要**勾选 "Add a README"。点 `Create repository`。
4. 在仓库页面点 `uploading an existing file`（或 `Add file` → `Upload files`）。
5. 把本文件夹里的**所有文件和子文件夹**（包括 `.github`、`app`、`build.gradle`、`settings.gradle`、`gradle.properties`、`gradle/`）全选拖进去，点 `Commit changes`。

> `.github` 是隐藏文件夹，拖拽时确认它一起传上去了；编译全靠它。

### 第二步：等 GitHub 自动编译

1. 传完后点顶部 `Actions` 标签。
2. 左边点 `Build DeepSeek Chat APK` → 右边点最近的一次运行。
3. 如果列表里是空的或没自动跑，点 `Run workflow` → `Run workflow` 手动触发一次。
4. 等 2–5 分钟，跑完（绿色勾）后拉到页面最底部 `Artifacts`，点 **`deepseek-chat-apk`** 下载，得到一个 zip。
5. 解压 zip，里面就是 `app-debug.apk`。

### 第三步：装到手机

1. 把 `app-debug.apk` 传到手机（微信文件传输助手 / QQ / 数据线都行）。
2. 在手机上点开它，系统提示"未知来源"时选**允许安装**（需在设置里给浏览器/文件管理器开"安装未知应用"权限）。
3. 装好后打开，填 API Key，开始用。

## 常见问题

- **Actions 显示红色失败**：点进去看日志。最常见是 `.github/workflows/build-apk.yml` 没传上去，或传的时候漏了 `app/` 子目录。
- **网页打开空白 / 填完 API Key 后白屏**：旧版系统 WebView（如华为机自带 WebView）不支持 CSS 的 `dvh` 视口单位，chat-demo 主框架高度会塌成 0，导致有内容但整片白。本项目已在页面加载后自动注入修复（见 `MainActivity.COMPAT_JS`）。
- **导入 ZIP 报"文件格式错误"**：旧 WebView 缺少 `Array.toSorted()` / `at()` 等新方法，chat-demo 导入逻辑用到会直接失败。`COMPAT_JS` 里已 polyfill 这两个方法，导入可正常完成（已在华为 Mate 20 Pro 验证导入 54 个会话成功）。
- **网页打不开**：检查手机网络；首次加载要能访问 `cdn.deepseek.com`。
- **导出点了几秒没反应**：大 ZIP 要分片传输，几百 MB 可能要十几秒，等一下看 Toast 提示。
- **"下载"目录找不到文件**：在手机文件管理器里找 `Download` 文件夹，文件名形如 `deepseek_data-xxxx.zip`。

## 文件结构

```
.
├── .github/workflows/build-apk.yml   # GitHub 自动编译配置
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/deepseek/chatdemo/MainActivity.java   # 全部逻辑
│       └── res/                       # 图标、名称、主题
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```
