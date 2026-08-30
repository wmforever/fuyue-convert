# 已知限制

## 通用限制

1. `status=available` 只表示路线可执行，不等于稳定质量承诺。
2. 外部 Office 引擎、系统字体、Poppler 版本和操作系统都会影响视觉结果。
3. 默认已使用独立 JVM Worker 实现硬超时和进程树清理；`worker-max-memory-mb` 只限制 Java 堆，CPU、总内存和外部 Office 进程的 OS 级限制需要 Docker/cgroup 或 systemd 配合。
4. 批量任务中单个文件失败不会中断其他文件，调用方需要检查每个 `TaskFileResult`。
5. Windows Release 会用 JDK `jpackage` 和 WiX 3 生成内置 Runtime 的 `.exe`/`.msi` 安装器；当前开源发布流程未配置商业代码签名证书，SmartScreen 可能显示未知发布者，生产分发方应在自身安全环境中完成签名和时间戳。

## OFD

1. 多页 OFD 已完整转换，但跨页视觉连续表格当前分别生成为多张 Word 表格，尚未自动拼成同一张跨页表格。
2. 只识别由水平/垂直矢量线构成的规则表格；无线、嵌套和异形表格尚未实现。
3. `OFD -> PDF` 已按源坐标绘制文字、图片、签章和线条；二次/三次贝塞尔曲线会折线化。复杂填充、渐变、透明度和裁剪尚未完整支持，弧线暂用端点连线近似，内部 `CM` 变换仍会告警。
4. 未启用 OCR 时，`OFD -> TXT/DOCX` 对纯扫描页以及文字极少但大幅图像覆盖的混合扫描页严格返回 `OCR_REQUIRED`，不生成不完整 TXT 或图片伪装的可编辑 DOCX。显式配置本地 Tesseract 后，会对触发扫描检测的非签章图像执行 OCR、按图像坐标合并真实文字，并保留原扫描图作为 DOCX 保真层；识别结果返回 `OCR_APPLIED`。无法解码图像或识别不到文字时返回 `OCR_NO_TEXT`。
5. 加密 OFD、私有厂商扩展和部分数字签章外观可能无法解析；系统会失败或给出警告。固定版式 PDF 使用内置字体替代源字体，字形宽度可能存在小幅差异。
6. `OFD -> XLSX` 仅导出置信度不低于 0.85 的水平/垂直矢量线规则表格，保留文本、分页工作表和矩形合并区域；无线表格、嵌套表格、公式、日期和数值类型推断尚未实现，当前统一写入字符串。未识别到可靠表格时返回 `NO_TABLE_FOUND`，扫描页返回 `OCR_REQUIRED`，不会生成空工作簿或低置信度数据伪装成功。
7. `OFD -> PNG/JPEG` 固定使用 160 DPI RGB 栅格化，并在 PNG `pHYs` / JPEG JFIF 中写入对应分辨率元数据；单页直接返回图片，多页返回按 `page-0001` 顺序命名的 ZIP。它与 `OFD -> PDF` 共享固定版式绘制限制；优先使用 Poppler，并在不可用时回退到 PDFBox。JPEG 使用 0.9 压缩质量，属于有损输出。扫描型 OFD 可以直接渲染，不需要 OCR，固定版式路线不会误报 `OCR_REQUIRED`。

## PDF

