# 稀疏 OFD 阶段跟踪与英文标题字体度量修复

父提交 `c97de2a2fc05b177b2d34fef525d3637b3d96667`，仍为同一分支和草稿 PR #1。先读取 iteration25/29 原样本、真值与失败记录，再做有限独立控制；没有重跑已有矩阵或扫描 OCR 参数。

## 稀疏 OFD：不是图片缩放或识别丢失

原文件 SHA-256 `d10ea3329fab62a3af1c58da09750fac259c19d5bb5fb355c1d17ed1f66a932a` 的图像为 **1360×1760**、物理框 **215.9×279.4 mm**（160 DPI）。实际 OFD 解析器读取的资源、ImageIO 标准化输入和源 PNG 的 ARGB 像素逐一一致，没有重采样。编码/分辨率元数据使 PNG 文件哈希不同，不能把文件哈希差异当作像素损坏。

固定引擎识别完整四行双语正文；对真值 CER **0**、字符召回 **100%**，金额 `127.50`、编号 `00842`、日期 `2036-04-19` 均保留。四个识别行全部在原生文字去重阶段删除。少量原生文字不能通过保守的扫描覆盖门槛，而重复识别也不能证明图像不存在其他漏字，故 `OCR_NO_NEW_TEXT` 仍属严格完整性拒绝。原文件的原生对象 Boundary 是整页框，实际文字另有局部偏移；记录了这些几何，但没有通过改覆盖阈值、信任自产文件或平均置信度放行。

独立稀疏控制使用 `SPARSE TRACE 2064`、`Record 00793`、`Amount 048.65`、`Date 2064-09-28`。冻结同像素 PNG/PDF、仅图像 OFD、完整原生层加图像、删除原生金额、别名前缀金额缺失、空白扫描和孤立噪点。阶段探针对 7 个 OFD 只执行 **4 次唯一像素/物理框识别**，复用其余结果，不扫参数。

实际 HTTP 共 **10 请求：7 个完整文本成功、3 个预期严格失败**。原 PNG/PDF 与独立 PNG/PDF/图像 OFD 的文本逐字匹配真值；缺原生金额的标准/别名前缀 OFD 补回金额且其他行不重复。完整原生层加稀疏图像继续 `OCR_NO_NEW_TEXT`；空白扫描及噪点无文字，均 `OCR_NO_TEXT`、无下载。真正无内容的 OFD 页面仍由全量解析器空白页回归覆盖，没有把无 OCR 文字的扫描失败伪装为空白成功。[阶段结果](cloud-sparse-trace30-results.json)。

初版生成器把 PNG/PDF 请求命名为相同标签。原报告保留各自任务 ID、10 个独立 Worker 和各下载 SHA；两组重复标签的结果分别逐字节相同，故没有内容证据丢失。后续生成器标签已包含扩展名，冻结执行清单和原 SHA 保留，没有重跑请求。

该调查没有得到安全的完整性转换修复，因此转向独立复现的非金额标题换行。

## 标题根因与最小实现

云端 Java 的 `new Font("Arial", ...)` 实际选择 **Dialog.plain**；实际 Office PDF 字体为 **LiberationSans**。在 13.5 pt、119% 横向缩放下，`RESERVE` 的 Dialog advance 为 **65.240 pt**，Arial 兼容字体的实际 advance 为 **76.779 pt**，超过现有框 **72.870 pt**。`RESERV` 后的 `E` 因此换行，API 又把末字母排到标题后。公开度量探针也验证现有 bundled Liberation Sans 字体与该系统字体在诊断词上的 advance 相同。

只把冻结 Word 中该词 `w:w` 的 **119 改为 100**，经真实 HTTP→独立 Worker→Office 后立即恢复 `EDIT RESERVE 2053`，其他文档部件和数字未改。另两份独立扫描也复现 `PROJEC`/`T` 和 `RESERV`/`E`，没有针对 OCR 阈值调参。

生产实现只纠正已证实的字号度量溢出：

- Java 确实回退到 Dialog，且存在系统/已有 bundled Liberation Sans 度量字体；源词为 3–32 个 ASCII 字母、置信度至少 0.85，轴对齐且无斜切。
- 使用实际写出的半点字号、整数横向百分比检查 advance。只有现有框装不下该词，才按两种字体的 ink 宽比缩小原横向比例。
- 候选不得小于 60%，必须比原比例小，且实际 advance 必须能放入原框。未知字体、原框已足够、低置信度、数字/日期/混合 ID、过窄框等均保持原行为。置信度只控制可选布局修改，不是完整性证据。
- 原始词框、bearing/定位、文字、字号、字体声明、图像、遮罩和数字编辑空间均保留；不扩遮罩，不改 OCR、数字选择或 `OCR_NO_NEW_TEXT`。仅同一字形的横向比例及由其导出的透明框容差可能变化。

