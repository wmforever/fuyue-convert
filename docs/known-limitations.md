# 已知限制

## 通用限制

1. `status=available` 只表示路线可执行，不等于稳定质量承诺。
2. 外部 Office 引擎、系统字体、Poppler 版本和操作系统都会影响视觉结果。
3. 默认已使用独立 JVM Worker 实现硬超时和进程树清理；`worker-max-memory-mb` 只限制 Java 堆，CPU、总内存和外部 Office 进程的 OS 级限制需要 Docker/cgroup 或 systemd 配合。
4. 批量任务中单个文件失败不会中断其他文件，调用方需要检查每个 `TaskFileResult`。

## OFD

1. 多页 OFD 已完整转换，但跨页视觉连续表格当前分别生成为多张 Word 表格，尚未自动拼成同一张跨页表格。
2. 只识别由水平/垂直矢量线构成的规则表格；无线、嵌套和异形表格尚未实现。
3. `OFD -> PDF` 已按源坐标绘制文字、图片、签章和线条；二次/三次贝塞尔曲线会折线化。复杂填充、渐变、透明度和裁剪尚未完整支持，弧线暂用端点连线近似，内部 `CM` 变换仍会告警。
4. 扫描型 OFD 尚未接入本地 OCR；`OFD -> TXT` 对纯扫描页以及文字极少但大幅图像覆盖的混合扫描页严格返回 `OCR_REQUIRED`，不生成不完整 TXT。`OFD -> PDF` 仍可保留原始图像。
5. 加密 OFD、私有厂商扩展和部分数字签章外观可能无法解析；系统会失败或给出警告。固定版式 PDF 使用内置字体替代源字体，字形宽度可能存在小幅差异。
6. `OFD -> XLSX` 仅导出置信度不低于 0.85 的水平/垂直矢量线规则表格，保留文本、分页工作表和矩形合并区域；无线表格、嵌套表格、公式、日期和数值类型推断尚未实现，当前统一写入字符串。未识别到可靠表格时返回 `NO_TABLE_FOUND`，扫描页返回 `OCR_REQUIRED`，不会生成空工作簿或低置信度数据伪装成功。
7. `OFD -> PNG/JPEG` 固定使用 160 DPI RGB 栅格化，单页直接返回图片，多页返回按 `page-0001` 顺序命名的 ZIP。它与 `OFD -> PDF` 共享固定版式绘制限制；JPEG 使用 0.9 压缩质量，属于有损输出。扫描型 OFD 可以直接渲染，不需要 OCR。

## PDF

1. `PDF -> DOCX` 当前恢复真实文字、基础段落、页面尺寸和方向，但复杂阅读顺序、多栏、矢量图形、图片及复杂表格仍可能不完整。
2. 纯文字 PDF 不再嵌入整页图片；因此严格 QA 以字符守恒、页数和零图片为准，视觉差异仅作参考。
3. 扫描型、纯图片型以及含无文字内容页的混合 PDF 在未启用 OCR 时严格返回 `OCR_REQUIRED`。显式配置本地 Tesseract 后，只对无真实文字但有可见内容的页进行 300 DPI OCR，并把 TSV 行坐标转成可编辑 `TextBlock`；文字页保持 PDFBox 提取，空白页不 OCR。OCR 页返回 `OCR_APPLIED`，有内容但识别不到文字时返回 `OCR_NO_TEXT`。
4. `PDF -> OFD` 已生成符合包结构的真实 OFD：整页 144 DPI 图像层负责版式保真，文字型页面另含源坐标 OFD 文字对象。当前表格、路径、原始图片、透明混合和表单尚未逐项重建为独立对象，因此标记为 experimental，并返回 `FIDELITY_IMAGE_LAYER`。
5. `PDF -> PNG/JPEG` 默认 160 DPI，可通过 `FORMAT_CONVERTER_IMAGE_DPI` 配置为 36-600。PNG 由 PDFBox 以 ARGB 渲染并写入 pHYs，空白区域保留透明；JPEG 输出 RGB、JFIF DPI 和 0.9 质量，CMYK 内容会转换到显示 RGB。渲染前会按 CropBox、UserUnit 和 DPI 检查像素上限；需要非空密码的 PDF 返回 `PDF_PASSWORD_REQUIRED`，当前任务 API 不接收密码。
6. `PDF -> TXT` 已按坐标重建视觉行、多栏阅读顺序和换页边界；未启用 OCR 时，纯扫描页以及混合 PDF 中没有可提取文字的内容页返回 `OCR_REQUIRED`。启用本地 OCR 后仅补齐这些扫描页。复杂旋转文字、无框表格、页眉页脚归类和带少量隐藏文字层的扫描页仍需扩充样本。