1. `PDF -> DOCX` 当前恢复真实文字、基础段落、页面尺寸和方向，但复杂阅读顺序、多栏、矢量图形、图片及复杂表格仍可能不完整。含中日韩文字时会嵌入 Droid Sans Fallback，保证基本字形跨机器可见，但会增大文件且不等同于恢复源字体设计。
2. 纯文字 PDF 不再嵌入整页图片；因此严格 QA 以字符守恒、页数、零图片和中日韩字体部件完整为准，视觉差异仅作参考。
3. 扫描型、纯图片型以及含无文字内容页的混合 PDF 在未启用 OCR 时严格返回 `OCR_REQUIRED`。显式配置本地 Tesseract 后，只对无真实文字但有可见内容的页进行 300 DPI OCR，并把 TSV 行坐标转成可编辑 `TextBlock`；文字页保持 PDFBox 提取，空白页不 OCR。OCR 页返回 `OCR_APPLIED`，有内容但识别不到文字时返回 `OCR_NO_TEXT`。
4. `PDF -> OFD` 已生成符合包结构的真实 OFD：整页 144 DPI 图像层负责版式保真，文字型页面另含源坐标 OFD 文字对象。当前表格、路径、原始图片、透明混合和表单尚未逐项重建为独立对象，因此标记为 experimental，并返回 `FIDELITY_IMAGE_LAYER`。
5. `PDF -> PNG/JPEG` 默认 160 DPI，可通过 `FORMAT_CONVERTER_IMAGE_DPI` 配置为 36-600。PNG 由 PDFBox 以 ARGB 渲染并写入 pHYs，空白区域保留透明；JPEG 输出 RGB、JFIF DPI 和 0.9 质量，CMYK 内容会转换到显示 RGB。渲染前会按 CropBox、UserUnit 和 DPI 检查像素上限；需要非空密码的 PDF 返回 `PDF_PASSWORD_REQUIRED`，当前任务 API 不接收密码。
6. `PDF -> TXT` 已按坐标重建视觉行、多栏阅读顺序和换页边界；未启用 OCR 时，纯扫描页以及混合 PDF 中没有可提取文字的内容页返回 `OCR_REQUIRED`。启用本地 OCR 后仅补齐这些扫描页。复杂旋转文字、无框表格、页眉页脚归类和带少量隐藏文字层的扫描页仍需扩充样本。
7. `PDF 压缩` 的无损模式只优化对象和内容流；均衡/强力模式会把不透明栅格图片重新编码为 JPEG，并把长边分别限制为 1800/1200 像素，透明图片保持无损编码。若输出未变小会自动返回原文件。网页端转换前只将页面标为“源文件预览”，不会模拟压缩画质；转换完成后才加载不超过 32 MiB 的真实 PDF 结果预览。数字签名会因重写失效，因此检测到签名时返回 `PDF_SIGNATURE_PRESENT`。
8. `PDF 水印` 支持中英文文字、颜色、不透明度、角度、五种位置、平铺和页码范围；网页端会在当前设备本地渲染实时效果预览，预览用于确认位置和样式，最终字体细节以导出文件为准。PDF 合并会按文件切换源预览，PDF 拆分会标出当前页是否入选并按真实页数拒绝越界范围；两者会重写 PDF，因此预览到签章外观不代表数字签名仍然有效。当前尚未支持图片水印；数字签名文件会被严格拒绝。需要密码的 PDF 返回 `PDF_PASSWORD_REQUIRED`，任务 API 不接收密码。

## Office 与国产格式

