# Fuyue Convert

[简体中文](README.md) | [English](README_EN.md)

Fuyue Convert 是一个开源文档格式转换平台，目标是用可审计、可替换的开源组件完成常见办公文档、国产格式和版式文档之间的转换。

项目基于 Java 17、Spring Boot、Vue 3、Apache POI、PDFBox、Poppler、OFDRW 和 LibreOffice headless。它不会承诺所有格式都能做到“完全一致且完全可编辑”，而是把转换能力拆成明确的路线、质量等级和失败原因，让结果可以被自动验证，也方便社区逐步增强。

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
| OFD -> DOCX/TXT/PDF/PNG/JPG | beta | 结构/版式 | DOCX/TXT 使用结构化解析；未配置 OCR 时扫描页严格失败，配置本地 Tesseract 后对扫描图像补充坐标文字。PDF/PNG/JPEG 按源坐标绘制文字、图片、签章和路径；图片固定为 160 DPI，多页输出 ZIP。 |
| OFD -> XLSX | experimental | 数据优先 | 将高置信度有线规则表格写成真实单元格、分页工作表和合并区域；未识别到可靠表格返回 `NO_TABLE_FOUND`，扫描页返回 `OCR_REQUIRED`。 |
| CSV <-> XLSX | stable | 数据优先 | CSV 支持 UTF-8/UTF-16 BOM/GB18030 与逗号、TAB、分号、竖线识别；输入统一写成文本以阻断公式注入。XLSX 公式导出缓存结果，日期按单元格格式输出，多工作表分别导出 CSV ZIP。 |
| DOCX/XLSX/PPTX -> PDF | beta | 版式优先 | 有 LibreOffice 时使用隔离的 headless profile 转换，校验真实 PDF 页数；本地字体会影响结果。 |
| TXT -> DOCX/PDF | stable | 内容优先 | 支持 UTF-8、带 BOM 的 UTF-16 和严格 GB18030 解码；换页符生成真实分页，PDF 按字形宽度进行 CJK 换行，DOCX 写入可配置中西文字体。 |
| DOCX -> TXT | beta | 内容提取 | 按正文对象顺序提取段落和表格，并带标签追加页眉页脚、文本框、脚注尾注、批注及修订文字；不保留版式。 |
| PDF -> TXT | beta | 内容提取 | 按页面坐标和多栏顺序提取文字并保留换页；默认对扫描页返回 `OCR_REQUIRED`，显式配置本地 OCR 后仅识别缺少文字的内容页。 |
| PDF -> PNG/JPG | stable | 版式渲染 | 默认 160 DPI（可配置 36-600）；PNG 保留透明画布，JPEG 转为 RGB 并使用 0.9 质量；多页自动输出 ZIP。 |
| PDF -> DOCX | beta | 可编辑优先 | 恢复真实文字、基础段落、页面尺寸和方向；默认严格拒绝扫描页，显式配置本地 OCR 后把扫描页识别为带坐标的可编辑文字且不嵌入整页图。 |
| PDF -> OFD | experimental | 版式优先 | 生成真实 OFD 包；144 DPI 页面图像层保留视觉，文字型 PDF 同时写入源坐标 OFD 文字对象。复杂对象尚未逐项结构化重建。 |
| PNG/JPG -> PDF | stable | 版式优先 | 读取 PNG pHYs、JPEG JFIF/EXIF DPI 与 EXIF 方向，透明 PNG 保留透明合成；无可信 DPI 时按 96 DPI 并警告。同格式多图按上传顺序合并为多页 PDF。 |
| PNG/JPG -> TXT | experimental/按配置 | OCR 提取 | 默认禁用；只有显式启用且本地 Tesseract 与所需语言包可用时开放，结果返回 `OCR_APPLIED` 并要求人工复核。 |
| WPS/ET/DPS/UOF -> OOXML | experimental | 兼容优先 | 依赖 LibreOffice 对国产格式的导入能力；UOF 直接转换为可编辑 DOCX，分页和对象位置可能发生变化。 |
| DOCX -> UOF | experimental | 兼容优先 | LibreOffice 可用时调用明确的 `UOF text` 导出过滤器写入真实 UOF XML，并验证 UOF 根元素；已覆盖正文和表格文字的 LibreOffice 往返打开。 |

外部依赖说明：

