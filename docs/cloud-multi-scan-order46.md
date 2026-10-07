# 多扫描图局部序号保护（iteration46）

本轮修复 iteration45 在“原生页眉＋两个独立扫描列图”上的 Word 复制顺序回归。保留45单图收益，但不把各图重复的局部序号或按图片追加产生的唯一序号视为页面阅读意图。实际 Word OCR 区域 CER 从79/117（67.5214%）降为0/117；**Office 抽取顺序和混合 Word 页眉 XML 顺序仍未通过完整阅读流验收**。

起点 `d32db3cf329f7ed24bcf9cf93d6a78dcf65b4de6`，同一 `wmforever/fuyue-convert` 分支及草稿 PR。旧 JAR SHA-256 `41a75070b3d15c239bf42fe78c942457d4f0e9db8ba273660b41d69228759669`，新 JAR `2e1961405e080daf66bb60bec6b6c8784739ef7091b0171301834336aaae6061`。新生产源 fingerprint `df9eebc3f63f60371799c22481a4b0b0ec6efc3b32ab4e803ed91fa87f951738`。实际完整文字、坐标、TSV、字体、下载哈希、测试和资源见 [结果](cloud-multi-scan-order46-results.json)。

## 实际复现与小修复

`PdfOcrSupport` 对有原生文字页走逐图 OCR，每张图的 `TesseractOcrConverter` 结果从局部序号1开始。两个扫描来源可进入同一全 OCR fallback 区域，45 的 early return 按局部序号取序，输出 `L1,R1,L2,R2`。本轮先用最小 Java 页面实际复现，再用原生页眉和两个互不重叠扫描图的 PDF 经认证 HTTP→独立 JVM Worker→下载→Office 复现，没有据静态审查强造结论。

`FixedLayoutDocxRenderer.fallbackReadingOrder` 现在只在**至多一个 OCR 背景来源、序号唯一、区域全为 OCR**时保留识别顺序。其余情况回到已有几何/变换/表格策略；不改逐图 OCR，不按图片追加次序重编全页序号，不改变原始词、数值、框、字体、遮罩或层次。图来源计数在2处停止，不解码新图像；没有新 OCR 调用、时限/像素/并发放宽。

Java 回归覆盖：双图局部序号重复、双图按右图先追加的唯一序号负控制、原生页眉仍保留、两个不同扫描资产仍保留；45 的单图跨栏标题及原生栏策略控制保留。最初旧实现1项失败，隔离 OCR 区域后的旧实现仍1项失败。修复后3专项类37项通过，最终新增的两种序号控制1项通过；完整 clean package **507项/506通过/1可选签名 OFD 跳过，0失败/错误**，10项 bundled 条件测试实际执行。

## 冻结输入与真实合约

原生页眉为 `NATIVE HEADER MIXED 00573`，600×340 pt 页面。两个960×960 RGB PNG 分别放在 `[24,68,264,308]` 和 `[336,68,576,308]` pt，来源解码 RGB 与源 PNG 逐像素相同。每栏5行：

| 左扫描栏 | 右扫描栏 |
| --- | --- |
| 左栏 Left ledger | 右栏 Right ledger |
| ID 04619 | ID 04620 |
| Date 2095-02-11 | Date 2095-02-12 |
| Amount -064.30 | Amount +0128.60 |
| 左栏完成 End left | 右栏完成 End right |

源 PNG 使用 Noto Sans CJK Regular2.004、42像素字，生成前逐字检查覆盖，SIL-OFL-1.1，字体 SHA `b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a`。PDF 原生页眉及纯原生负控制使用标准 Helvetica。另冻结纯原生双栏（编号05731/05732、日期2096-03-21/22、金额-075.45/+0150.90），以及复用45原始单图 PDF。没有相同像素另赋歧义真值。

旧 JAR 只执行4合约：混合 Word/Office、原生 Word/Office。新 JAR 执行6合约：上述4项＋单图45 Word/Office。10个独立 Worker 均消失，监督器 ECHILD，新 zombie0。未重做45矩阵或包装 PDF。

## 内容、顺序与可见性分别计量

| 层与真值 | 修复前编辑距离 | 修复后编辑距离 | 结论 |
| --- | ---: | ---: | --- |
| 双图 Word OCR 区域，117字符 | 79 | 0 | 复制序列恢复，原始字符集合未变化 |
| 双图 Word 整体，139字符 | 106 | 44 | 页眉仍在 XML 中位于 OCR 之后，未宣称整体通过 |
| 双图 Office 原生抽取，139字符 | 79 | 71 | 仍交织/拆分行，未通过 |
| 纯原生 Office 负控制，127字符 | 69 | 69 | 原有边界未变化，不代表阅读意图正确 |
| 单图45 Office，199字符 | 2 | 2 | 已验证收益保留，“左栏→A”识别错误仍在 |

