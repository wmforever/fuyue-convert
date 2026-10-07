# 云端扫描表格和多栏边界（iteration45）

本轮修复全部为 OCR 词的区域被二次栏排序打乱的问题：保留已有识别顺序，坐标、字体、遮罩、原始文字和扫描底图不变。实际双语无边框控制 Word 阅读流 CER 从37.1859%降为1.0050%，3份 Office 输出逐像素不变。两次源码不同的有限矩阵共16个认证 HTTP → 独立 JVM Worker → 下载合约均返回 SUCCESS，但**扫描表格、内容完整性和可见金额编辑仍未通过验收**。源表格像素线框、可编辑文本框和真实 Word 单元格分别计数。

输入冻结于 `8eae8590ff26b5f8d96f6362e6035857568ea310`，原始 manifest SHA-256 为 `d51cd6f3ff75b7cf235347fb494d7d7a2ab9f62371d7f280cabede311334c0de`。旧 JAR SHA-256 `1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b`，新 JAR `41a75070b3d15c239bf42fe78c942457d4f0e9db8ba273660b41d69228759669`。详细文字、坐标、遮罩、TSV、置信度、下载哈希和时间见 [审计结果](cloud-scan-tables45-results.json)，其中 `baseline` 保留完整首批边界，外层记录已验证的小修复。未把复制顺序改善算作识别准确率或跨平台安装包认证。

## 固定输入与合约

两个 RGB PNG 均为1920×960、200 DPI（PNG pHYs 实际199.9996），白纸黑字，源物理尺寸约243.84×121.92 mm。使用 Noto Sans CJK Regular 2.004，逐字检查字体覆盖，许可证 SIL-OFL-1.1，字体 SHA-256 `b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a`。

- `ruled.png`：4行×3列，12个明确源单元格，标题“扫描台账 Scan ledger 00783”。三列为编号/日期/金额；三数据行分别为 `01936 / 2093-12-04 / -042.70`、`03872 / 2093-12-05 / +0085.40`、`07744 / 2093-12-06 / 0170.80`。固定4像素规则线，真值包含每个文字框和单元格框。唯一预声明编辑是末行金额 `0170.80→0180.80`。
- `columns.png`：不同像素、不同文本的无边框三栏控制，标题“独立多栏 Independent columns 00649”。编号 `00619 / 01238 / 02476`，日期 `2094-01-13/14/15`，金额 `-053.25 / +0106.50 / 0213.00`。每栏包含独立标题和结尾，冻结作者预期栏序，并另列视觉行序诊断。它不是表格；数字完整不能证明阅读意图可由像素唯一推断。

只执行一次9合约矩阵：有线 PNG→DOCX、有线 PNG→PDF→DOCX→Office PDF、一次 Word 单词编辑→Office PDF→API TXT；无边框 PNG→PDF→DOCX→Office PDF。3个实际 Office 转换，5页完整200 DPI只读渲染。源扫描 PDF 无原生文字。直接图片 DOCX 未额外经 Office 重开，明确未运行。

生产源码变化后，复用首批实际下载的两个 PDF 和同一 PNG，冻结独立7合约：两条 Word 入口、三次 Office、编辑后 TXT、直接 PNG→Word。没有重做2个包装 PDF，不重复旧9合约矩阵。新4份完整 TSV 与旧4份逐字节相同；新增3页 Office 渲染，总完整渲染页数8。

## 已验证的顺序修复

原始无边框 TSV 已按栏序排列，CER 为2/199（1.0050%，仅“左栏→A”）；Word 框序却为74/199（37.1859%）。`FixedLayoutDocxRenderer.fallbackReadingOrder` 先按识别源 `zOrder` 取序，再做栏沟分割；跨越左/中栏的标题连接两个栏，后续按基线排序将它们交错，造成真实的输出阶段顺序损失。

小修复只在区域内所有块都含 OCR 词时直接返回既有识别顺序。原生/混合区域仍使用原策略；不重新推断扫描作者意图、不过滤低置信度词、不规范化金额。新回归在旧实现实际1项失败，修复后3专项类36项通过；同几何的原生定位文字控制保留旧策略。

修复后 Word 阅读流恢复为原始 TSV 的顺序，CER 2/199；Office 原生提取同为2/199。所有文本/数字多重集合、逐词几何/字体/变换/z层次、遮罩和底图字节相同；3份 Office PDF 200 DPI渲染逐像素恒等。有线两份 Word `document.xml` 逐字节相同，编辑 API TXT 也逐字节相同，所以以下表格漏字/叠印边界没有被修复。

编译产物232类中226类和所有其他资源字节相同；一个生产源类及5个嵌套类二进制变化。5个嵌套类 `javap -c -p` 方法指令输出逐字节一致，源码插入导致调试行号变化。初版 QA 误预期“只有1个二进制变化”，审计在渲染前失败；已改为验证实际6个类、逐个核对嵌套指令，保留初次 exit1 回执，未重复 HTTP/OCR。

## 实际测量

