# REST API

FormatConverter 的 API 以异步任务为中心：上传一个或多个文件，选择目标格式，后台执行转换，完成后下载单文件或 ZIP。

## 错误格式

所有错误统一返回：

```json
{
  "code": "TASK_NOT_FOUND",
  "message": "任务不存在",
  "timestamp": "2026-08-04T03:00:00Z"
}
```

队列满返回 HTTP 429 / `TASK_QUEUE_FULL`；数据盘低于配置水位返回 HTTP 507 / `INSUFFICIENT_STORAGE`；上传请求或单任务配额超限不会创建任务目录。

## 访问令牌与监听地址

默认只监听 `127.0.0.1`。需要从其他主机访问时，显式设置 `SERVER_ADDRESS=0.0.0.0`，并必须同时配置至少 32 个字符且不含首尾空白的 `FORMAT_CONVERTER_API_TOKEN`，否则服务拒绝启动。只有外层网络已经严格隔离时才可显式设置 `FORMAT_CONVERTER_ALLOW_INSECURE_REMOTE=true`；生产环境仍应使用 TLS 反向代理。启用 Token 后，所有 `/api/tasks` 请求都必须携带以下任一请求头：

```http
X-Format-Converter-Token: <token>
Authorization: Bearer <token>
```

内置网页不提供令牌输入；启用令牌的部署请使用 API 请求头调用受保护接口。

## 创建任务

```http
POST /api/tasks
Content-Type: multipart/form-data
```

表单字段：

- `files`：可重复上传一个或多个源文件；
- `targetFormat`：目标格式，当前开放 `docx`、`txt`、`pdf`、`xlsx`、`csv`、`png`、`jpg`、`pdf-compress`、`pdf-watermark`、`pdf-merge`、`pdf-split`，其中 `jpeg` 会按 `jpg` 处理；未传时默认 `docx`。

当 `targetFormat=pdf` 且所有输入均为 PNG、JPG 或 JPEG 时，允许混合图片格式，按上传顺序合并为一个 PDF，并逐个验证扩展名、MIME 和文件头。任务顶层 `sourceFormat` 保留首文件类型以兼容既有客户端，各 `files[]` 结果的 `sourceFormat` 则记录对应输入的实际格式。其他路线仍要求同一批次使用相同的源格式。

PDF 工具、Excel 和图片转换可选参数：

- `compressionMode`：`lossless`、`balanced` 或 `strong`；仅用于 `pdf-compress`，默认 `lossless`；
- `watermarkText`：1-80 个字符的中英文文字，默认 `CONFIDENTIAL`；
- `watermarkOpacity`：水印不透明度，`0.05-0.85`，默认 `0.18`；
- `watermarkAngle`：`-180` 到 `180` 度，默认 `35`；
- `watermarkPosition`：`center`、`top-left`、`top-right`、`bottom-left` 或 `bottom-right`；
- `watermarkTiled`：是否在整页平铺水印；
- `watermarkPages`：`all`、`1`、`1-3` 或 `1,3-5` 形式的页码范围；
- `watermarkColor`：`#RRGGBB` 形式的颜色。
- `imageDpi`：PDF/OFD 导出 PNG/JPEG 时的任务级清晰度，整数 `36-600`；省略时使用服务默认值。DPI 越高，像素、内存和输出体积越大。
- `spreadsheetSheets`：仅用于 XLSX 转 PDF，默认 `all` 导出全部可见工作表；支持 `1`、`1-3`、`1,3-5`，按源工作簿从左到右的序号升序去重（隐藏表也占序号）。显式选中隐藏表返回 `SPREADSHEET_SHEET_HIDDEN`，越界返回 `SPREADSHEET_SHEET_RANGE_INVALID`，不静默截断。批量时分别校验每份工作簿。未选中表保留在临时副本中供跨表公式引用，不修改上传原件。
- `spreadsheetFitWidth`：仅用于 XLSX 转 PDF，默认 `false` 保留原打印设置；`true` 将所选普通工作表的所有列缩放到一页宽度，纵向页数不限，保留纸张方向、边距、打印区域与重复标题。列很多时字体会变小；图表工作表沿用原打印设置。没有 LibreOffice 时该选项返回 `OFFICE_REQUIRED_FOR_FIT_WIDTH`，基础路线支持全部可见表或指定表的文本逐表分页。
- `imagePages`：仅用于 PDF 导出 PNG/JPEG，默认 `all`；支持 `1`、`1-3`、`1,3-5`。按原页码升序去重，只渲染所选页面；越界以 `PDF_PAGE_RANGE_INVALID` 失败，不截断为部分页面。选择一页返回图片（多页源文件保留 `-page-0003` 后缀），多页返回 ZIP，条目如 `page-0003.png` 保留原页码。批量时范围分别应用于各输入 PDF。
- `imagePdfPageSize`：仅用于 PNG/JPEG 转 PDF，默认 `original` 保留 DPI 对应的原始物理尺寸；`a4-auto` 按 EXIF 修正后的物理长宽选择方向，`a4-portrait` 为 A4 纵向，`a4-landscape` 为 A4 横向。图片等比缩放、居中，不裁切或拉伸。
- `imagePdfMarginMm`：A4 图片 PDF 四边的最小留白，`0-50` mm，支持小数，默认 `10`；原始尺寸模式不应用页边距。批量图片的纸张设置一致，自动方向可逐页不同。

