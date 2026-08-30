# Fuyue Convert

[简体中文](README.md) | [English](README_EN.md)

[![CI](https://github.com/wmforever/fuyue-convert/actions/workflows/ci.yml/badge.svg)](https://github.com/wmforever/fuyue-convert/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/source%20license-Apache--2.0-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-4c8cbf.svg)](pom.xml)

Fuyue Convert 是一个开源文档格式转换平台，目标是用可审计、可替换的开源组件完成常见办公文档、国产格式和版式文档之间的转换。

项目基于 Java 17、Spring Boot、Vue 3、Apache POI、PDFBox、Poppler、OFDRW 和 LibreOffice headless。它不会承诺所有格式都能做到“完全一致且完全可编辑”，而是把转换能力拆成明确的路线、质量等级和失败原因，让结果可以被自动验证，也方便社区逐步增强。

> 自有源码采用 Apache-2.0；构建依赖继续适用各自许可证。官方 Windows x64 Lite 桌面版通过独立的运行时来源、许可、哈希和安装后冒烟门禁发布，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 直接下载桌面版

[下载 Fuyue Convert v0.1.4（Windows 10/11 x64）](https://github.com/wmforever/fuyue-convert/releases/download/v0.1.4/Fuyue-Convert-0.1.4-win-x64.exe)

安装包内置 Eclipse Temurin Java Runtime，安装后可直接运行，不需要另外安装 Java。完整文件、`SHA256SUMS`、许可证、对应源码和测试材料见 [v0.1.4 Release](https://github.com/wmforever/fuyue-convert/releases/tag/v0.1.4)。

这是 Lite 版：不内置 OCR/Tesseract、Poppler 或 LibreOffice；PDF 基础路线有内置回退，OCR 路线会显示不可用，Office 高保真路线会使用电脑上已有的 LibreOffice。首个公开版本尚未做商业代码签名，Windows 可能显示 SmartScreen 或“未知发布者”提示。macOS/Linux 桌面安装包尚未开放。

## 项目定位

- 开源优先：默认使用开源库和本机命令行工具，不依赖云服务。
- 路线可扩展：每条转换能力都实现为独立 `FileConverter`。
- 质量透明：能力 API 返回 `stable`、`beta`、`experimental`、依赖和已知限制。
- 质量可验证：提供像素级视觉对比和数据回环 QA 脚本。
- 失败透明：页数不一致、格式不支持、字体替代、图像层兜底等情况会通过错误或警告暴露。
- 双目标并存：可编辑优先和保真优先是不同目标，项目会明确标注取舍。

## 当前能力矩阵

状态说明：

- `stable`：有自动化测试覆盖，输入边界明确，适合日常使用。
- `beta`：主流程可用，真实文档里可能受字体、引擎或格式特性影响。
- `experimental`：能力已接入，但输出质量和兼容范围仍需要更多样本验证。

| 路线 | 状态 | 默认策略 | 说明 |
| --- | --- | --- | --- |
| OFD -> DOCX/TXT/PDF/PNG/JPG | beta | 结构/版式 | DOCX/TXT 使用结构化解析；含中日韩文字的 DOCX 嵌入已许可的回退字体，避免换机后文字不可见。未配置 OCR 时扫描页严格失败，配置本地 Tesseract 后对扫描图像补充坐标文字。PDF/PNG/JPEG 按源坐标绘制文字、图片、路径和普通图片型签章；嵌套 OFD 签章外观会明确警告并跳过，正文仍保留。图片固定为 160 DPI，多页输出 ZIP。 |
| OFD -> XLSX | experimental | 数据优先 | 将高置信度有线规则表格写成真实单元格、分页工作表和合并区域；未识别到可靠表格返回 `NO_TABLE_FOUND`，扫描页返回 `OCR_REQUIRED`。 |
| CSV <-> XLSX | stable | 数据优先 | CSV 支持 UTF-8/UTF-16 BOM/GB18030 与逗号、TAB、分号、竖线识别；输入统一写成文本以阻断公式注入。XLSX 公式导出缓存结果，日期按单元格格式输出，多工作表分别导出 CSV ZIP。 |
| DOCX/XLSX/PPTX -> PDF | beta | 版式优先 | 有 LibreOffice 时使用隔离的 headless profile 转换，校验真实 PDF 页数；本地字体会影响结果。 |
| TXT -> DOCX/PDF | stable | 内容优先 | 支持 UTF-8、带 BOM 的 UTF-16 和严格 GB18030 解码；换页符生成真实分页，PDF 按字形宽度进行 CJK 换行。DOCX 未显式配置东亚字体时按需嵌入内置许可字体，显式配置时保持指定字体。 |
| DOCX -> TXT | beta | 内容提取 | 按正文对象顺序提取段落和表格，并带标签追加页眉页脚、文本框、脚注尾注、批注及修订文字；不保留版式。 |
| PDF -> TXT | beta | 内容提取 | 按页面坐标和多栏顺序提取文字并保留换页；默认对扫描页返回 `OCR_REQUIRED`，显式配置本地 OCR 后仅识别缺少文字的内容页。 |
| PDF -> PNG/JPG | stable | 版式渲染 | 默认 160 DPI（可配置 36-600）；PNG 保留透明画布，JPEG 转为 RGB 并使用 0.9 质量；多页自动输出 ZIP。 |
| PDF -> DOCX | beta | 可编辑优先 | 恢复真实文字、基础段落、页面尺寸和方向；含中日韩文字时嵌入已许可的 Droid Sans Fallback，仍不嵌入整页图。默认严格拒绝扫描页，显式配置本地 OCR 后把扫描页识别为带坐标的可编辑文字。 |
| PDF -> OFD | experimental | 版式优先 | 生成真实 OFD 包；160 DPI 页面图像层保留视觉，文字型 PDF 同时写入源坐标 OFD 文字对象。优先使用 Poppler 并保留 PDFBox 回退；复杂对象尚未逐项结构化重建。 |
| PDF 压缩/水印 | beta | 保真优先 | 压缩支持无损、均衡和强力三级策略，先预览源 PDF，完成后再逐页预览真实压缩结果；水印支持中英文文字、不透明度、角度、颜色、位置、平铺和页码范围，并提供本地实时效果预览。修改已提交的设置后会明确要求重新生成，避免把旧结果当成新设置下载。两者均拒绝修改带数字签名的 PDF。 |
| PDF 合并/拆分 | stable | 保真优先 | 合并按上传顺序输出单个 PDF，可切换检查每个源文件并在重排后保持预览绑定；拆分按页输出编号连续的 ZIP，可逐页确认选择范围并在提交前阻止越界页码。两类操作都会重写 PDF，不保留数字签名有效性。 |
| PNG/JPG -> PDF | stable | 版式优先 | 读取 PNG pHYs、JPEG JFIF/EXIF DPI 与 EXIF 方向，透明 PNG 保留透明合成；无可信 DPI 时按 96 DPI 并警告。同格式多图按上传顺序合并为多页 PDF，网页端展示源图页序，转换完成后逐页展示真实 PDF 结果。 |
| PNG/JPG -> TXT/DOCX | experimental/按需 | OCR 提取 | 可显式配置系统 Tesseract；经许可审核的运行包未来也可内置固定 OCR 运行时。TXT 输出识别文字，DOCX 将坐标文字映射到 `DocumentModel` 后生成真实可编辑文本；两者均返回页级置信度和 OCR 警告。 |
| WPS/ET/DPS/UOF -> OOXML | experimental | 兼容优先 | 依赖 LibreOffice 对国产格式的导入能力；UOF 直接转换为可编辑 DOCX，分页和对象位置可能发生变化。 |
| DOCX -> UOF | experimental | 兼容优先 | LibreOffice 可用时调用明确的 `UOF text` 导出过滤器写入真实 UOF XML，并验证 UOF 根元素；已覆盖正文和表格文字的 LibreOffice 往返打开。 |

外部依赖说明：

- 网页结果预览：单个 PDF、PNG/JPEG、TXT/CSV 结果会在受控大小内加载真实产物；PDF 逐页渲染，文本不会执行 HTML、链接或公式。ZIP 与超限结果保持流式下载，不在页面内自动解包。

- LibreOffice：用于 DOCX/XLSX/PPTX/WPS/ET/DPS/UOF 与 PDF 相关的 Office 引擎转换；图片转 PDF 使用内置 PDFBox 路线以稳定处理 DPI 和 EXIF。
- Poppler：用于 PDF 渲染为 PNG/JPEG 和视觉回归比较。
- `FORMAT_CONVERTER_IMAGE_DPI`：PDF 图片导出的渲染分辨率，默认 `160`，允许 `36-600`；异常配置会在启动转换器时明确失败。
- `FORMAT_CONVERTER_OFFICE_REQUIRED_VERSION`：可选的 LibreOffice 版本锁定片段（如 `24.8`）；实际 `--version` 不匹配时 Office 引擎会标记为不可用，版本可在 `/api/health` 和 `/api/diagnostics` 查看。
- 源码/独立 JAR 模式可设置 `FORMAT_CONVERTER_OCR_ENABLED=true`，用 `FORMAT_CONVERTER_TESSERACT_BINARY` 指定系统 Tesseract（留空则从 `PATH` 查找）。OCR 只在图片 OCR 路线或检测出的扫描页上执行，不参与普通原生文字转换。设置 `FORMAT_CONVERTER_OCR_ENABLED=false` 可强制关闭；状态接口通过 `ocr.bundled` 标识当前是否使用经过审核的内置运行时。
- OCR 不参与固定版式渲染，也不替换 PDF/OFD 原生文字解析。混合文档逐页处理：有原生文字的页保留原对象，只有扫描页进入 OCR；页面模型不连续时返回 `OCR_PAGE_MISSING`，无文字、低于最低置信度、超时和资源终止分别返回 `OCR_NO_TEXT`、`OCR_LOW_CONFIDENCE`、`OCR_TIMEOUT`、`OCR_RESOURCE_EXHAUSTED`，不会生成不完整结果。
- 系统字体：仍会影响 Office 输出的分页、行距和文字替换。PDF/OFD -> DOCX 对中日韩文字嵌入项目已声明许可的回退字体，以保证基本字形可见；源字体的字宽与设计仍可能不同。基础 PDF 输出路线也内置中文回退字体，可通过 `FORMAT_CONVERTER_PDF_FONT` 指定 TrueType 字体。TXT -> DOCX 可通过 `FORMAT_CONVERTER_DOCX_FONT` 和 `FORMAT_CONVERTER_DOCX_CJK_FONT` 配置西文及东亚字体名；未显式配置东亚字体时同样按需嵌入内置字体。

质量标准和已提交的测试摘要见 [docs/quality-standard.md](docs/quality-standard.md) 与 [docs/test-report.md](docs/test-report.md)。完整 QA 会在本地生成被忽略的 `qa-samples/report/qa-report.md`。
OCR 是否需要安装、不同运行方式的依赖责任和启用示例见 [docs/ocr-deployment.md](docs/ocr-deployment.md)。

## 快速开始

环境要求：

- JDK 17
- Maven 3.9+
- 可选：LibreOffice 或 `soffice`
- 可选：Poppler `pdftoppm`
- 源码/独立 JAR 可选：Tesseract 5.x 与所需语言包；本地 Docker 构建会从发行版包管理器安装 OCR，公开镜像发布仍处于暂停状态

如果 `pdftoppm` 不在 `PATH` 中，可通过 `PDFTOPPM_BIN=/绝对路径/pdftoppm` 指定。OFD 图片、`PDF -> OFD` 和 `PDF -> JPEG` 路线会优先使用 Poppler，并在不可用时回退到 PDFBox；`PDF -> PNG` 为保留透明语义固定使用 PDFBox。

克隆并构建：

```bash
git clone https://github.com/wmforever/fuyue-convert.git
cd fuyue-convert
mvn clean verify
```

运行：

```bash
java -jar web-api/target/web-api-*.jar
```

访问：

```text
http://127.0.0.1:8080
```

健康检查：

```bash
curl --fail http://127.0.0.1:8080/api/health
```

前端热更新开发（后端保持在 8080）：

```bash
cd frontend
npm ci --no-audit --no-fund
npm run dev
```

## 桌面应用

`desktop/` 提供独立 Electron 外壳，界面采用深色本地工作台布局。Electron 不重写转换逻辑：生产模式会启动内置 Java Runtime 与 Spring Boot 服务，并加载同源的本地页面。

开发预览（先保持 Java 服务与 Vite 运行）：

```bash
cd desktop
npm ci
npm run dev
```

Windows x64 Lite 正式打包命令如下；普通用户请直接使用上面的 Release 安装包：

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

桌面模式使用随机回环端口和每次启动随机生成的 API Token；Token 只由 Electron 主进程注入本地任务请求，不暴露给页面脚本。用户文件与任务数据写入系统 `userData` 目录，关闭窗口时会优先触发后端优雅关闭。正式发布还会校验最终 EXE、静默安装后的真实资源、转换结果和退出后的残留进程；本地自行生成的安装器不属于官方发行版。详细说明见 [desktop/README.md](desktop/README.md)。

## 本地运行包构建

以下脚本可用于本地验证自带 Java Runtime 的包：

```bash
bash scripts/package-runtime.sh
```

生成文件位于 `dist/`：

- macOS/Linux：`fuyue-convert-<version>-<os>-<arch>.tar.gz`，解压后运行 `start.command` 或 `bin/start.sh`。
- Windows：在 Windows 本机运行 `scripts/package-runtime.ps1`。脚本不会自动复制构建机上的 Poppler；运行时可使用 PDFBox 回退或由用户显式配置系统 `pdftoppm`。

> 这些通用运行包只用于本地开发验收，不属于官方公开下载。当前官方二进制仅为上方的 Windows x64 Lite 桌面安装包；不要上传本机已有的 `dist/` 或 `desktop/release/` 产物。

启动后访问：

```text
http://127.0.0.1:8080
```

发布包默认会自动打开浏览器。Windows ZIP 额外提供 `start.vbs`，可隐藏控制台窗口启动。需要关闭自动开浏览器时可设置：

```bash
AUTO_OPEN_BROWSER=false ./bin/start.sh
```

生产部署可复制 JAR、`deploy/application.yml.example` 和管理脚本到同一目录：

```bash
./start.sh
./status.sh
./stop.sh
```

外部配置：

```bash
java -jar app.jar --spring.config.additional-location=./application.yml
```

默认每个文件会在独立 JVM Worker 中转换，主服务负责进度回传、硬超时和子进程清理。生产环境可调整：

```bash
FORMAT_CONVERTER_WORKER_ENABLED=true
FORMAT_CONVERTER_WORKER_MAX_MEMORY_MB=768
FORMAT_CONVERTER_WORKER_JAVA_BINARY=/path/to/java
FORMAT_CONVERTER_MAX_FILES_PER_TASK=100
FORMAT_CONVERTER_MAX_TASK_UPLOAD_BYTES=262144000
FORMAT_CONVERTER_MAX_TASK_OUTPUT_BYTES=536870912
FORMAT_CONVERTER_MIN_FREE_DISK_BYTES=536870912
FORMAT_CONVERTER_RESULT_TTL=24h
```

服务默认只监听 `127.0.0.1`。远程部署设置 `SERVER_ADDRESS=0.0.0.0` 时必须同时配置至少 32 个字符且不含首尾空白的 `FORMAT_CONVERTER_API_TOKEN`，否则服务拒绝启动；只有外层网络已严格隔离时才可显式设置 `FORMAT_CONVERTER_ALLOW_INSECURE_REMOTE=true`。生产环境还应使用 TLS 反向代理，并通过 `X-Format-Converter-Token` 或 Bearer Token 调用任务 API。服务会执行文件数、单文件、单任务总上传量、总输出量和数据盘安全水位检查；Worker 堆限制不替代 Docker/cgroup 或 systemd 的 CPU、总内存和进程数限制。

官方 Release 附带与最终安装内容对账的运行时组件清单、SBOM、`SHA256SUMS`、完整许可证、已知限制和测试报告；只生成依赖锁文件级 SBOM 不视为再分发审核完成。

## QA 验证

先构建可执行 JAR：

```bash
mvn -DskipTests package
```

再运行端到端 QA：

```bash
python3 qa-samples/run_qa.py
```

完整语料不随仓库发布；脚本会在启动服务前一次性列出缺失的必需样本，具体文件、可选路线和许可/脱敏要求见 [qa-samples/README.md](qa-samples/README.md)。QA 会通过 HTTP 上传样本、下载结果，再用 LibreOffice/Poppler 做路线级数据或视觉比较。

## 模块结构

- `layout-model`：与库无关的页面、文字、线、段落、表格和警告模型。
- `ofd-parser`：安全解压、`OfdParser`/`OcrEngine` SPI 和 OFDRW 适配器。
- `table-recognizer`：线段归一化、网格、合并单元格和文字分配。
- `docx-renderer`：基于 POI/OOXML 的 DOCX 页面、段落、真实表格和图片生成。
- `task-service`：转换器注册、异步状态机、批量转换、ZIP、可中断取消、失败重试、TTL 清理和重启恢复。
- `web-api`：Spring Boot REST API 和打包后的 Vue 3 前端。
- `qa-samples`：样本驱动的端到端 QA 脚本和本地测试样本。

## 贡献

欢迎贡献新的格式解析器、转换器、合成样本、字体兼容性报告和失败用例。开始前请阅读 [贡献指南](CONTRIBUTING.md)、[质量标准](docs/quality-standard.md)、[安全策略](SECURITY.md) 和 [社区行为准则](CODE_OF_CONDUCT.md)。疑似漏洞不要开公开 Issue。

## 赞助支持

> 如果您觉得项目对您有帮助，欢迎赞助支持。

[捐赠列表](docs/sponsors.md)

<p>
  <img src="docs/assets/sponsor-wechat.png" alt="微信赞助收款码" width="360">
  <img src="docs/assets/sponsor-alipay.png" alt="支付宝赞助收款码" width="360">
</p>

扫码时请核对收款方信息。

## 许可证

Fuyue Convert 自有源码使用 Apache License 2.0，详见 [LICENSE](LICENSE)。依赖、字体、外部工具和组装产物继续适用各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。在其中列出的二进制再分发阻断项关闭前，请不要把本地构建产物作为公开安装包发布。