下表记录修复前基线。CER 忽略空白、统一拉丁大小写，保留数字/符号，以冻结真值完整阅读流为基准；多栏 CER 同时包含阅读顺序错误，不等同于漏字率。对齐字符召回不证明完整。

| Word 路径 | 真值字符数 | CER | 对齐字符召回 | 精确数值字段 | 按源框精确文字 | 文本框 / `w:tbl` / 底图 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 有线 PNG→DOCX | 104 | 0.9615%（1/104） | 99.0385% | 9/9 | 11/12 | 22 / 0 / 0 |
| 有线扫描 PDF→DOCX | 104 | 47.1154%（49/104） | 52.8846% | 4/9 | 4/12 | 13 / 0 / 1 |
| 无边框扫描 PDF→DOCX | 199 | 37.1859% | 68.3417% | 9/9 | 不作为单元格计数 | 44 / 0 / 1 |

直接图片路径识别出所有数据行，表头 `ID` 变为 `1D`。该路径没有底图或矢量规则线，文本框不代表逻辑表格结构。扫描 PDF 路径保留完整渲染底图和栅格线框，但缺失全部3个编号、首行日期/金额和真实表头。无边框控制未伪造表格，左栏“左栏”识别为 `A`；Word 框序交错左/中栏后再右栏，Office 原生文本阅读流 CER 为13.5678%。该数字与 Word 框序 CER 属不同提取层，不能混作 OCR 改善。

TSV 观察器保留8次瞬时快照，其中可能含尚未写完的仅表头文件。审计逐文件验证哈希，并按 `(taskId,source)` 取最大、完整快照，得到4个独立生产 OCR 输入，额外诊断 OCR 为0：

- 直接 PNG：1920×960，非空词按字符加权置信度90.5003%。
- 无原生文字的有线扫描 PDF：生产先按300 DPI渲染为2880×1440，字符加权置信度87.4301%；生产页置信度0.77557，只发出 `OCR_APPLIED`。低置信度 `Ce / ee / ee` 的原始框分别为 `(125,275,2634,219)`、`(1312,271,505,275)`、`(2137,271,620,275)`，置信度15.56%、30.53%、27.82%。内容缺失已在原始 TSV 中发生。
- 编辑后 PDF 的必需嵌入图：1600×800，字符加权置信度92.4021%。它是另一条混合图文 OCR 路径，不是上述整页输入。
- 无边框扫描 PDF：2880×1440，字符加权置信度94.5715%。

扫描 DOCX 底图为2880×1440，不是源1920×960像素身份；物理页映射仍使用原 PDF 页面。直接 Word 与无边框 Word 的全部输出框在页内，有线扫描 Word 有2个异常低置信度输出框超出页边界。原始框、扩展后的 Word 框、遮罩框分别保留，未把图内 OCR 坐标冒称为原像素坐标或把保留底图冒称像素恒等。

## 可见性和一次编辑

审计证明只改了一个 `w:t` 节点，其余 ZIP 部件字节相同。Office 两份 PDF 都为1页，原生文字由 `0170.80` 变为 `0180.80`。200 DPI完整渲染仅489像素改变，全部在预声明源单元格内；这只是变化范围证据。

实际查看完整渲染和金额裁剪发现：**旧底图金额与新文字叠印，编辑可见性未通过**。表头有巨大 `ee` 覆盖，标题和其他已识别行也有叠印。不能因原生 PDF 或 API TXT 中旧值消失就宣称旧扫描墨迹消失。

编辑 API TXT 返回 SUCCESS，读到新值，未返回旧值，但缺失 `07744`、标题重复、第二行日期/金额拼接重复。对编辑真值 CER 为37.5%（39/104），对齐字符召回81.7308%。保留实际原文，包括 `+0085.402093-12-05 +0085.40`；没有规范化、选取或修正原始金额来制造通过。

## 来源判断、停止范围与下一有限问题

`PdfOcrSupport` 无原生文字路径固定300 DPI整页渲染；原生文字加必需扫描图路径仍为既有1600最长边。此次1920源图与2880整页的路线差异是事实，不证明“所有 PDF 改为原始分辨率”安全。iteration38 原2000输入已有中文数字回归，故没有重跑或采用全局改尺度方案。

`FixedLayoutDocxRenderer.addOcrMasks` 的纸色采样将低置信度 `Ce` 巨框外环判为黑纸、选择白色文字，使整页回退至旧遮罩层次；巨大 `ee` 仍覆盖表头。该全页回退具有已记录的 LibreOffice 24.2 深色纸回归依据，不能未经两个 Office 版本控制就改成混合层次。本轮只保护已有 OCR 顺序，不重启已拒绝的表格/多栏推断或重排试验。

下一项有证据的有限问题是：**如何让异常低置信度、跨多个源单元格的大词框保持原扫描可见，同时不改变可靠词的遮罩/编辑行为？**可先用已冻结 TSV 和底图作离线、零 OCR 的 renderer 对照，保留所有原始词和值；控制必须包含既有深色纸 Office 回归、未识别表头/数字、原坐标和未知区域。本轮没有实施该候选，不将它列为已否定或已修复。另一独立问题是严格单图整页避免不必要重采样，需独立资源/解码/DPI及回归证明，未与遮罩问题混合。