CER 忽略空白、统一拉丁大小写，保留符号数字。所有双图 OCR 数值字段、原生页眉、文字多重集合、逐词几何/字体/变换/z层、遮罩和源扫描字节都不变。两个实际960×960逐图 TSV 前后字节相同，单图45 TSV 与此前已验收产物也相同；总5个生产 TSV 身份，额外诊断 OCR0。置信度未变，识别准确率未改善。

纯原生 Word、单图45 Word 的 `document.xml` 前后字节相同。5份实际 Office PDF 重开为1页并完整200 DPI渲染，3组前后渲染逐像素相同。像素恒等是“不新增可见变化”的证据，不证明原始扫描/编辑叠印已解决或 PDF 复制顺序正确。本轮不做金额编辑，不把全扫描图片当作可编辑表格。

232个应用类与新源码/target/JAR对应，226类和其他资源字节不变。一个源类及5个嵌套类二进制变化；5个嵌套类 `javap -c -p` 方法指令输出相同，源码行号变动影响调试信息。

## 初次 QA 假设、成本与边界

保留初次失败回执：Java 初始断言含页眉应在 XML 最前的既有边界，随后隔离 OCR 区域，仍复现交织；两份相同 PNG 被 POI 合法去重为一个媒体部件，测试随后改用不同扫描资产。实际 PDF 从一开始就是两个不同源图。最初审计假定 Office CER为0，实测不成立；5份渲染及哈希保留，只读修正审计结论，未重复 HTTP/OCR/Office。

4合约基线 wall17.9018s、子进程 user48.1232s/system3.4920s、最大单子进程 RSS329484 KiB；6合约候选 wall26.2321s、user66.2393s/system6.2203s、RSS358676 KiB。动作数不同，不能据此声称提速或内存回归；RSS不是同时驻留总峰值，不含生成/审计/渲染。每合约120s、矩阵480s，生产页限120s、25M像素、并发1未变。

运行环境与45相同：Linux x64、Temurin17.0.16+8、Maven3.9.11、Node22.17.0、Python3.12.14、Pillow12.3.0、NumPy2.3.5、PyMuPDF1.26.6、PDFBox3.0.8、POI5.4.1；bundled Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57/zlib1.3.1，`tessdata_fast` revision `87416418657359cb625c412a48b6e1d6d41c29bd`、chi_sim+eng/PSM3。12个运行时 payload 本轮独立复核哈希；系统 CI OCR 不替代 bundled。OfficeDev26.8alpha0 commit `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`，实际输出字体保留于结果 JSON（Droid Sans Fallback2.55、Liberation Sans2.1.5）。没有新模型/字体/参数搜索。

全球20个旧 zombie 的 PID/startTicks 与45核对基线相同。其中10个有 iteration16 登记归属（6Java/4Python），其余10个历史归属仍未证实；不是本轮新增，也未宣称无关。未向 PID1/zombie 发信号。

混合 Word 页眉 XML 顺序、Office 抽取顺序、任意/重叠/旋转/多图阅读意图仍未验收。45 表格漏字/巨大低置信框/旧金额可见叠印/API重复或缺失编号保持原边界。未运行 Microsoft Word、原生 macOS/Windows 包或可选签名夹具；未 merge/release/访问 Mac/重试 Library。后续由父会话协调，未启动巨大低置信框候选。

## 复现入口

`python3 qa-samples/generate_multi_scan_order46.py` 冻结源、前4/后6合约及真值，拒绝覆盖。使用固定 app-home/Office 环境和公开 `capture_numeric_http35.py`，分别运行 corpus `qa-samples/generated/multi-scan46` 与 `multi-scan46-after`，out `qa-samples/work/iteration46-before-http` 与 `iteration46-after-http`，通过 `iteration20-command.py` 监督、记录独立日志/回执。保留旧 JAR为 `qa-samples/work/iteration46-before.jar` 后 clean package 新 JAR，并用 `record_cloud_provenance.py` 绑定源码/target/提交。

`python3 qa-samples/verify_multi_scan_order46.py` 只读校验真实下载、Word XML、TSV、类及 Office PDF；首次生成5份完整200 DPI渲染，已有渲染按记录的 PDF/PNG 哈希复用，零新 OCR/HTTP/Office。单图45控制读取此前实际产物，未重新执行旧矩阵。新提交 CI 的精确 SHA 状态以草稿 PR 和保存的 CI 读回为准。