## Office 与国产格式

1. `DOCX/XLSX/PPTX -> PDF` 依赖 LibreOffice headless；每次转换使用独立 profile/output 目录，输出会重新打开并校验真实页数。可通过 `FORMAT_CONVERTER_OFFICE_REQUIRED_VERSION` 锁定部署版本，但缺失字体仍会造成分页和视觉差异，生产环境应固定 LibreOffice 包及字体包镜像。
2. `WPS/ET/DPS/UOF` 依赖 LibreOffice 对对应格式的兼容能力，当前标记为 experimental。
3. `UOF -> DOCX` 当前由 LibreOffice 直接导入并输出可编辑 DOCX；能保留的对象取决于 LibreOffice 的 UOF 兼容性，分页、字体、脚注/尾注等自动编号和对象位置可能变化。
4. WPS 官方命令行中的部分 PDF 转换能力可能需要登录或会员能力，不适合作为开源默认依赖。
5. `DOCX -> TXT` 按正文 XML 对象顺序保留段落和表格，再以标签追加页眉页脚、脚注尾注和批注；修订会以插入、删除、移入、移出标签显式导出。它不还原浮动对象的视觉锚点顺序，也不保留样式、域计算结果或批注与正文的精确锚点关系。
6. `TXT -> DOCX/PDF` 支持 UTF-8、带 BOM 的 UTF-16LE/BE 和 GB18030；无 BOM 且非 UTF-8 时会按 GB18030 严格解码并返回 `TEXT_ENCODING_GUESSED`。其他编码不会盲猜。换页符会保留为分页，但 TXT 本身不包含纸张尺寸、页边距或样式元数据。
7. `CSV -> XLSX` 自动识别逗号、TAB、分号和竖线，所有值均写为文本以避免公式注入，因此不会猜测数值、日期或公式类型。`XLSX -> CSV` 使用工作簿中保存的公式缓存值，不执行或刷新公式；过期缓存需先由表格软件重新计算并保存。多工作表输出为 ZIP，每张表一个 UTF-8 CSV。CSV 不保留样式、合并区域和原始类型元数据。
8. `PNG/JPEG -> PDF` 使用 36-1200 DPI 范围内的 PNG pHYs、JPEG JFIF 或 EXIF 分辨率；缺失或异常时固定按 96 DPI 并返回 `IMAGE_DPI_DEFAULTED`。支持 1-8 EXIF 方向和透明 PNG。同一批次目前要求全部是 PNG 或全部是 JPEG，不能混合两种扩展名；成功页面按上传顺序合并，部分失败时返回 `PARTIAL_BATCH_OUTPUT`。
9. `DOCX -> UOF` 仅在 LibreOffice 提供 `UOF text` 导出过滤器时开放，输出是具有 `uof:UOF` 根元素和 UOF 命名空间的真实 XML，不是改扩展名。复杂绘图、嵌入对象、修订、域和字体仍可能在 LibreOffice 兼容转换中变化，因此保持 experimental。当前 LibreOffice 没有经本项目验证的 WPS/ET/DPS 写出过滤器，`DOCX -> WPS`、`XLSX -> ET`、`PPTX -> DPS` 继续保持 planned，禁止伪装支持。
10. `PNG/JPEG -> TXT` 仅在显式启用本地 Tesseract 且全部语言包可用时开放。当前输出是纯文本，不包含置信度、坐标或版面结构；手写体、复杂表格、竖排、低分辨率、倾斜和噪声图像可能误识别，因此固定为 experimental 并返回 `OCR_APPLIED`。

## QA 样本

1. 当前公开样本数量仍少，不能代表所有行业文档。
2. 本地 QA 按路线目标执行严格检查；跨引擎二次渲染差异由 `visualPass` 单独记录，不能用内容一致或内嵌页面一致代替视觉一致声明。
3. 标记 stable 前必须扩大样本集，并记录样本来源和许可证。