1. `DOCX/XLSX/PPTX -> PDF` 优先使用 LibreOffice headless；每次转换使用独立 profile/output 目录，输出会重新打开并校验真实页数。可通过 `FORMAT_CONVERTER_OFFICE_REQUIRED_VERSION` 锁定部署版本，但缺失字体仍会造成分页和视觉差异，生产环境应固定 LibreOffice 包及字体包镜像。LibreOffice 不可用时，DOCX/XLSX 会降级为内容优先的 Java Beta 路线：DOCX 保持正文段落/表格顺序，XLSX 仅导出第一个工作表并使用已保存的公式缓存值，两者都不保留复杂版式；PPTX 路线会明确标记为不可用。
2. `WPS/ET/DPS/UOF` 依赖 LibreOffice 对对应格式的兼容能力，当前标记为 experimental。
3. `UOF -> DOCX` 当前由 LibreOffice 直接导入并输出可编辑 DOCX；能保留的对象取决于 LibreOffice 的 UOF 兼容性，分页、字体、脚注/尾注等自动编号和对象位置可能变化。
4. WPS 官方命令行中的部分 PDF 转换能力可能需要登录或会员能力，不适合作为开源默认依赖。
5. `DOCX -> TXT` 按正文 XML 对象顺序保留段落和表格，再以标签追加页眉页脚、脚注尾注和批注；修订会以插入、删除、移入、移出标签显式导出。它不还原浮动对象的视觉锚点顺序，也不保留样式、域计算结果或批注与正文的精确锚点关系。
6. `TXT -> DOCX/PDF` 支持 UTF-8、带 BOM 的 UTF-16LE/BE 和 GB18030；无 BOM 且非 UTF-8 时会按 GB18030 严格解码并返回 `TEXT_ENCODING_GUESSED`。其他编码不会盲猜。换页符会保留为分页，但 TXT 本身不包含纸张尺寸、页边距或样式元数据。
7. `CSV -> XLSX` 自动识别逗号、TAB、分号和竖线，所有值均写为文本以避免公式注入，因此不会猜测数值、日期或公式类型。`XLSX -> CSV` 使用工作簿中保存的公式缓存值，不执行或刷新公式；过期缓存需先由表格软件重新计算并保存。多工作表输出为 ZIP，每张表一个 UTF-8 CSV。CSV 不保留样式、合并区域和原始类型元数据。
8. `PNG/JPEG -> PDF` 使用 36-1200 DPI 范围内的 PNG pHYs、JPEG JFIF 或 EXIF 分辨率；缺失或异常时固定按 96 DPI 并返回 `IMAGE_DPI_DEFAULTED`。支持 1-8 EXIF 方向和透明 PNG。同一批次目前要求全部是 PNG 或全部是 JPEG，不能混合两种扩展名；成功页面按上传顺序合并，部分失败时返回 `PARTIAL_BATCH_OUTPUT`。网页端的转换前画面只表示源图内容和页序，不承诺 PDF 物理页面尺寸；转换成功后以真实 PDF 结果预览为准。
9. `DOCX -> UOF` 仅在 LibreOffice 提供 `UOF text` 导出过滤器时开放，输出是具有 `uof:UOF` 根元素和 UOF 命名空间的真实 XML，不是改扩展名。复杂绘图、嵌入对象、修订、域和字体仍可能在 LibreOffice 兼容转换中变化，因此保持 experimental。当前 LibreOffice 没有经本项目验证的 WPS/ET/DPS 写出过滤器，`DOCX -> WPS`、`XLSX -> ET`、`PPTX -> DPS` 继续保持 planned，禁止伪装支持。
10. `PNG/JPEG -> TXT/DOCX` 仅在 OCR 能力可用时开放：官方运行包自动使用内置 Tesseract，源码/独立 JAR 使用系统 Tesseract 时需显式启用。TXT 输出纯文本；DOCX 将 OCR 坐标映射回 `DocumentModel` 并复用布局分析和 Word 渲染，生成真实文本框架而非整页图片。任务警告提供页级平均置信度；低于复核阈值返回 `OCR_LOW_CONFIDENCE` 警告，低于最低阈值则以同名错误码失败。印刷体中英文、数字、标点和 EXIF 旋转已纳入自动化测试。竖排需配置对应 `*_vert` 语言包并自动使用竖排分割模式；当前跨版本金样门禁为字符召回率至少 50%，不代表逐字可靠。手写体、复杂表格、倾斜和噪声图片仍可能误识别，因此保持 experimental。

## QA 样本

1. 当前公开样本数量仍少，不能代表所有行业文档。
2. 本地 QA 按路线目标执行严格检查；跨引擎二次渲染差异由 `visualPass` 单独记录，不能用内容一致或内嵌页面一致代替视觉一致声明。
3. 标记 stable 前必须扩大样本集，并记录样本来源和许可证。
