# QA Samples

本目录用于端到端转换 QA。完整测试语料不随仓库发布；fresh clone 中的 `input/` 默认只有占位文件。项目不为你本地放入的样本声明来源或许可。

## 目录约定

- `input/`：本地测试样本，请按下方精确文件名自行放置。
- `run_qa.py`：启动本地服务、上传样本、下载结果并做视觉/数据对比。
- `output/`：本地转换结果，已在 `.gitignore` 中忽略。
- `work/`：本地渲染和中间文件，已忽略。
- `runtime-data/`：测试服务运行数据，已忽略。
- `report/`：本地 QA 报告和差异图，已忽略。

## 样本预检

脚本在启动服务、创建或清理 `output/`、`work/`、`report/` 之前，一次性检查全部必需样本。缺少任意必需样本时，会完整列出缺失项并以退出码 `3` 结束；不会启动服务，也不会改动本次 QA 输出目录。

### 必需样本

| 精确文件名 | 用途 |
| --- | --- |
| `dummy.pdf` | PDF -> PNG/JPEG 与 PDF 工具基线 |
| `demo.docx` | DOCX -> PDF 视觉保真 |
| `frictionless-sample.xlsx` | XLSX -> PDF 视觉保真 |
| `microsoft-workshop.pptx` | PPTX -> PDF 视觉保真 |
| `w3c-home.png` | 图片 -> PDF 页面布局与 OCR 契约 |
| `countries.csv` | CSV <-> XLSX 数据往返 |

### 可选样本（存在时才执行）

这些文件缺失不会阻止脚本启动；对应兼容性 case 会跳过。

| 精确文件名 | 存在时执行的 case |
| --- | --- |
| `quanzhou-drug-retail.wps` | WPS -> DOCX |
| `wps-template-newchart.et` | ET -> XLSX |
| `wps-template-newfile.dps` | DPS -> PPTX |
| `libreoffice-generated-demo.uof` | UOF -> DOCX |
| `ofdrw-invoice.ofd` | OFD -> TXT/DOCX/PDF/PNG/JPEG/XLSX |

把文件直接放到 `qa-samples/input/`，并保持上表中的精确文件名。脚本不会下载样本，也不假定任何第三方样本具有可再分发许可。请只使用来源清楚、具备公开许可或你有权使用并已充分脱敏的文件；不要把包含个人隐私、公司秘密、真实印章密钥或未脱敏合同的语料用于 QA。

`beta-capability-coverage` 仍要求本次实际执行的 case 覆盖服务声明为 available 的全部 Beta 路线。因此，`ofdrw-invoice.ofd` 虽不是启动前的必需项，但当 OFD Beta 路线可用时，缺少该文件会让相关 case 跳过，并使最终 strict QA 因 Beta 覆盖不足而失败。要取得完整 Beta 路线证据，需要提供对应可选样本。

`ofdrw-invoice.ofd` 是维护者本地金样的固定文件名，不代表任意 OFD 发票都能替换。它的 XLSX 断言要求：1 个工作表、18 个单元格、8 个合并区域，并保留“购买方”“项目名称”“价税合计（大写）”“销售方”四段文字。该金样目前没有可公开再分发的版本，因此 fresh clone 可以运行单元测试和不依赖它的路线，但无法复现“全部 available Beta 已覆盖”的最终维护者门禁。长期方案是用具备明确许可的确定性合成 OFD 替换它。

## 运行方式

先准备样本，再运行：

