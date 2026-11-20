# DeepSeek Chat APK（chat-demo 封装）

把 `https://cdn.deepseek.com/chat-demo` 封装成一个独立 Android App。聊天记录和本地图片存在 App 私有存储里，**清浏览器缓存不会丢**。

## 它做了什么

- 全屏 WebView 加载官方 chat-demo，页面本身一行不改，功能就是网页原生的。
- 打开 `localStorage` + `IndexedDB`，网页设置、API Key、会话、图片都能存进 App 私有目录。
- 补上 WebView 默认缺的两座桥：
  - **导入 ZIP**：接上系统文件选择器，点击网页的 Upload 能选文件。
  - **导出 ZIP**：拦截网页的 `blob:` 下载，分片写进手机「下载」目录（大文件也不会失败）。
- 返回键：先在网页内回退，到首页再退出 App。

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
- **网页打开空白 / 填完 API Key 后白屏**：旧版系统 WebView（如华为机自带 WebView）不支持 CSS 的 `dvh` 视口单位，chat-demo 主框架高度会塌成 0，导致有内容但整片白。本项目已在页面加载后自动注入修复（见 `MainActivity.CSS_FIX`，把 `dvh` 覆盖为 `vh`）。若仍白屏，先确认手机浏览器能正常打开该网址，再看是不是系统 WebView 过旧。
- **网页打不开**：检查手机网络；首次加载要能访问 `cdn.deepseek.com`。
- **导出点了几秒没反应**：大 ZIP 要分片传输，几百 MB 可能要十几秒，等一下看 Toast 提示。
- **"下载"目录找不到文件**：在手机文件管理器里找 `Download` 文件夹，文件名形如 `deepseek_data-xxxx.zip`。

## 未解决问题

- **老手机低版本WebView无法适配附件相关功能，只能导入纯文字会话记录。因此未做文件选择器，无法上传附件，也就是说附件相关功能都没适配。



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
