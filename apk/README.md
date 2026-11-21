# APK 打包（Android）

把仓库里构建出的 chat-demo 网页打进一个独立 App，手机上装完就能用，不依赖任何 CDN。

- 网页产物 → 由 `pnpm apk(:assets)` 生成到 `app/src/main/assets/chat-demo/`（不提交进仓库）
- 版本号：`app/build.gradle` 直接读仓库根 `package.json`，APK 与网页同版本（当前 0.1.3）
- 页面由 App 内部的本地 HTTP 服务（`127.0.0.1:8899`）提供，而不是 `file://`：
  后者在老 WebView 上属于不安全上下文，麦克风、文件选择等能力会失效
- `webview-compat.js` 在页面脚本之前注入，补 `toSorted`/`at`/`Object.hasOwn`/`CSS.escape` 等新方法和 `dvh`→`vh`

## 一键出包

```bash
pnpm install
pnpm apk            # 构建网页 → 同步资源 → 有 Android SDK 时直接 assembleDebug
pnpm apk:assets     # 只构建网页并同步资源
```

本机没有 Android SDK 时脚本会停在资源同步并提示用 CI 出包（见下）。

装好 SDK 后可以直接出包：

```bash
export ANDROID_HOME=$HOME/Android/Sdk     # Windows: set ANDROID_HOME=C:\Android\Sdk
pnpm apk
# 产物：apk/app/build/outputs/apk/debug/app-debug.apk
```

## 用 GitHub Actions 出包（无需本机 SDK）

`.github/workflows/build-apk.yml` 已经串好整条链路：装 pnpm → 类型检查/lint → 构建网页并同步资源
→ 装 JDK 17 与 Android SDK → Gradle 出 debug APK → 上传产物。

1. 推送代码（或手动触发 `Actions → Build APK → Run workflow`）
2. 跑完在那次运行的 Artifacts 里下载 `chat-demo-apk`
3. 打 tag（`git tag v0.1.3 && git push --tags`）时还会额外建一个 Release，手机可以直接下载安装

APK 用 debug 签名，能直接侧载；要上商店再换成自己的 release 签名。

## 在手机上用什么接口

App 内置的是本仓库的版本，设置 → 数据管理里可以自己填：

| 项 | 说明 |
| --- | --- |
| 接口协议 | `Anthropic Messages` 或 `OpenAI Chat Completions` |
| 接口地址 | 例如 `http://192.168.1.5:17890/v1`（局域网网关，需与手机同一网络） |
| 模型名 | 手填或点箭头从该地址拉取列表 |
| API Key | 你自己的 Key |

明文 HTTP 已经放开（`network_security_config.xml` 的 `base-config`），
所以指向局域网网关不会被系统拦掉；填 `127.0.0.1` 则只会指向手机自己。

## 已知取舍

- **老 WebView 的两个坑**：`dvh` 与 ES2022/2023 新方法由 `webview-compat.js` 兜住；
  图片尺寸检测里 `HTMLImageElement.decode()` 在个别老内核上不返回，源码里做了 4 秒超时兜底，
  超时就按原图上传，不会一直卡在“上传中”。
- `MainActivity` 里的原生上传桥（`DSNative.uploadFile`）当前没有接上，页面直接用 WebView 上传；
  如果某台设备上上传总是失败，再把页面里的 fetch 上传改走这个桥。