例如将 Excel 的第 1、3 张表导出为一页宽度 PDF，将图片整理为 A4 PDF，或只导出 PDF 第 2、4–5 页：

```bash
curl -F 'files=@workbook.xlsx' -F 'targetFormat=pdf' \
  -F 'spreadsheetSheets=1,3' -F 'spreadsheetFitWidth=true' http://127.0.0.1:8080/api/tasks
curl -F 'files=@photo.jpg' -F 'targetFormat=pdf' \
  -F 'imagePdfPageSize=a4-auto' -F 'imagePdfMarginMm=10' http://127.0.0.1:8080/api/tasks
curl -F 'files=@document.pdf' -F 'targetFormat=png' \
  -F 'imageDpi=300' -F 'imagePages=2,4-5' http://127.0.0.1:8080/api/tasks
```

成功返回 HTTP 202 和任务快照。快照中包含 `sourceFormat`、`targetFormat`、任务状态、进度、警告和文件级结果。

除上述受控参数外，API 仍由 `targetFormat` 选择路线，保真优先和可编辑优先策略由注册的转换器决定。

## 转换能力

```http
GET /api/tasks/capabilities
```

返回当前服务已注册或规划的转换路线，例如：

```json
[
  {
    "id": "ofd-to-docx",
    "sourceFormat": "ofd",
    "targetFormat": "docx",
    "sourceLabel": "OFD",
    "targetLabel": "Word DOCX",
    "inputExtension": ".ofd",
    "outputExtension": ".docx",
    "description": "将文字型 OFD 转换为可编辑 Word 文档，保留段落、表格、图片和页面方向。",
    "status": "available",
    "qualityLevel": "beta",
    "strategy": "editable",
    "requires": [],
    "limitations": ["复杂签章、扫描页和厂商私有扩展需要更多样本验证"]
  }
]
```

`status=available` 表示当前服务可执行该路线，不代表该路线已经达到 `stable`；`status=unavailable` 表示路线已配置但当前依赖检测失败，`limitations` 会给出原因。

`qualityLevel` 表示质量等级：`stable`、`beta`、`experimental`、`planned`。

`strategy` 表示默认转换策略：`editable`、`fidelity`、`data`、`extraction`、`content`、`compatibility`、`planned`。

`requires` 和 `limitations` 给出外部依赖和已知限制，调用方应在 UI 中明确展示。

`status=planned` 表示路线只展示规划，不开放执行。