两项新回归检查实际输出字号/整数比例后的 advance，以及数字、低置信度、未知字体、原框足够和过窄框的回退；与遮罩/Word 回归合计 **19 项通过**。

## 最终验证与真实剩余问题

[产物结果](cloud-heading30-results.json)，[构建、版本及 helper 绑定](cloud-heading30-validation.json)。

- clean package 共 **473 项：472 通过、1 项可选真实签名 OFD 跳过、0 失败/错误**；bundled OCR 条件用例实际执行。229 个应用类匹配 fresh targets，前端重建 7 文件与 JAR 内资源一致。相对父 JAR，仅 FixedLayoutDocxRenderer 类族改变，其余应用类逐字节一致。
- 最终 JAR SHA-256 `de05c153351caf48a41ed5522fb9b591756f2e8d178301e3a7aa7619d9630b18`；源码输入指纹 `4ea22a954172673bf16834fe40fa6cc9dc37b9971af766de1e9fcf8dff6dbc85`。绑定最终提交和精确 SHA CI 的记录保留在 PR/最终云端检查点，不用旧 CI 冒充新提交验收。
- 最终 **22 次 HTTP：20 次接口成功、2 次预期失败**。5 份 Word 的正文文字、原图、遮罩、字体声明/字号、源定位不变，只分别改 `RESERVE 119→100` 或 `PROJECT 117→100`；实际 Office 中标题不再拆行。改变词区外 300 DPI 像素一致，数值词精确，原警告不变。暗底 Word 除 core 时间戳外所有部件逐字节不变。
- **10 次**实际合成 Office 标题区域的 bundled OCR（5 份前后各一次）证明单行字母恢复。原 3 份 API CER 分别 **3.226%/3.175%/3.125%→0**，两份独立样本 **2.941%→0**；这只是去空白指标。4 份全文 API 逐字匹配真值，独立 `reserve` 样本仍把 `REVIEW 2068` 连成 **`REVIEW2068`**，明确记为 **1 项未解决的原有分隔错误**，没有按 CER 0 称作全文通过。
- `4.10→541.80` 实际编辑、Office 重开和 API 全文逐字匹配；金额单行，数值词精确。OFD 标准/别名前缀的缺失金额控制仍完整且逐字匹配；原始稀疏文件继续 `OCR_NO_NEW_TEXT`，噪点继续 `OCR_NO_TEXT`，均不可下载。
- 22 个 Worker 全退出、ECHILD、无新增僵尸。墙钟 **67.550 s**、子进程 user/system **177.234/14.500 s**、最大子进程 RSS **349,348 KiB**。稀疏 10 请求为 **34.025 s / 429,748 KiB**，独立标题基线 8 请求为 **26.847 s / 343,076 KiB**；请求组合不同，不声称性能改善。

首次核验器误要求存在 `w:w`，但 100% 可合法省略节点；此时尚未执行可视 OCR，保留失败及原 helper 后修正。第二次核验器完成全部 10 次可视 OCR 后，因真实的 API 分隔错误失败；保留失败及 helper，把此案例单独标为未解决，再凭原 PNG 字节、输出哈希、完成/ECHILD receipt 复用这 10 次结果。没有重跑 HTTP/可视 OCR，也没有更改产物、源真值或生产代码来通过检查。

运行版本沿用并复核 iteration29：Temurin 17.0.16+8、Maven 3.9.11、Node 22.17.0、Python 3.12.14、PDFBox 3.0.8、OFDRW 2.3.9、Tesseract 5.5.2/Leptonica 1.87.0、fast 模型提交 87416418657359cb625c412a48b6e1d6d41c29bd、Liberation Sans 2.1.5、Noto CJK 2.004、LibreOfficeDev 26.8 alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`。这是固定 Linux 运行时结果，不代表其他字体、Microsoft Word 或 macOS/Windows 原生包验收。没有合并、发布、Mac 访问或 Library 重试。

复现入口：`generate_sparse_trace30.py`、`OcrSparseTraceProbe.java`、`verify_sparse_trace30.py`；`OcrHeadingMetricsProbe.java`、`generate_heading30.py`（before/`--scale-proof`/`--existing-before`/`--after`）、已有 `run_edit_iteration26.py`、`verify_heading30.py`。`--resume-visible` 只验证完成的相同输入探针，不能补做缺失结果。私有服务器/任务数据、认证令牌及二进制样本仍不进入 Git。