- LibreOffice：用于 DOCX/XLSX/PPTX/WPS/ET/DPS/UOF 与 PDF 相关的 Office 引擎转换；图片转 PDF 使用内置 PDFBox 路线以稳定处理 DPI 和 EXIF。
- Poppler：用于 PDF 渲染为 PNG/JPEG 和视觉回归比较。
- `FORMAT_CONVERTER_IMAGE_DPI`：PDF 图片导出的渲染分辨率，默认 `160`，允许 `36-600`；异常配置会在启动转换器时明确失败。
- `FORMAT_CONVERTER_OFFICE_REQUIRED_VERSION`：可选的 LibreOffice 版本锁定片段（如 `24.8`）；实际 `--version` 不匹配时 Office 引擎会标记为不可用，版本可在 `/api/health` 和 `/api/diagnostics` 查看。
- 本地 OCR 默认关闭。设置 `FORMAT_CONVERTER_OCR_ENABLED=true` 后，使用 `FORMAT_CONVERTER_TESSERACT_BINARY` 指定 Tesseract（留空则从 `PATH` 查找），并以 `FORMAT_CONVERTER_OCR_LANGUAGES` 配置语言，默认 `chi_sim+eng`。缺少引擎或语言包时图片 OCR 路线保持 planned，PDF/OFD 继续严格失败；启用、版本和语言状态可从 `/api/health` 或 `/api/diagnostics` 查看，接口不会暴露绝对路径。
- 系统字体：影响 Office 输出的分页、行距和文字替换；基础 PDF 文本路线内置中文回退字体，也可通过 `FORMAT_CONVERTER_PDF_FONT` 指定 TrueType 字体。TXT -> DOCX 可通过 `FORMAT_CONVERTER_DOCX_FONT` 和 `FORMAT_CONVERTER_DOCX_CJK_FONT` 配置西文及东亚字体名。

质量标准见 [docs/quality-standard.md](docs/quality-standard.md)。最新本地 QA 报告见 `qa-samples/report/qa-report.md`。

## 快速开始

环境要求：

- JDK 17
- Maven 3.9+
- 可选：LibreOffice 或 `soffice`
- 可选：Poppler `pdftoppm`

构建：

```bash
mvn clean verify
```

运行：

```bash
java -jar web-api/target/web-api-0.1.1.jar
```

访问：

```text
http://127.0.0.1:8080
```

## 免 Java 发布包

面向普通用户可以使用自带 Java Runtime 的发布包，解压后不需要单独安装 JDK/JRE：

```bash
bash scripts/package-runtime.sh
```

生成文件位于 `dist/`：

- macOS/Linux：`fuyue-convert-<version>-<os>-<arch>.tar.gz`，解压后运行 `start.command` 或 `bin/start.sh`。
- Windows：通过 GitHub Actions 或 Windows 本机运行 `scripts/package-runtime.ps1`。普通包解压后双击 `start.bat`；`*-exe.zip` 解压后双击 `FuyueConvert.exe`。

GitHub Release 会在打包后自动执行 smoke test：三个平台都会使用内置 Runtime 启动服务、检查 `/api/health`，再完成一次真实 `TXT -> DOCX` Worker 转换和下载内容校验；Windows 另外检查 `FuyueConvert.exe`。

启动后访问：

```text
http://127.0.0.1:8080
```

发布包默认会自动打开浏览器。需要关闭时可设置：

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
```

Worker 内存限制仅限 JVM 堆；Docker/cgroup 或 systemd 的 CPU、总内存和进程数限制仍应在部署层设置。

## QA 验证

先构建可执行 JAR：

```bash
mvn -DskipTests package
```

再运行端到端 QA：

```bash
python3 qa-samples/run_qa.py
```

QA 会启动本地服务，通过 HTTP 上传样本、下载转换结果，再用 LibreOffice/Poppler 渲染并比较。`strictPass` 按路线目标判定：直接保真路线要求渲染像素一致，可编辑文档要求规范化内容一致，表格路线要求数据一致；PDF -> DOCX 还要求纯文字多页样本页数一致、零内嵌图片，并要求纯图片 PDF 严格失败为 `OCR_REQUIRED`。跨引擎二次渲染差异另记为 `visualPass`，不会被严格内容检查掩盖。

## 模块结构

- `layout-model`：与库无关的页面、文字、线、段落、表格和警告模型。
- `ofd-parser`：安全解压、`OfdParser`/`OcrEngine` SPI 和 OFDRW 适配器。
- `table-recognizer`：线段归一化、网格、合并单元格和文字分配。
- `docx-renderer`：基于 POI/OOXML 的 DOCX 页面、段落、真实表格和图片生成。
- `task-service`：转换器注册、异步状态机、批量转换、ZIP、清理和重启恢复。
- `web-api`：Spring Boot REST API 和打包后的 Vue 3 前端。
- `qa-samples`：样本驱动的端到端 QA 脚本和本地测试样本。

## 贡献

欢迎贡献新的格式解析器、转换器、样本、字体兼容性报告和失败用例。新增路线前请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [docs/quality-standard.md](docs/quality-standard.md)。

## 赞助支持

> 如果您觉得项目对您有帮助，欢迎赞助支持。

[捐赠列表](docs/sponsors.md)

<p>
  <img src="docs/assets/sponsor-wechat.png" alt="微信赞助收款码" width="360">
  <img src="docs/assets/sponsor-alipay.png" alt="支付宝赞助收款码" width="360">
</p>

扫码时请核对收款方信息。

## 许可证

本项目使用 Apache License 2.0，详见 [LICENSE](LICENSE)。随应用分发的第三方组件和字体许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