## 运行、成本与复现

实际9合约矩阵 wall34.9090s、子进程 user86.8494s/system7.3980s、最大单子进程 RSS364144 KiB。不是同时驻留内存总峰值，也不含生成/审计/只读渲染。逐合约耗时保留于结果 JSON。所有9个 Worker 身份已消失，监督器 ECHILD，新 zombie 为0；已有20个全局旧 zombie 保留，不进行信号清理。

新7合约 wall28.4255s、子进程 user70.0647s/system5.8116s、最大单子进程 RSS362372 KiB。由于动作数不同，不能把两次 wall 差解释成提速；增加一次常量时间的 all-OCR 判断，没有额外 OCR。两矩阵16个 Worker 都已消失。

环境：Linux x64、Temurin17.0.16+8、Maven3.9.11、Node22.17.0、Python3.12.14、Pillow12.3.0、NumPy2.3.5、PyMuPDF1.26.6、PDFBox3.0.8、POI5.4.1。实际应用使用固定 bundled Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1，`tessdata_fast` revision `87416418657359cb625c412a48b6e1d6d41c29bd`，`chi_sim+eng`、PSM3、120s页限、最低0.35、警告0.75、候选增益0.05、25M像素、并发1；未改引擎配置或门禁。eng SHA `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`，chi_sim SHA `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730`。OfficeDev26.8alpha0 commit `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`。

Word 字体配置为 Arial / Droid Sans Fallback；云端替代使用已安装 Liberation Sans2.1.5和 CJK fallback，实际 glyph 字体不是源图 Noto 的像素恒等重现。内置 Latin/CJK 字体和运行时完整固定哈希/许可证保留于 iteration35 证据，当前12个 OCR payload 另作哈希复核。

旧生产源 fingerprint 为 `07911c45cf0a510f7fa9f8ae279e977341c795e246c19711c3dce83aee95b198`，新 fingerprint 为 `11b3c1d372c2ed1a632bd90c9ecb2b59c6cd465dac5611eea1959f7c8bf7fa7c`，232类对应新源码/target/JAR。本轮实际完整 `mvn -B -ntp -Dskip.frontend=true clean package` 运行506项/505通过/1可选签名 OFD 跳过，0失败/错误；10项 bundled 条件测试实际执行。新提交的 CI 状态以草稿 PR 精确 SHA 记录为准。没有把此前系统 OCR 或这些本地结果计为 Office24.2 或桌面包验收。

在独立干净输出目录生成及首次 HTTP 的实际命令为：

```bash
python3 qa-samples/generate_scan_tables45.py
source /workspace/fuyue-env/activate.sh
export FORMAT_CONVERTER_APP_HOME=/workspace/fuyue-convert/qa-samples/work/iteration25-review-app
export FORMAT_CONVERTER_OFFICE_BINARY=/opt/codex/runtimes/codex-primary-runtime/dependencies/bin/override/soffice
unset FORMAT_CONVERTER_TESSERACT_BINARY
python3 qa-samples/work/iteration20-command.py \
  qa-samples/work/iteration45-http-receipt.json qa-samples/work/iteration45-http.log 520 \
  python3 qa-samples/capture_numeric_http35.py \
  --jar web-api/target/web-api-0.1.5.jar \
  --corpus qa-samples/generated/scan-tables45 --out qa-samples/work/iteration45-http --keep-going
```

生成器拒绝覆盖已存在冻结目录。首批基线审计 `python3 qa-samples/verify_scan_tables45.py` 只检查下载/OOXML/TSV/已有 PNG，零新 OCR/HTTP/Office；首次独立复现可加 `--render-missing` 生成缺少的5页渲染，不覆盖已有 PNG。本轮基线审计新渲染页数为0。源码变化后历史审计可用 `--baseline-jar qa-samples/work/iteration45-before.jar --historical-source --report qa-samples/work/iteration45-baseline-results.json`，通过固定 Git 源码核对旧 JAR，不把当前 target 冒称旧类。

`python3 qa-samples/verify_scan_order45.py --freeze` 从原始实际包装产物冻结7合约；本轮已经冻结，未再次执行该生成动作。保存旧 JAR 和基线审计，再构建新 JAR。新的实际认证矩阵用同一监督器/TSV捕获器、corpus `qa-samples/generated/scan-order45`、out `qa-samples/work/iteration45-order-http`，报告/日志/回执另命名。`python3 qa-samples/verify_scan_order45.py` 校验前后 Word/TSV/JAR，并只读新3页 Office 渲染，不执行新 OCR/HTTP/Office。

审计成功意味着事实/哈希/顺序修复一致，不意味表格质量通过。未实现真实扫描单元格、插删行、任意长编辑、通用阅读意图恢复；未运行 Microsoft Word、原生 macOS/Windows 包或签名私有夹具。