## 任务资源限制

- `FORMAT_CONVERTER_MAX_FILES_PER_TASK`：单任务文件数，默认 100；
- `FORMAT_CONVERTER_MAX_TASK_UPLOAD_BYTES`：单任务上传总量，默认 250 MiB；
- `FORMAT_CONVERTER_MAX_TASK_OUTPUT_BYTES`：单任务成功输出总量，默认 512 MiB；
- `FORMAT_CONVERTER_MIN_FREE_DISK_BYTES`：转换和打包时必须保留的磁盘安全水位，默认 512 MiB。

批量结果会在打包前累计输出大小、再次检查可用磁盘，并在生成最终 ZIP/PDF 后清理单文件中间产物。

## 查询任务

```http
GET /api/tasks/{taskId}
```

外部状态：

- `WAITING`
- `CONVERTING`
- `SUCCESS`
- `FAILED`
- `CANCELLED`

`stage` 提供内部阶段，`progress` 为 0 到 100。`warnings` 是非致命限制，例如字体替代、OCR 低置信度或图像层保真兜底。`OCR_APPLIED` 与 `OCR_LOW_CONFIDENCE` 警告的 `confidence` 为 0-1 的识别范围平均置信度（页级摘要或文案注明的图片范围），其他警告可以为 `null`。采用低置信度或受限未覆盖阴影字迹探测触发的图片增强重试结果时另外返回 `OCR_IMAGE_ENHANCED` 警告，表示临时处理了灰底/缓变阴影与对比度，原图和像素坐标系未变；置信度并非逐字准确率。`PNG/JPEG -> TXT` 的可选小角度校正被采用时返回 `OCR_DESKEW_APPLIED`，词框逆变换到原图坐标；可靠原数字与校正候选冲突时保留完整原识别词（含小数点、符号、单位与分组上下文），不拼接正则片段；歧义多词合并或数字数量冲突拒绝候选。采用保留原词的校正结果时返回 `OCR_RECOGNITION_CONFLICT`，可靠文字差异也要求人工复核。这些新警告的 `confidence` 可为 `null`；不会证明内容完整或数字正确。未采用增强/校正候选且阴影覆盖证据仍未解决时，可返回 `OCR_POSSIBLE_TEXT_OMISSION`，表示可能漏识别或包含非文字图形；原文字、数字和坐标保持不变，警告不等于漏字已被证明。超过95%的平均置信度也可触发该警告，但不启动无法满足五个百分点增益的重试。Word 及 PDF/OFD 路线暂不启用此校正。PNG/JPEG 的 TXT 输出在保守双栏推断改变阅读顺序时返回 `OCR_READING_ORDER_ADJUSTED`；所有非空白字符守恒，Word/PDF/OFD 布局不因此改变。检测到多个达到宽度/字高阈值的空栏时保留引擎次序，并返回 `OCR_READING_ORDER_UNCERTAIN`，表示可能是三栏以上或表格、保留的引擎次序仍可能混行；不自动推广双栏规则。短表格、跨栏标题、过窄间隔和密集/超时页面也保留引擎次序，复杂双栏仍需人工复核。`files` 给出每个文件的成功或失败结果；成功结果中的 `pageCount` 是目标文档实际写入页数。OCR 常见稳定失败码包括 `OCR_REQUIRED`、`OCR_ENGINE_UNAVAILABLE`、`OCR_LANGUAGE_MISSING`、`OCR_PAGE_MISSING`、`OCR_NO_TEXT`、`OCR_NO_NEW_TEXT`、`OCR_VISIBILITY_UNCERTAIN`、`OCR_IMAGE_INVALID`、`OCR_IMAGE_FAILED`、`OCR_IMAGE_LIMIT_EXCEEDED`、`OCR_LOW_CONFIDENCE`、`OCR_TIMEOUT`、`OCR_CAPACITY_EXCEEDED`、`OCR_RESOURCE_EXHAUSTED` 和 `OCR_ENGINE_FAILED`。PDF/OFD 图像对象无法安全提取时分别返回 `PDF_IMAGE_EXTRACTION_FAILED`、`OFD_IMAGE_EXTRACTION_FAILED`；OFD 页面对象若使用错误的命名空间 URI，则返回 `OFD_UNSUPPORTED_NAMESPACE`；签名 PDF 被压缩、水印、合并或拆分路线拒绝时返回 `PDF_SIGNATURE_PRESENT`。服务重启恢复历史任务时若发现下载结果文件缺失，会将任务标记为 `FAILED` 并返回 `RESULT_MISSING`，避免继续展示不可用的下载状态。