```bash
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

QA 脚本需要本机具备：

- Java 17
- Maven 3.9+
- LibreOffice 或 `soffice`
- Poppler `pdftoppm` 与 `pdfinfo`
- `curl`
- Python 3 与 Pillow（例如 `python3 -m pip install Pillow`）

`strictPass` 使用路线级判定：直接保真路线比较渲染像素，可编辑文档要求规范化内容一致；PDF -> TXT 检查字符守恒，纯扫描 PDF 必须 `OCR_REQUIRED`，多栏、混合扫描和空白页契约由生成样本覆盖；PDF -> DOCX 还要求生成的纯文字多页样本页数一致且不含任何 `word/media/` 图片，含中日韩文字时必须包含字体表和 OOXML 混淆字体部件，并要求纯图片 PDF 严格失败为 `OCR_REQUIRED`；PDF -> OFD 检查真实包转换后的字符和页数守恒，并经 OFD -> PDF 回环记录视觉差异；OFD -> TXT 通过单元集成样本检查多栏、表格和纯扫描/混合扫描失败契约，OFD -> DOCX 检查 TXT 与 DOCX 的字符守恒，OFD -> PDF 检查字符和声明/渲染页数守恒；OFD -> PNG/JPEG 检查页数、顺序、160 DPI 像素尺寸、非空内容和扫描页渲染；OFD -> XLSX 检查精确单元格数、合并区域数和已知文字，同时排除低置信度候选，生成样本另行覆盖分页工作表、`NO_TABLE_FOUND` 和 `OCR_REQUIRED`；表格比较数据，JPEG 使用有损误差上限。`visualPass` 单独表示当前 LibreOffice/Poppler 环境中的二次渲染差异是否低于参考阈值。

服务启动前会把可执行 JAR 复制并校验到 `runtime-data/`。因此并行 Maven 构建即使替换 `web-api/target` 下的 JAR，也不会破坏正在运行的 QA 服务。QA 脚本自身使用跨进程锁；同一 checkout 已有 QA 运行时，后启动的进程会以退出码 `2` 拒绝运行。

## 样本提交要求

如需提议把样本纳入仓库，必须先单独审查其来源、许可和内容。至少满足：

- 来源清楚，允许公开分发；
- 不包含个人隐私、公司秘密、真实印章密钥或未脱敏合同；
- 文件尽量小；
- 能代表一个明确场景或失败用例。

不要提交本地输出、差异图和运行数据。

---

## English

The complete QA corpus is not distributed with the repository. A fresh clone
contains only `input/.gitkeep`; the project makes no provenance or licensing
claim for files placed there by a contributor.

Before starting a service or touching QA output, `run_qa.py` checks these
required exact names and exits with code `3` while listing every missing file:

- `dummy.pdf` — PDF rendering and PDF-tool baseline
- `demo.docx` — DOCX to PDF fidelity
- `frictionless-sample.xlsx` — XLSX to PDF fidelity
- `microsoft-workshop.pptx` — PPTX to PDF fidelity
- `w3c-home.png` — image-to-PDF layout and OCR contracts
- `countries.csv` — CSV/XLSX round trip

The following are optional and run only when present: `quanzhou-drug-retail.wps`,
`wps-template-newchart.et`, `wps-template-newfile.dps`,
`libreoffice-generated-demo.uof`, and `ofdrw-invoice.ofd`. Missing optional
fixtures may still make the final Beta capability coverage case fail when an
available Beta route depends on them.

The maintainer-only `ofdrw-invoice.ofd` golden fixture has a fixed contract:
OFD-to-XLSX must produce one worksheet, 18 cells, eight merged regions, and the
four Chinese fragments documented above. An arbitrary OFD file is not a valid
replacement. No redistributable version is currently available, so a fresh
clone cannot reproduce the final all-Beta maintainer gate yet.

Place local fixtures under `qa-samples/input/` using the exact names. Use only
files with known provenance that you are authorized to use and that have been
fully privacy-reviewed. Do not upload personal data, confidential documents,
real seal material, or merely “redacted” files for which redistribution rights
are missing.

Requirements: Java 17, Maven 3.9+, LibreOffice/`soffice`, Poppler `pdftoppm` and `pdfinfo`,
`curl`, Python 3, and Pillow (`python3 -m pip install Pillow`). Then run from the
repository root:

```bash
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

Generated `output/`, `work/`, `runtime-data/`, `report/`, and diff images are
local artifacts and must not be committed.
