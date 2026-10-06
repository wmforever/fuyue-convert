# Word 转 TXT 和页面提示（2026-10-07）

从 `59dd1859cc912cc525aeee4cb125736e2f892b1c` 修复扫描 Word 转 TXT 多出括号、原行被拆散的问题。Apache POI 会给图片中的文本框内容加括号；这些符号并不是原文。

现在直接读取本软件生成的独立 OCR 词框，按已有行标识输出文字。原来的括号、空格、金额、前导零和低置信度词仍保留，不做文本纠错。混合正文、修订、未知文本框、重复词标识或复杂内容仍走原来的提取逻辑，避免丢掉其他内容。未对所有 Word 全局删除括号，也未增加隐藏文字层。

页面上把“路线”“故事区”“适用边界”等说法改成转换格式、文字来源和转换限制。测试中的功能明确提醒用户核对内容和排版；PDF 工具直接列出用途，去掉未经普遍验收的“可靠”表述。

## 实际 API 结果

冻结同一批 Word 文件，使用旧 JAR 与最终 JAR 分别运行 7 次认证上传、独立 JVM Worker 转换和下载。旧结果中 6 份 OCR Word 的 TXT 与可见词框文字不相等；最终逐字及逐行相等。原上传的失败 Word 另外完成一次最终 JAR 提取，不重复生成扫描或重跑旧转换矩阵。

| 文件类型 | 最终检查 |
| --- | --- |
| 两份独立英文扫描 | 各 9 个词框、4 行；原金额和编号不变 |
| 两份可见金额编辑版本 | 各 4 行；新金额各出现一次，旧金额不存在，负号和前导零保留 |
| 中文扫描 | 67 个词框、9 行，与可见文字及原行精确对应 |
| 普通 Word | 原括号、表格两列、页眉页脚符合独立真值，与旧 TXT 字节完全相同 |
| 两份已授权私有 Word | 各 2102 个已有词框、423 条识别行，分别与自己的可见文字精确对应 |

英文原结果恢复为 `REVIEW RECORD 2036 / Record 00842 / Amount 127.50 / Date 2036-04-19` 四行。编辑版本读取 `128.75`；另一份负金额读取 `-0068.95`。以上精确对应衡量的是 **Word 可见文字的提取完整性**，不等于扫描原件的 OCR 完整性或阅读顺序正确率。私有内容、文件名和校验值不进入公开报告。

## 验证及证据

新增专项覆盖实际渲染器生成的中文、真实括号、低置信度词、负数、前导零和可见 run 编辑；混合正文、修订、普通框、重复框保留原行为。最终专项 4/4；首次测试因 XMLBeans 不支持 DOM Level 3 读取及错误 XML 片段构造出现 1 失败、1 错误，修正测试后通过，失败日志保留。

完整 `clean test` 一次通过：532 项，530 执行通过，2 条件跳过，0 失败/错误；其中实际 Office 6/6。跳过仍为可选 OFD 样本与 Windows 探针。前端 22/22 和生产构建通过。打包与隐私门禁通过，具体提交的 CI 结果见草稿 PR，不沿用上轮绿色状态。

最终 JAR SHA256 `4da10130d003e94b841735167816f214ddb73f35135a6ab6c5b88e2aedfc6d5d`，构建输入指纹 `b5d040b65a1ea79f92d005fa4536e3d0f33870a484c94e147b19229f6a88ac3d`；237 应用类与 target 一致。与旧 JAR 比较仅 `DocxToTextConverter` 类变化；Word/Office 生产类逐字节相同。7 个静态资源与新前端构建完全一致。已有真实 Office 重开、11 页渲染和金额显示证据保留，未重复受本修改影响不到的 22 步渲染矩阵。

同一 7 步旧/新墙钟分别 57.61/27.30 秒，最大单子进程 RSS 分别 360436/320964 KiB；额外原上传 Word 单步墙钟 12.21 秒、317828 KiB。全部 Worker 已回收，无新僵尸。这些是本次观测，受启动和缓存影响，不能作为性能提升或总内存上限的结论。

运行环境：Temurin 17.0.16+8、Maven 3.9.11、Node 22.17.0、Python 3.12.14、POI 5.4.1、PDFBox 3.0.8。OfficeDev 26.8 alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`；Liberation Sans 2.1.5、Noto Sans CJK 2.004。健康接口确认固定 bundled Tesseract 5.5.2、Leptonica 1.87，tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`、chi_sim+eng、PSM3、120 秒/页、25M 像素、并发1。此次 TXT 提取不调用 OCR，也未改模型、字体或这些限制。

## 复现与剩余问题

```bash
mvn -pl task-service -am -Dskip.frontend=true -Dtest=DocxToTextConverterTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 qa-samples/verify_docx_ocr_text.py --docx frozen.docx --text downloaded.txt --out text-audit.json
```

审计工具核对可见 run 和原行，遇到普通正文或未知框拒绝忽略它们；普通 Word 另用真值验证。冻结输入、旧/新 JAR、全部下载结果、失败日志、类比较、进程回收和校验记录保存在隔离目录 `qa-samples/work/text-extraction-20261007/`。

复杂扫描仍有巨大伪词、中文标题和深色纸面重影、字段遗漏、截图/架构图误识别。Office PDF 转 TXT 的 `OCR_VISIBILITY_UNCERTAIN` 未解决。本次没有改识别结果或放宽保护，不能称为复杂扫描全可编辑修复。未知或经其他工具改写结构的 Word 仍可能走 POI 原路径并出现包装括号。Microsoft Word、原生 macOS/Windows 安装包和并发压力验收未新增；未发布、合并或写 main。

此前实际 Office 图像与跨格式边界见[扫描 Word 报告](cloud-ocr-word-spacing-20261006.md)和[常用转换验收](cloud-product-quality-20261006.md)。