OCR 的部分补行模式保持全部原识别行、分隔和词框，仅加入严格分离的候选新行；其 `OCR_IMAGE_ENHANCED` / `OCR_DESKEW_APPLIED` 文案明确说明混合输出，并同时返回 `OCR_RECOGNITION_CONFLICT` 和 `OCR_POSSIBLE_TEXT_OMISSION`。混合置信度包含原词，不保证满足整份输出五个百分点增益，不证明内容完整或数字正确；不能安全区分的重叠/跨行/多栏区域不替换。既有完整候选行为不变，路线仍为 experimental；详见 [部分恢复证据](cloud-ocr-iteration7.md)。

完整增强候选被采用也不能证明覆盖完整：对未倾斜校正的水平 PSM3 结果，剩余原页时限超过一秒时，会用最终原坐标词框和原图重查现有阴影覆盖证据，仍未覆盖时同时返回 `OCR_POSSIBLE_TEXT_OMISSION`。该文案明确说明增强候选已经采用；不增加 OCR 重试、不改变文字/数字/坐标，也不延长时限。探测可能由图形触发；时限不足、无警告或置信度提升都不能证明没有遗漏。

An accepted full enhancement can still return `OCR_POSSIBLE_TEXT_OMISSION` when the existing shaded-ink probe finds uncovered regions in final source-space word boxes. The optional horizontal PSM3 check stays within the original page deadline. This warning changes no selected text, coordinates or retry policy and is not an OCR accuracy improvement.

OFD 保留一条按识别词数加权的页级 `OCR_APPLIED` 摘要，同时保留逐图增强、遗漏、冲突及低置信度提示。逐图文案包含“第 N 页图片 M”；其 `OCR_LOW_CONFIDENCE.confidence` 是该图片的平均值，可低于页平均值。图片标记不合并为整页标记，不能据此推断其他图片也增强、部分补行或存在遗漏。

OFD retains one word-weighted page `OCR_APPLIED` summary and supplemental warnings scoped to each image. An image-scoped `OCR_LOW_CONFIDENCE.confidence` is that image's average, which can be lower than the page average. Enhancement, omission and conflict flags are not merged across images.

多个显著空栏的保留引擎次序规则现有一个受限例外：仅 TXT 中，恰好三栏、每栏至少三行、每行三至六个重复严格对齐的短片段，可按左至右栏、上至下行重组；每个原片段全文恰好使用一次，只加入空白分隔，不改写数值/原分隔，不更改词框、Word 或 PDF/OFD。表格数值格、跨栏/已合并行、窄间隔、重叠/近邻行、超时及不满足边界的输入仍保留引擎次序。采用时返回 `OCR_READING_ORDER_ADJUSTED` 和准确描述此例外的 `OCR_READING_ORDER_UNCERTAIN`；未采用时仍说明保留引擎次序。此例外不能补回原 OCR 漏字；见 [三栏片段实测](cloud-ocr-iteration8.md)。

## 下载

```http
GET /api/tasks/{taskId}/download
```

