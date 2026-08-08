# 已知限制

## 通用限制

1. `status=available` 只表示路线可执行，不等于稳定质量承诺。
2. 外部 Office 引擎、系统字体、Poppler 版本和操作系统都会影响视觉结果。
3. 默认已使用独立 JVM Worker 实现硬超时和进程树清理；`worker-max-memory-mb` 只限制 Java 堆，CPU、总内存和外部 Office 进程的 OS 级限制需要 Docker/cgroup 或 systemd 配合。
4. 批量任务中单个文件失败不会中断其他文件，调用方需要检查每个 `TaskFileResult`。

## OFD

1. 多页 OFD 已完整转换，但跨页视觉连续表格当前分别生成为多张 Word 表格，尚未自动拼成同一张跨页表格。
2. 只识别由水平/垂直矢量线构成的规则表格；无线、嵌套和异形表格尚未实现。
3. Path 中的曲线、圆弧和内部 `CM` 变换不参与表格边线识别。
4. 扫描型 OFD 尚未接入本地 OCR；当前只返回 `OCR_REQUIRED` 警告，不伪造文字。
5. 加密 OFD、私有厂商扩展和部分数字签章外观可能无法解析；系统会失败或给出警告。

## PDF

1. `PDF -> DOCX` 当前是保真优先页面图层路线，不是完整结构化编辑路线。
2. 页面图层 DOCX 在再次通过 LibreOffice 渲染为 PDF 时仍可能产生少量像素差异。
3. 文本型 PDF 可提取 TXT；扫描型 PDF 需要后续接入 OCR。

## Office 与国产格式

1. `DOCX/XLSX/PPTX -> PDF` 依赖 LibreOffice headless；缺失字体会造成视觉差异。
2. `WPS/ET/DPS/UOF` 依赖 LibreOffice 对对应格式的兼容能力，当前标记为 experimental。
3. `UOF -> DOCX` 当前使用 `UOF -> PDF -> 图像层 DOCX` 兜底，避免分页漂移，但正文结构编辑能力有限。
4. WPS 官方命令行中的部分 PDF 转换能力可能需要登录或会员能力，不适合作为开源默认依赖。

## QA 样本

1. 当前公开样本数量仍少，不能代表所有行业文档。
2. 本地 QA 按路线目标执行严格检查；跨引擎二次渲染差异由 `visualPass` 单独记录，不能用内容一致或内嵌页面一致代替视觉一致声明。
3. 标记 stable 前必须扩大样本集，并记录样本来源和许可证。
