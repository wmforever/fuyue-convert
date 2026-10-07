# Fuyue Convert Desktop

Electron 只负责桌面窗口、安全边界和后端生命周期；所有转换仍由仓库现有的 Java 服务完成。

## 用户下载

用户可在 [GitHub Releases](https://github.com/wmforever/fuyue-convert/releases/latest) 按设备下载 Lite（不带 Office）或 Full（内置 LibreOffice）安装包，无需另外安装 Java：

- Windows 10/11 x64：`Fuyue-Convert-<version>-win-x64.exe`
- macOS 13+ Intel：`Fuyue-Convert-<version>-macOS-Intel.dmg`
- macOS 13+ M1/M2/M3/M4 等 Apple Silicon：`Fuyue-Convert-<version>-macOS-Apple-Silicon.dmg`

Full 文件名在扩展名前增加 `-Full`，例如 `Fuyue-Convert-0.1.5-win-x64-Full.exe`。

当前源码支持跨平台 Lite 与 Full；OCR 改动需要重新构建发布后进入安装包：

- 内置经 `jlink` 精简的 Eclipse Temurin 17.0.20.1+1。
- 两版均内置固定源码构建的静态 Tesseract 与中英文模型，不依赖用户电脑安装 OCR；不捆绑 Poppler。
- 应用内包含项目、前端、字体、Electron/Chromium、Temurin，以及 Windows 所用 NSIS 的许可/来源文件。
- Lite 不捆绑 LibreOffice；Office 高保真路线使用用户电脑上已安装的 LibreOffice。
- Full 内置官方 LibreOffice 26.2.5.2，并锁定来源、架构、版本和文件哈希。

Windows 暂未做商业代码签名，可能显示 SmartScreen 或“未知发布者”。macOS 当前为 ad-hoc 签名且未经过 Apple 公证，首次启动如被 Gatekeeper 拦截，请前往“系统设置 → 隐私与安全”选择“仍要打开”。六个安装文件的 SHA-256 都写在 Release 正文。

## 保存结果与使用偏好

- 用户手动点击下载时，渲染页只向 Electron 主进程传入 `taskId`。主进程会向本地后端查询任务元数据、确认结果已可下载，再打开系统“另存为”对话框。
- 主进程从回环后端流式下载到同目录临时文件，传输完整后再替换目标文件；大文件不需经过渲染页整体缓冲。
- 成功保存后记住目标目录；已记录目录不存在时回退到系统下载目录。普通网页环境没有桌面桥接时，仍由浏览器处理下载。
- 自动下载、PDF 默认压缩等级和“源格式 → 上次选择的可用目标格式”会保存为偏好。桌面版写入 Electron `userData/desktop-preferences.json`，纯网页版写入当前浏览器的 `localStorage`。
- 只有当前仍标记为 `available` 的目标路线会被恢复；损坏、旧版本或不合法的偏好值会回退到安全默认值，不影响应用启动。

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

暂存结构固定为 `.runtime/backend/{runtime,app,licenses,application.yml}`，OCR 默认包含。`FORMAT_CONVERTER_OCR_HOME` 可复用通过固定来源、模型、许可证和真实识别校验的运行时；公开发布仍拒绝 `FORMAT_CONVERTER_POPPLER_HOME`。仅本地开发可明确设置 `FORMAT_CONVERTER_BUNDLE_OCR=false`。详见 [OCR 打包与运行说明](../docs/ocr-deployment.md)。

## Windows x64 正式打包

在 Windows x64 构建机安装 Maven 3.9+、Node.js 22、CMake、Visual Studio C++ Build Tools 和精确版本的 Eclipse Temurin 17.0.20.1+1，然后执行：

```powershell
cd desktop
npm ci --no-audit --no-fund
$env:FORMAT_CONVERTER_BUNDLE_OCR = "true"
$env:FORMAT_CONVERTER_PUBLIC_LITE_RELEASE = "true"
$env:FORMAT_CONVERTER_REQUIRE_TEMURIN_RUNTIME = "true"
$env:FORMAT_CONVERTER_REQUIRED_RUNTIME_VERSION = "17.0.20.1"
npm run dist:win
npm run verify:package -- --public-lite --require-installer
```

产物为 `release/Fuyue-Convert-0.1.5-win-x64.exe`。Electron-builder 只生成 `win-unpacked`，仓库自有的最小安装脚本再使用 `nsis@1.2.1` 工具集中的 NSIS 3.12 编译安装器。该脚本只使用 NSIS 内建的 `File`、`CreateShortCut`、`WriteUninstaller` 等指令，采用 zlib 压缩并以当前用户权限安装；不使用 StdUtils、UAC、WinShell、nsProcess、nsis7z 或 `elevate.exe`，也不得回退到旧 NSIS 3.0.4.1。

官方 Actions 会额外将 NSIS 安装器静默安装到临时目录，对真实安装后资源再执行一次许可、Runtime、禁止依赖、OCR 中英文真实识别和 Poppler 缺席检查，而不只检查 `win-unpacked`。

Full 版执行 `npm run dist:win:full`，产物为 `release/Fuyue-Convert-0.1.5-win-x64-Full.exe`，并额外完成包内 LibreOffice 的真实 DOCX → PDF 转换。

## 用户引擎配置

新版“设置 → OCR 与 Office 配置”可选择本机 Tesseract、tessdata 目录和 LibreOffice，并实际检测、保存与重启生效；存在未完成任务或原生保存时阻止重启。默认使用内置引擎，不继承系统遗留的 OCR 停用/路径设置，不需要用户绑定账号。Lite/Full 均必须包含 OCR；Office 仍由 Full 提供，Lite 可指定用户已有的本机引擎。旧发布包不会自动增加这些功能。

`npm run verify:package` 默认要求随包 OCR，包括执行文件、中英模型和 TSV 配置。

## macOS 原生 Lite 打包

Intel 与 Apple Silicon 必须分别在同架构 Mac 上构建，使用 Maven 3.9+、Node.js 22、CMake、Xcode Command Line Tools 和精确版本 Eclipse Temurin 17.0.20.1+1：

```bash
cd desktop
npm ci --no-audit --no-fund
export FORMAT_CONVERTER_BUNDLE_OCR=true
export FORMAT_CONVERTER_PUBLIC_LITE_RELEASE=true
export FORMAT_CONVERTER_REQUIRE_TEMURIN_RUNTIME=true
export FORMAT_CONVERTER_REQUIRED_RUNTIME_VERSION=17.0.20.1
npm run dist:mac
```

原生 x64 构建输出 `release/Fuyue-Convert-0.1.5-macOS-Intel.dmg`，原生 arm64 构建输出 `release/Fuyue-Convert-0.1.5-macOS-Apple-Silicon.dmg`。流程会在签名后定稿运行时清单、重新进行 ad-hoc 外层签名，再执行 `codesign --deep --strict`、DMG 校验、只读挂载和最终资源对账。当前不做 universal DMG，也不生成公开 ZIP。

Full 版执行 `npm run dist:mac:full`，输出文件名增加 `-Full`，并额外验证包内 LibreOffice 的版本、原生架构、许可证和真实 DOCX → PDF 转换。

## 发布门禁

### 只构建安装包

`.github/workflows/desktop-package.yml` 在该文件合入 main 时构建 Intel 和 Apple Silicon 两种 Full DMG。也可在 Actions 中手动选择 macOS、Windows 或全部平台。构建复用原生安装、内置 OCR/Office 转换和退出检查，完成后把安装包、`BUILD-INFO.json` 和 `SHA256SUMS` 保存为 14 天有效的 Actions 产物；产物名包含完整源码 SHA。它不创建 Release 或修改版本标签，不使用下述发布开关。现有版本号可能与旧安装包相同，请用源码 SHA 和校验值区分构建。

### 公开发布

`.github/workflows/desktop-release.yml` 只在推送与应用版本一致的 `v*` 标签后运行，并且同时要求仓库变量 `FORMAT_CONVERTER_BINARY_RELEASE_APPROVED=true` 与 `FORMAT_CONVERTER_BINARY_RELEASE_APPROVED_SHA=<已审核提交>`。这两个变量是维护者手动、短时开启的发布开关：

```bash
gh variable set FORMAT_CONVERTER_BINARY_RELEASE_APPROVED --body true
gh variable set FORMAT_CONVERTER_BINARY_RELEASE_APPROVED_SHA --body "$(git rev-parse HEAD)"
git tag v0.1.5
git push origin v0.1.5
gh variable set FORMAT_CONVERTER_BINARY_RELEASE_APPROVED --body false
gh variable delete FORMAT_CONVERTER_BINARY_RELEASE_APPROVED_SHA
```

只有在最终 fat JAR 禁止依赖、许可束、对应源码，以及 Windows x64、macOS Intel、macOS Apple Silicon 的 Lite/Full 安装、启动、转换和退出验收全部通过后才能开启。主 Release 只允许六个安装文件，其他清单只保留在应用内或 Actions 审计产物中。发布完成后应立即关闭变量，避免后续任务意外生成公开二进制。

## 运行安全

应用启动时使用随机回环端口与随机 API Token，文件数据与偏好设置写入 Electron `userData`，退出时先请求 Spring Boot 优雅关闭，再清理残留进程树。正式发布前，Windows 需完成安装/卸载，两个 Mac 架构需完成 DMG 挂载/复制/删除；三者都必须完成真实转换、下载、优雅退出和无残留进程验收。
