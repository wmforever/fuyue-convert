# 开源路线图

FormatConverter 的路线图以“开源可验证”为原则。短期目标不是一次性覆盖所有格式，而是让每条转换路线都有清晰的质量等级、样本证据和可替换实现。

## 已完成

- 项目包名和 Maven 坐标迁移到 `com.fuyue:format-converter`。
- 后端抽象 `DocumentFormat`、`ConversionRoute` 和 `FileConverter`。
- 前端通过 `/api/tasks/capabilities` 动态展示转换路线。
- 增加 Office 引擎健康检查，自动发现 LibreOffice。
- 增加 PDFBox、Poppler、LibreOffice 组合转换能力。
- 增加 `qa-samples/run_qa.py`，支持 HTTP 端到端转换和像素级视觉对比。
- 转换路线增加 `qualityLevel`、`strategy`、`requires` 和 `limitations` 元数据，并在前端展示。
- 修复 CSV CR 换行导致行数错误的问题。
- 修复 UOF XML 文件头校验。
- 修复 UOF 直接转 DOCX 的页数漂移，改为 PDF 图层保真兜底。
- 增加独立 JVM Worker，通过 JSON 协议回传进度和结果，支持硬超时、崩溃隔离、进程树清理和单 Worker 堆上限。

## 近期目标

### 1. 显式转换模式

为任务 API 增加：

```text
mode=fidelity|editable
```

- `fidelity`：保真优先，允许图像层兜底。
- `editable`：可编辑优先，尽量重建文字、表格和图片结构。

同一条源格式到目标格式可以存在多个实现，由模式和质量等级选择。

### 2. 样本库治理

- 为 `qa-samples/input` 中每个样本补来源、许可证和测试目的。
- 区分公开样本、生成样本和本地私有样本。
- 为每条 stable/beta 路线至少保留一个公开可复现样本。

### 3. PDF -> DOCX 双路线

- 保真路线：继续完善页面图层 DOCX，重点解决二次渲染差异和多页尺寸问题。
- 可编辑路线：基于 PDFBox 提取文本位置，逐步重建段落、标题和表格；扫描型 PDF 后续接入 OCR SPI。

### 4. OFD 路线增强

- `OFD -> PDF` 升级为版式优先渲染。
- `OFD -> PNG/JPEG` 按页输出图片并打包 ZIP。
- 扩展厂商样本，覆盖签章、背景、表格、旋转文字和多页混合纸张。

### 5. 国产办公格式

短期基于 LibreOffice 兼容层，长期探索开源解析器或独立插件：

- WPS 文字：`.wps -> .docx`
- WPS 表格：`.et -> .xlsx`
- WPS 演示：`.dps -> .pptx`
- UOF：`.uof/.uot/.uos/.uop`

所有国产格式路线默认标记为 `experimental`，直到公开样本集通过质量门禁。

## 中长期方向

- OS 级资源管理：为 Docker/cgroup、systemd 和 Windows Job Object 提供 CPU、总内存和进程数配置指南。
- OCR 插件：接入 Tesseract 或其他本地 OCR，不调用云服务。
- 字体诊断：转换前识别缺失字体并给出明确警告。
- 多输出任务：支持 PDF 多页输出 PNG/JPEG ZIP。
- 插件系统：允许社区贡献可选转换引擎，而不污染核心依赖。
- CI QA：在 GitHub Actions 中运行无 Office 依赖的基础测试；Office/Poppler 样本 QA 作为可选工作流。

## 新增转换器清单

1. 确认格式定义和 MIME。
2. 实现 `FileConverter`。
3. 注册转换器。
4. 增加单元测试。
5. 增加端到端 QA 样本。
6. 更新 README 能力矩阵。
7. 更新质量标准和已知限制。
