# Fuyue Convert Desktop

Electron 只负责桌面窗口、安全边界和后端生命周期；所有转换仍由仓库现有的 Java 服务完成。

## 用户下载

Windows 10/11 x64 用户可在 [GitHub Releases](https://github.com/wmforever/fuyue-convert/releases/latest) 下载 `Fuyue-Convert-<version>-win-x64.exe`，安装后可直接从桌面或开始菜单运行，无需另外安装 Java。

v0.1.4 是 Windows x64 Lite 版：

- 内置经 `jlink` 精简的 Eclipse Temurin 17.0.20.1+1。
- 不捆绑 OCR/Tesseract 和 Poppler，避免未审核的本地原生库被意外带入公开安装包。
- 安装目录内包含项目、前端、字体、Electron/Chromium、Temurin 和 NSIS 的许可/来源文件。
- 不捆绑 LibreOffice；Office 高保真路线会使用用户电脑上已安装的 LibreOffice。

首个公开安装包暂未做商业代码签名，Windows 可能显示 SmartScreen 或“未知发布者”提示。请先在 Release 页面对照 `SHA256SUMS` 校验下载文件。

## 开发预览

先启动现有 Vite 与 Java 服务，再运行：

```bash
cd desktop
npm ci --no-audit --no-fund
npm run dev
```

可用 `FORMAT_CONVERTER_DESKTOP_URL` 覆盖默认的 `http://127.0.0.1:5173`。

## 托管后端预览

先暂存当前平台的 JRE、JAR 与配置，再让 Electron 自己启动服务：

```bash
npm run stage:backend
npm run dev:managed
```

暂存结构固定为 `.runtime/backend/{runtime,app,licenses,application.yml}`。开发者可通过 `FORMAT_CONVERTER_OCR_HOME` 或 `FORMAT_CONVERTER_POPPLER_HOME` 生成仅用于本地验收的扩展包；这些目录会被公开 Lite 发布门禁明确拒绝。

## Windows x64 正式打包

在 Windows x64 构建机安装 Maven 3.9+、Node.js 22 和精确版本的 Eclipse Temurin 17.0.20.1+1，然后执行：

```powershell
cd desktop
npm ci --no-audit --no-fund
$env:FORMAT_CONVERTER_BUNDLE_OCR = "false"
$env:FORMAT_CONVERTER_PUBLIC_LITE_RELEASE = "true"
$env:FORMAT_CONVERTER_REQUIRE_TEMURIN_RUNTIME = "true"
$env:FORMAT_CONVERTER_REQUIRED_RUNTIME_VERSION = "17.0.20.1"
npm run dist:win
npm run verify:package -- --public-lite --require-installer
```

产物为 `release/Fuyue-Convert-0.1.4-win-x64.exe`。Electron-builder 只生成 `win-unpacked`，仓库自有的最小安装脚本再使用 `nsis@1.2.1` 工具集中的 NSIS 3.12 编译安装器。该脚本只使用 NSIS 内建的 `File`、`CreateShortCut`、`WriteUninstaller` 等指令，采用 zlib 压缩并以当前用户权限安装；不使用 StdUtils、UAC、WinShell、nsProcess、nsis7z 或 `elevate.exe`，也不得回退到旧 NSIS 3.0.4.1。

官方 Actions 会额外将 NSIS 安装器静默安装到临时目录，对真实安装后资源再执行一次许可、Runtime、禁止依赖和 OCR/Poppler 缺席检查，而不只检查 `win-unpacked`。Release 还会同时上传 Temurin 与 NSIS 的精确对应源码、SBOM 和 `SHA256SUMS`。

## 发布门禁

`.github/workflows/desktop-release.yml` 只在推送与应用版本一致的 `v*` 标签后运行，并且同时要求仓库变量 `FORMAT_CONVERTER_BINARY_RELEASE_APPROVED=true` 与 `FORMAT_CONVERTER_BINARY_RELEASE_APPROVED_SHA=<已审核提交>`。这两个变量是维护者手动、短时开启的发布开关：

```bash
gh variable set FORMAT_CONVERTER_BINARY_RELEASE_APPROVED --body true
gh variable set FORMAT_CONVERTER_BINARY_RELEASE_APPROVED_SHA --body "$(git rev-parse HEAD)"
git tag v0.1.4
git push origin v0.1.4
gh variable set FORMAT_CONVERTER_BINARY_RELEASE_APPROVED --body false
gh variable delete FORMAT_CONVERTER_BINARY_RELEASE_APPROVED_SHA
```

只有在最终 fat JAR 禁止依赖、许可束、对应源码与 Windows 实机启动/转换/退出验收全部通过后才能开启。发布完成后应立即关闭变量，避免后续标签意外生成公开二进制。

## 运行安全

应用启动时使用随机回环端口与随机 API Token，文件数据写入 Electron `userData`，退出时先请求 Spring Boot 优雅关闭，再清理残留进程树。Windows 正式发布前仍需在真实 Windows 10/11 x64 机器上完成首次安装、启动、至少一条真实转换、下载、卸载和无残留进程验收。