单文件任务返回目标格式文件；图片转 PDF 和 PDF 合并批次返回合并后的单个 PDF，其他批量任务通常返回 ZIP。多页图片导出和 PDF 拆分即使只有一个输入，也返回 ZIP。任务未完成时返回 HTTP 400。响应包含精确的 `Content-Type`、`Content-Length` 和附件文件名，并设置 `Cache-Control: private, no-store, max-age=0`、`Pragma: no-cache`、`X-Content-Type-Options: nosniff`，避免敏感转换结果进入浏览器缓存或被 MIME 猜测。

网页端仅自动加载不超过 32 MiB 的单个 PDF、24 MiB 的单张 PNG/JPEG，以及 2 MiB 的 TXT/CSV 结果。PDF 使用本地 PDF.js 逐页渲染，图片使用受控 Blob URL，文本只作为纯文本节点展示且最长显示 200,000 个字符；ZIP 和超限结果不会自动读入页面内存，仍可直接流式下载。

## 取消任务

```http
POST /api/tasks/{taskId}/cancel
```

等待中或转换中的任务会进入 `CANCELLED`，正在执行的转换线程会被中断，且不会发布下载结果。已结束任务保持原状态并直接返回当前快照。

## 重试任务

```http
POST /api/tasks/{taskId}/retry
```

仅允许重试 `FAILED` 或 `CANCELLED` 任务。服务使用保留的原始上传内容创建一个新的任务 ID 并返回 HTTP 202，不复用旧任务的工作目录或不完整输出。失败/取消任务的原始上传保留到 `result-ttl`，重启后仍可重试；成功任务会立即删除原始上传，过期或主动删除的任务不可重试。

## 删除任务

```http
DELETE /api/tasks/{taskId}
```

删除任务及其输入、工作文件和结果文件，成功返回 HTTP 204。删除运行中任务会先请求取消，并由转换线程退出时完成目录清理，避免一边写入一边删除的竞态。

## 健康检查

```http
GET /api/health
```

`ocr` 节点返回 `enabled`、`available`、`bundled`、`binaryName`、`version`、`requestedLanguages`、`availableLanguages`、`timeoutSeconds`、`maxConcurrency`、`maxImagePixels`、`minimumConfidence`、`errorCode` 和脱敏后的 `message`。`bundled=true` 表示应用正在使用发布包内置运行时；健康接口只报告能力，不会因为 OCR 被强制关闭而把整个服务标记为 DOWN。

后续 Lite/Full 和通用运行包默认内置中英文 OCR，完整运行时存在时自动启用；`FORMAT_CONVERTER_OCR_ENABLED=false` 可强制关闭。JPEG 等非 PNG 图片会在像素上限检查后转成临时 PNG 供引擎识别，转换失败返回 `OCR_IMAGE_NORMALIZATION_FAILED`，不修改上传原件或识别坐标。

返回服务版本、解析器、Java、操作系统、CPU 架构和 Office 引擎状态，不包含文件正文或敏感内容。

新版桌面应用通过受可信主页面限制的 IPC 配置本机 OCR/Office，不开放 HTTP 的可执行文件绑定接口。独立 JAR 可设置 `FORMAT_CONVERTER_TESSDATA_DIR` 选择独立语言包目录。

`office.available=true` 时，DOCX/XLSX/PPTX 到 PDF 以及部分 WPS/UOF 兼容路线会由本机 LibreOffice headless 执行；否则服务回退到 Java 内置基础转换或将对应路线标为规划中。

PDF→TXT 对支持的单张轴对齐扫描图检查后绘制遮罩；只有整行 OCR 词框被确定不透明遮罩完全覆盖时，才不并入这些隐藏底图文字，并返回 `OCR_OCCLUDED_TEXT_IGNORED`（`confidence` 可为 `null`）。部分遮挡、透明/不明确重叠或分析超限返回 `OCR_VISIBILITY_UNCERTAIN`，不提供成功下载。此规则不证明 OCR 完整，也不覆盖复杂多图、旋转或嵌套 Form；[范围与复现](cloud-visibility-iteration27.md)。
