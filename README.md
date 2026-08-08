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
| OFD -> DOCX/TXT/PDF | beta | 可编辑优先 | 文字型 OFD 可解析文字、表格和图片；签章外观不兼容时保留正文并明确告警，扫描型 OFD 会提示需要 OCR。 |
| CSV <-> XLSX | stable | 数据优先 | 已覆盖 CSV 到 XLSX，再回到 CSV 的严格数据回环。 |
| DOCX/XLSX/PPTX -> PDF | beta | 版式优先 | 有 LibreOffice 时使用 headless 转换；本地字体会影响结果。 |
| TXT -> DOCX/PDF | stable | 内容优先 | 适合纯文本生成基础办公文档。 |
| DOCX -> TXT | stable | 内容提取 | 提取正文文本，不保留版式。 |
| PDF -> TXT/PNG/JPG | stable | 提取/渲染 | PDF 到 PNG/JPEG 使用 Poppler 或 PDFBox 按页渲染；多页自动输出 ZIP。 |
| PDF -> DOCX | experimental | 保真优先 | 当前生成页面图层 DOCX，版式比纯文本更稳，但结构编辑能力有限。 |
| PNG/JPG -> PDF | beta | 版式优先 | 有 LibreOffice 时使用 Office 引擎；无 Office 时回退 PDFBox。 |
| WPS/ET/DPS/UOF | experimental | 兼容优先 | 依赖 LibreOffice 对国产格式的导入能力；UOF 当前用 PDF 图层兜底避免分页漂移。 |

外部依赖说明：

- LibreOffice：用于 DOCX/XLSX/PPTX/WPS/ET/DPS/UOF 与 PDF 相关的 Office 引擎转换。
- Poppler：用于 PDF 渲染为 PNG/JPEG 和视觉回归比较。
- 系统字体：影响 Office/PDF 输出的分页、行距和文字替换。

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

QA 会启动本地服务，通过 HTTP 上传样本、下载转换结果，再用 LibreOffice/Poppler 渲染并比较。`strictPass` 按路线目标判定：直接保真路线要求渲染像素一致，页面图层 DOCX 要求内嵌页面像素一致，可编辑文档要求规范化内容一致，表格路线要求数据一致。跨引擎二次渲染差异另记为 `visualPass`，不会被严格内容检查掩盖。

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

本项目使用 Apache License 2.0，详见 [LICENSE](LICENSE)。
