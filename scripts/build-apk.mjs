#!/usr/bin/env node
/**
 * 打包 APK：网页产物 → 安卓工程资源 → Gradle 出包。
 *
 *   pnpm apk                 构建网页、同步资源，本机有 Android SDK 时直接出 APK
 *   pnpm apk:assets          只构建网页并同步到 apk/app/src/main/assets/chat-demo
 *   node scripts/build-apk.mjs --skip-build   复用已有 dist，只同步资源
 *
 * 没有 Android SDK 时脚本停在资源同步这一步，并提示用 GitHub Actions 出包
 * （仓库自带 .github/workflows/build-apk.yml）。
 */
import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')
const apkDir = join(root, 'apk')
const assetsDir = join(apkDir, 'app', 'src', 'main', 'assets', 'chat-demo')

const args = new Set(process.argv.slice(2))
const assetsOnly = args.has('--assets-only')
const skipBuild = args.has('--skip-build')

const run = (command, argv, options = {}) => {
  const result = spawnSync(command, argv, {
    stdio: 'inherit',
    cwd: root,
    shell: process.platform === 'win32',
    ...options,
  })
  if (result.error) {
    throw result.error
  }
  if (result.status !== 0) {
    throw new Error(command + ' ' + argv.join(' ') + ' 失败，退出码 ' + result.status)
  }
}

/** 统计目录里的文件数与字节数，用于在日志里确认资源完整 */
const measure = (dir) => {
  let files = 0
  let bytes = 0
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const info = statSync(full)
    if (info.isDirectory()) {
      const nested = measure(full)
      files += nested.files
      bytes += nested.bytes
    } else {
      files += 1
      bytes += info.size
    }
  }
  return { files, bytes }
}

const buildWeb = () => {
  console.log('▸ 构建网页（pnpm build）')
  run('pnpm', ['build'])
  if (!existsSync(dist)) {
    throw new Error('构建完成但没有 dist 目录')
  }
}

/** 把 dist 复制进 assets，并在 index.html 里挂上 WebView 兼容补丁 */
const syncAssets = () => {
  const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
  rmSync(assetsDir, { recursive: true, force: true })
  mkdirSync(assetsDir, { recursive: true })
  cpSync(dist, assetsDir, { recursive: true })
  cpSync(join(apkDir, 'webview-compat.js'), join(assetsDir, 'webview-compat.js'))

  // 补丁要排在应用脚本之前：它只补语言能力和 dvh，页面加载前生效才有意义
  const indexPath = join(assetsDir, 'index.html')
  const html = readFileSync(indexPath, 'utf8')
  if (!html.includes('webview-compat.js')) {
    writeFileSync(indexPath, html.replace(/<head>/i, '<head><script src="webview-compat.js"></script>'))
  }

  const { files, bytes } = measure(assetsDir)
  console.log('▸ 资源已同步：' + files + ' 个文件，' + (bytes / 1024 / 1024).toFixed(1) + ' MB → ' + assetsDir)
  console.log('▸ 版本 ' + pkg.version + '（APK versionName 取自 package.json）')
}

/** 本机有 Android SDK 时直接出包，否则提示改用 CI */
const buildApk = () => {
  const hasSdk = Boolean(process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT)
  const wrapper = process.platform === 'win32' ? join(apkDir, 'gradlew.bat') : join(apkDir, 'gradlew')
  const gradle = existsSync(wrapper) ? wrapper : 'gradle'

  if (!hasSdk) {
    console.log('')
    console.log('⚠ 未检测到 Android SDK（ANDROID_HOME / ANDROID_SDK_ROOT），跳过出包。')
    console.log('  资源已就绪，可以直接用 GitHub Actions 出包：')
    console.log('    推送仓库 → Actions → Build APK → 下载 chat-demo-<版本>-debug.apk')
    console.log('  或者装好 Android SDK 后重跑：pnpm apk')
    return
  }

  console.log('▸ 编译 APK（gradle assembleDebug）')
  run(gradle, ['assembleDebug', '--stacktrace'], { cwd: apkDir })
  console.log('')
  console.log('✓ 产物：apk/app/build/outputs/apk/debug/app-debug.apk')
}

const main = () => {
  if (!skipBuild) {
    buildWeb()
  }
  syncAssets()
  if (!assetsOnly) {
    buildApk()
  }
}

try {
  main()
} catch (error) {
  console.error('')
  console.error('✗ ' + (error instanceof Error ? error.message : String(error)))
  process.exit(1)
}
