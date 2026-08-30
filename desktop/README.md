# Fuyue Convert Desktop

Electron 只负责桌面窗口、安全边界和后端生命周期；所有转换仍由仓库现有的 Java 服务完成。

## 开发预览

先启动现有 Vite 与 Java 服务，再运行：

```bash
cd desktop
npm ci
npm run dev
```

可用 `FORMAT_CONVERTER_DESKTOP_URL` 覆盖默认的 `http://127.0.0.1:5173`。

## 托管后端预览

先暂存当前平台的 JRE、JAR 与配置，再让 Electron 自己启动服务：

```bash
npm run stage:backend
npm run dev:managed
```

暂存结构固定为 `.runtime/backend/{runtime,app,application.yml}`。OCR 与 Poppler 是平台相关原生依赖，可分别通过 `FORMAT_CONVERTER_OCR_HOME`、`FORMAT_CONVERTER_POPPLER_HOME` 加入本地验收包；这些显式暂存目录尚未通过公开再分发审核。

## Windows x64 安装器

请在 Windows x64 构建机使用 JDK 17、Maven 3.9+ 与 Node.js 22 执行：

```powershell
cd desktop
npm ci
$env:FORMAT_CONVERTER_BUNDLE_OCR = "true"
npm run dist:win
```

首版生成 NSIS 安装器。本地完整能力验收可设置 `FORMAT_CONVERTER_BUNDLE_OCR=true`，构建会调用仓库已有脚本准备并验证 OCR 运行目录；需要验证外部 Poppler 时可临时指定 `FORMAT_CONVERTER_POPPLER_HOME`。这两个选项只证明功能可运行，不代表产物可公开再分发。

应用启动时使用随机回环端口与随机 API Token，文件数据写入 Electron `userData`，退出时先请求 Spring Boot 优雅关闭，再清理残留进程树。Windows 安装器应在 Windows x64 构建机完成真实启动、转换和无残留进程验证后再发布。

公开二进制 Release 目前暂停，工作流只有在维护者完成 fat JAR、JRE、OCR 原生库及可选工具的逐项许可/来源审核并显式启用门禁后才会运行。本地生成的安装器只用于开发验收，不得直接上传公开 Release；它也未做商业代码签名，Windows 可能显示 SmartScreen 或“未知发布者”提示。LibreOffice 不随安装器分发，Office 高保真路线会使用用户电脑已有的 LibreOffice。
