# FormatConverter 云端开发交接

交接日期：2026-10-03。仓库：[wmforever/fuyue-convert](https://github.com/wmforever/fuyue-convert)，交接分支：`main`。

本轮已验证功能基线为 `414fd94b3d41b6bdf1619b684bb0a6d6c70e9af5`（`feat: improve common conversions and bundle configurable OCR`）。包含本说明的提交只增加交接资料、合成样本生成器和验证摘要；使用交接提交的完整 SHA 固定工作起点，不使用旧 `v0.1.5` 标签代替。

本机已暂停新的功能开发，由云端继续。云端不需要依赖本机任务、私有样本或桌面运行目录，也不要把本地尚未验收的原型当作 `main` 已实现能力。

## 已完成且进入 main 的改进

| 范围 | 已实现行为 | 主要入口 |
| --- | --- | --- |
| 图片转 PDF | 混合 PNG/JPEG、EXIF/DPI、原始尺寸与 A4 自动/纵向/横向、0–50 mm 页边距；等比居中且不裁切 | `ImageToPdfConverter`、`ImagePdfPageSize`、前端 `imagePdfOptions.js` |
| PDF 转图片 | 指定原页码、升序去重、只渲染所选页；单页图片、多页 ZIP 保留原页码；36–600 DPI | `PdfToImageConverter`、`ConversionOptions`、`TaskController` |
| Excel 转 PDF | 选择可见工作表范围、所有列缩放为一页宽度，保留纵向分页、打印区域和跨表依赖 | `SpreadsheetPdfPreparation`、`XlsxToPdfConverter` |
| PDF 转 Word | 字体族映射和粗斜体、明显双栏阅读顺序、有线规则表格及几何变换；清晰单栏自动重排、谨慎跨页续接、CJK 字体嵌入 | `PdfLayoutParser`、`PdfFontNames`、`PdfParagraphReconstructor`、`PoiDocxRenderer` |
| OCR 识别 | 图片与 PDF/OFD 扫描区域复用本地 Tesseract；灰底/缓变阴影的低置信度重试、页级时限、可靠文字和数字保护 | `TesseractOcrConverter`、`OcrContrastEnhancer`、`PdfOcrSupport`、`OfdOcrSupport` |
| 扫描 Word | 源图和未知词间区域保留、逐词可编辑文字、规则中文词框细化、纸色与平滑阴影遮罩、深色纸面文字对比 | `OcrWordGeometryRefiner`、`FixedLayoutDocxRenderer`、`OcrBackgroundMaskSampler` |
| 桌面 OCR/Office | 后续 Lite/Full 包默认内置固定 OCR 和中英文模型；设置页支持自动/自定义/关闭、文件选择、检测、保存和安全重启 | `desktop/src/engine-settings.mjs`、`EngineSettings.vue`、`desktop/scripts/lib/ocr-runtime.mjs` |

OCR 始终是本地处理，未添加云服务账号或密钥。公开输入格式、质量等级和错误契约见 [API](api.md)、[OCR 部署](ocr-deployment.md) 和 [已知限制](known-limitations.md)。

## 已完成的验证与边界

在 macOS Intel、Java 17 和实际内置 OCR/Office 环境中，功能基线全量 Maven 测试 **364 项通过，0 失败、0 错误、0 跳过**；前端 **20 项**、桌面 **69 项**测试通过，前端生产构建和桌面脚本检查通过。此为本地验证结果，不能替代云端 CI 或 Windows/Apple Silicon 原生验收。

### Linux CI 的稀疏字迹兼容修复

交接提交 `d6a55e992e0e2bd16d6ee028573b2d5eee5525e7` 的 [Linux CI](https://github.com/wmforever/fuyue-convert/actions/runs/37097705551) 暴露了增强器的真实边界缺陷：整页 99% 分位会忽略占比不足 1% 的低对比字迹。原伪引擎测试中的单行文字受系统字体笔画密度影响，Linux 上没有触发增强，导致候选采用、空结果恢复和重试失败清理三个契约未执行；Office 和真实系统 OCR 集成任务本身通过。

修复保留普通页面的原统计方法；只有整页分位未检出字迹时，才在足量、对纸面亮度差至少 4 的非纸面像素中估计对比度。稀疏字迹不再被多数空白淹没，少量孤立脏点仍不触发恢复。页面时限、一次重试、最低置信度、至少 5 个百分点增益、文字量及可靠数字保护均未放宽。新增几何合成样本检查低于 1% 的字迹增强和源坐标/像素保留，另检查孤立噪点跳过；原有测试及断言保持不变。

云端复验命令为 `mvn -pl task-service -am -Dskip.frontend=true -Dtest=OcrContrastEnhancementTest -Dsurefire.failIfNoSpecifiedTests=false test`，然后完整运行主 CI。两项原有 bundled 条件测试仍需固定引擎环境，不将缺少原生运行时造成的跳过计为实际引擎通过；最终以对应修复 SHA 的三项 GitHub CI 均成功为交接条件。

在原生 Linux Maven/Temurin 17 容器中，修复前复现相同的 2 失败、1 错误；修复后该专项共 16 项，14 项执行通过、2 项原有 bundled 条件测试跳过，0 失败、0 错误。同一单行合成样本的暗像素在 macOS 占 1.195%，Linux 占 0.9954%，验证了系统字体笔画密度只是触发因素，实际缺陷是对稀疏内容的判断。固定引擎识别和整体构建仍需独立验收。

纸色遮罩专项另完成 100 项相关回归和 4 项携带认证的 HTTP API → 独立 JVM Worker 转换：英文阴影 PNG → TXT/DOCX、中文阴影 PNG → TXT、扫描 PDF → DOCX。输入未改变，Word 可编辑文字完整；扫描 Word 保留原图，两个 Word 经实际 LibreOffice 打开、导出为一页 PDF，渲染后文字完整。合成灰底对照样本词框区域亮白像素约减少 81.2%；该统计包含合法白字，只代表这一样本，不是通用识别准确率。

跨页段落专项验证中英文正文守恒，英文编辑后从两页自然增长到五页；标题、页眉、列表、图表、不同纸张和歧义边界仍保留逐页结构。表格/双栏专项检查文字顺序、无重复、真实单元格或可编辑定位文字，以及实际 Office 重开后的方向。

本地 QA 图像、报告、任务数据和安装包不进入 Git。相关场景的生成代码已提交，云端应重新生成证据，不依赖本地报告路径。

## 云端构建和测试

需要 JDK 17（Maven Enforcer 拒绝其他主版本）、Maven 3.9+、Node.js 22。普通 Linux 验证可安装 LibreOffice、Poppler、Tesseract 中英/竖排模型及 Noto CJK 字体；参考 `.github/workflows/ci.yml` 的 Office/OCR 集成任务。

在仓库根目录执行：

```bash
export JAVA_TOOL_OPTIONS=-Djava.awt.headless=true
npm --prefix frontend ci --no-audit --no-fund
npm --prefix desktop ci --no-audit --no-fund
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix desktop run check
npm --prefix desktop test
mvn -B -ntp -Dskip.frontend=true test
mvn -B -ntp -Dskip.frontend=true -DskipTests package
node scripts/check-local-only-files.mjs
git diff --check
```

`-Dskip.frontend=true` 依赖前一步已有的 `frontend/dist`，不可据此跳过新前端改动的构建。应用 JAR 为 `web-api/target/web-api-0.1.5.jar`；不用 Electron 也可启动本地服务验证 API。

系统 OCR 集成测试需显式启用：

```bash
export FORMAT_CONVERTER_OCR_ENABLED=true
export FORMAT_CONVERTER_OCR_LANGUAGES=chi_sim+eng
export FORMAT_CONVERTER_OCR_MAX_CONCURRENCY=1
mvn -B -ntp -pl task-service -am -Dskip.frontend=true \
  -Dtest=TesseractOcrConverterTest,TesseractMultilingualIntegrationTest,OcrEnabledRegistryIntegrationTest,PdfOcrConverterTest,OfdOcrConverterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

固定引擎验收需先在目标原生平台准备 OCR：

```bash
node desktop/scripts/prepare-ocr-runtime.mjs
```

准备产物位于 `desktop/.runtime/ocr`。将这份审核通过的目录放到一个 app-home 的 `ocr/` 子目录，设置 `FORMAT_CONVERTER_APP_HOME` 指向 app-home，再运行 `BundledOcrRuntimeIntegrationTest` 和 `OcrContrastEnhancementTest`。完整桌面暂存目录对应 `desktop/.runtime/backend/app`。系统引擎不能冒充 bundled；缺模型/字体/Office 导致条件测试跳过时应记录跳过原因并补跑，不能把跳过当作真实引擎通过。

真实验收应使用带随机认证令牌的本地后端、生产独立 Worker、实际下载产物，再用 LibreOffice/Poppler 重开和渲染。不要打印认证令牌，不依赖仅检查 OOXML 属性或直接调用转换器的结果替代生产链路。

## 可公开复现的合成样本

提交的 `desktop/test/fixtures/ocr-smoke.png` 只有合成文字“文档转换 12345”，来源和字体许可见该目录 README。其他业务文档不随仓库分发。

以下生成器仅使用固定合成文本与仓库内许可字体，生成中英文正常/正负 6° 扫描图及逐行期望文本、源图哈希和 Pillow 版本：

```bash
python3 -m pip install Pillow
python3 qa-samples/generate_ocr_handoff_samples.py
```

输出默认位于被 Git/Docker 隔离的 `qa-samples/generated/cloud-handoff/`，`expected.json` 定义六个样本的真值。保留正常图作为对照；不能只与原 OCR 输出比较，应与真值比较。引擎/字体/Pillow 版本会影响实际误差，首次运行需建立当前环境基线。

生成器本机原图识别基线已记录在 [ocr-synthetic-baseline.json](ocr-synthetic-baseline.json)，包含源 PNG 哈希、Pillow 版本、期望/识别字符数和字符编辑距离。测量使用内置 Tesseract 5.5.2 原生 CLI、PSM 3，未增强、未校正倾斜，不是 API/Word 验收。忽略空白、统一拉丁大小写后保留数字和标点，以编辑距离除以真值字符数计算字符错误率：

| 样本 | 字符错误率 | 平均置信度 |
| --- | --- | --- |
| 英文 0° | 0.00% | 96.26% |
| 英文 -6° | 27.99% | 92.94% |
| 英文 +6° | 79.52% | 93.65% |
| 中文 0° | 3.06% | 92.69% |
| 中文 -6° | 19.39% | 85.11% |
| 中文 +6° | 27.55% | 82.48% |

英文 +6° 仅识别出 61/293 个非空白字符，平均置信度仍超过 93%；这些公开可再生成的样本给云端后续校正提供了明确的内容完整性对照。中文正常图已有少量标点/字形误差，验收要同时防止正常输入退步，不能仅比较倾斜前后平均置信度。

其他可重生成场景：

| 测试 | 样本/验收用途 |
| --- | --- |
| `OcrContrastEnhancementTest` | 实际内置引擎识别灰底英文、中文与金额/标点；PNG → Word、扫描 PDF → Word；可用 `-Dformat.converter.ocr-enhancement.qa-directory=<本地目录>` 导出合成产物 |
| `OcrWordOverlayTest`、`OcrBackgroundMaskSamplerTest` | 源图字节、未知词间区域、纸色/阴影、印章/纹理采样、深色纸面、裁剪边界 |
| `PdfCrossPageParagraphTest` | 中英文跨页段落、边界拒绝、实际 Office 编辑和分页；可用 `-Dformat.converter.crosspage.qa-directory=<本地目录>` 导出 |
| `PdfToDocxTableGeometryTest`、`PdfToDocxColumnsTest`、`PdfToDocxFontTest` | 有线表格、细填充边线、坐标变换、旋转、双栏、字体族/粗斜体 |
| `PdfOcrConverterTest`、`OfdOcrConverterTest` | 纯扫描、同页混合、原生文字保留、无 OCR 时失败、图像/模型不完整时不静默丢内容 |
| `ForkedFileConverterTest`、`FormatConverterApplicationTest` | 独立 JVM、超时/失败契约、下载和产物安全、真实服务配置 |

合成样本属于软件测试输入，不代表复杂真实合同、手写体或各厂商扫描件已可靠支持。新增公开厂商样本需记录允许分发的来源和许可；不要上传真实业务合同、个人信息、印章密钥或私有报告。

## 后续优先级和验收标准

### 1. 扫描倾斜校正与内容完整性

本轮试验发现：八行约 +6° 合成英文扫描，原引擎只识别两行，平均置信度仍约 94.83%，并把 `80421` 识别成 `80424`；校正候选识别出全部八行、平均置信度约 96.48%。这说明平均置信度不是内容完整性的保证，也不是数字正确性的保证。该观察来自本机 Arial 合成样本，云端生成器使用仓库许可字体，必须重新测量，不能照抄这些数字作为结果。

原型因现有“置信度至少增益 5 个百分点”和可靠数字保护拒绝候选；中文 -6° 检测也未触发，因此**倾斜校正没有进入 main**。未验收原型只保留在本地 `wip/ocr-deskew-20261003`，提交 `b7e83089c562d9c1309c2fb96ad20c135e5317e3`，未推送，云端不能依赖该分支。

实施时至少验证：

- 中英文正负角度、正常图、空白/稀疏页、线框/印章干扰和角度超出支持范围；无可靠证据时不旋转。
- 用逐行真值、字符编辑距离/召回率、数字及标点正确率判断前后质量；补齐漏行时不得静默替换已识别的重要数字。证据冲突应明确提示复核，不简单取消现有保护。
- 校正图只作为有界临时输入，不覆盖源图、不裁掉边缘内容；Word 词框、字形方向和遮罩必须逆变换回原扫描位置，保护未识别区域。
- 图片、PDF 整页/混合区域、OFD 共用机制；竖排模型和各向异性 DPI 单独验证，不能照搬水平旋转假设。
- 总页面时限包含检测、增强和重试；限制像素、额外内存和进程数，失败/超时保留原错误契约，释放临时文件及子进程。

### 2. PDF 转 Word 的常见复杂版式

优先窄栏沟、跨栏标题和不等长多栏、无线/复杂表格、带页眉/图表文档的局部跨页续接。每次只开放有清晰证据的子场景，并验证源/Word/Office 重开后的文字守恒、阅读顺序、页数、真实可编辑结构；表格要检查行列与合并区域，不能把整页图片当作可编辑成功。编辑后验证换行、推动后段和自然分页，保留已有保守边界测试。

### 3. 成功率、速度和稳定性

从真实失败样本排序，分别统计路线成功率、明确失败原因、漏页/漏区域、耗时和峰值内存。不要通过减少 OCR 页数、忽略失败区域、删掉质量检查或延长所有超时来提高表面成功率。性能优化要保持内容/图像/页数契约，并验证大文件、混合文档、超时后无残留进程及并发不互相污染。

推送时 GitHub 提示默认分支存在 Dependabot 依赖告警；本轮未逐条确认受影响路径或处理。云端应查看仓库安全告警、核实实际影响，优先修复确实影响运行的高严重度依赖，并保留转换回归门禁，不能把当前测试通过理解为依赖没有漏洞。

### 4. 安装包与最终交付

GitHub 现有 `v0.1.5` 安装包仍是旧版，不包含当前 OCR 打包及最新源码改进。本地较早的 OCR 预览 DMG 也未包含后续跨页、阴影增强和纸色遮罩改动。版本号当前仍为 `0.1.5`，推送 main 不会自动替换旧下载包。

新包必须从经验证的提交构建，遵循 [desktop/README.md](../desktop/README.md) 和 Lite/Full 发布工作流；固定 Java、OCR、Office 的来源、架构、许可证与哈希。Windows x64、macOS Intel/Apple Silicon 需在匹配的原生环境分别验收。云端 Linux 的测试/JAR 构建不能代表三个桌面平台的安装包都已通过。

保留发布开关、标签及已审核 SHA 门禁；本次交接不创建 release、不覆盖旧标签、不发布安装包。后续交付记录完整提交 SHA、测试通过/跳过数、样本前后真值误差、Office 渲染检查、构建平台和产物 SHA-256，再由对应原生工作流验收。

## 工作方式

以本交接提交为起点，分小轮实际实施并提交。每轮先记录问题和对照样本，完成针对性回归、生产 API/Worker 转换及产物重开，再提交已验收实现；将失败原型与 main 分开保存。当前 `promo-video/` 属于本机无关未跟踪内容，未进入交接提交，云端无需处理。

后续结果包括提交 SHA、改变的行为、验证证据、仍未解决的问题和打包状态；不只给建议，不把本地报告、测试跳过或源码推送称为正式安装包发布。
