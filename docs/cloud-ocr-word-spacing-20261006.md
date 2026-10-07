# 扫描 Word 词间距与保护性验收（2026-10-06）

本轮从 `eb0c5780309300ad5c69dd9bc8055bd95dab5fec` 修复实际 Office 中英文标题词相互侵入的问题。任务完成、识别内容守恒、视觉质量和真正可编辑性分别验收；复杂扫描仍未达到成熟质量。[结构化结果](cloud-ocr-word-spacing-20261006-results.json) 只含公开合成数据和匿名私有边界。

## 根因与生产修改

Linux Java 将 Arial 静默替换成 Dialog 测量，Office 使用 Liberation Sans。部分大写词的实际字形更宽。既有补偿检查加宽后的透明文本框，能防换行，却未必防止文字侵入下一个词的位置。

现在还检查同一行相邻可靠词的原始位置：扣除后词的既有左侧字形偏移，并保留一半原词间距。只接受既有字体保护条件下、可证明容纳实际半点字号和整数百分比的收缩；数字、混合编号、中文、低置信度、旋转/倾斜、重叠或异行边界保持原保护。原字号、词框位置、扫描、遮罩和金额编辑余量不变。

独立新样本抓到了首版回归：邻词限制太紧时返回原比例，撤销已有有效收缩。最终实现取已验证比例与新证明比例的较小值，失败的新证明不会撤销旧修正。失败候选 JAR、完整 22 步下载产物和审计失败保留在本地；没有放宽验收或删除词。

## 实际产物结果

八类冻结输入：两个独立标题、另两份标题、中文、深色纸面、负数和前导零；22 次认证 API→独立 JVM Worker 操作全部完成。实际 Office 重开和渲染后，140 个词框中只收缩 8 个英文词；所有源图字节、遮罩、原数字、文字顺序及位置保持，变化像素全部在受影响文字框内。中文对照零变化像素。

| 实际相邻字形间距 | 修复前 pt | 修复后 pt |
| --- | ---: | ---: |
| REVIEW / RECORD | -0.774 | 5.152 |
| RECORD / 2036 | -0.948 | 5.562 |
| REVENUE / REVIEW | 2.976 | 2.976 |
| REVIEW / 2071 | -2.374 | 3.552 |

两份标题的四行文本在 Word/Office/API 中对应，忽略空白的 CER 0、字符召回 100%；以上字形间距另行证明视觉改进。只改可见文字 run 的金额 `127.50→128.75`、`-0054.80→-0068.95` 后，实际 Office 新值可见、旧值不残留，API 文本逐行精确，符号和前导零保留。无重复隐藏文字层。

已授权的私有原件重新完成 PDF→Word→Office 和 TXT 检查：仍为 11 页、2102 个既有词框、11 张原扫描；只收缩一个英文词，第5页452个150 DPI像素变化，其他10页像素恒等。此为**保持既有识别结果**，不能证明原件已完整识别。Word→TXT 顺序保留，但存在既有 POI 逐词括号包装。Office PDF→API TXT 明确失败 `OCR_VISIBILITY_UNCERTAIN`，没有交付伪成功产物。

实际查看全部页面及第4/5页局部，复杂灰底/合并表格的巨大伪词、短中文标题重影、字段遗漏及截图/架构图误识别仍在；深色纸面对照正文也有既有重影。保护性审计通过不等于这些页面视觉质量通过。未修改 OCR 策略、模型或保护门禁，不能宣称解决复杂扫描全可编辑问题。

## 测试与运行边界

字体专项最终5/5。最终源码完整 reactor 首次在宽表 Excel 的45秒 Office 限时出现1次超时，已保存失败报告；其他已执行用例通过。相同限时下隔离重验该用例通过，再完成未运行的 Web28/28。合计530个不同用例：最终528通过、2条件跳过，无未解除测试错误；**不是一次全量零失败**。两个跳过是可选嵌套 OFD 签章样本和仅 Windows javaw 探针。最终打包通过，精确提交 CI 另见同一草稿 PR，不沿用父提交绿色状态。

提交 `eddbafcbcc951fb435fa44d6bb3f5e55372a5042` 的[CI#108](https://github.com/wmforever/fuyue-convert/actions/runs/37496863898)实际OCR24/24、Office6/6通过；主测试在新增回归的固定毫米几何假设失败，后续JS门禁未运行。回归改为用实际字体advance构造相邻间距，仍要求原比例真实溢出、修正容纳、数字不变及失败证明不撤销旧修正，未跳过或削弱断言。该补充只改测试和记录，生产源码/JAR不变；本地5/5重验，最终精确SHA CI继续核查。原失败日志保留。

最终 JAR SHA256 `226af9e5a4a8e1e275238c2cd50b42948c8b97e2005e2977962f884e18d34312`；输入指纹 `4f854297b1dcc77c245604be408b5b3994ac9ef7eba28ac4581c08e0a6a7a278`。237应用类与target匹配；与父版本相比仅3个渲染器/内嵌类字节变化，其他应用类相同；7个静态资源与frontend/dist清单及字节完全一致。

合成22步墙钟91.92秒，最大单子进程RSS344864 KiB；私有4步墙钟133.63秒、3成功1明确失败，最大单子进程RSS972852 KiB。均独立Worker，全部ECHILD回收、无新僵尸；这是观测成本，非总OS内存上限或并发压力验收。宽表冷启动延迟仍是风险，不提高限时掩盖。

环境：Temurin17.0.16+8、Maven3.9.11、Node22.17.0、Pillow12.3.0、PyMuPDF1.26.6、PDFBox3.0.8、POI5.4.1、Liberation Sans2.1.5、Noto Sans CJK2.004；LibreOfficeDev26.8 alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`。实际健康接口确认bundled Tesseract5.5.2/Leptonica1.87，tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`、chi_sim+eng、PSM3、120秒/页、25M像素、并发1。系统OCR CI不代替固定运行时。Microsoft Word、原生macOS/Windows安装器、新倾斜Word矩阵及总OS负载未运行。

## 公开复现

需要现有 QA Python 依赖 Pillow、PyMuPDF、lxml 和已配置的固定 OCR/Office。生成器使用仓库许可字体，输入与之前样本独立。把父提交构建的 JAR 保存在 `baseline.jar`，分别运行，输出目录必须是新的：

```bash
python3 qa-samples/generate_ocr_word_spacing.py --out qa-samples/generated/spacing-before
python3 qa-samples/run_cloud_smoke25.py --jar baseline.jar --out qa-samples/report/spacing-before --corpus qa-samples/generated/spacing-before
python3 qa-samples/generate_ocr_word_spacing.py --after qa-samples/report/spacing-before --out qa-samples/generated/spacing-after
python3 qa-samples/run_cloud_smoke25.py --jar web-api/target/web-api-0.1.7.jar --out qa-samples/report/spacing-after --corpus qa-samples/generated/spacing-after
python3 qa-samples/verify_ocr_word_spacing.py --manifest qa-samples/generated/spacing-after/expected.json --after qa-samples/report/spacing-after --out qa-samples/report/spacing-audit.json
```

本轮生成器重建的PNG与实测输入逐字节一致，after模式冻结PDF及五步依赖图与实际验收一致，无重复矩阵。所有私有原件、渲染、失败及进程证据位于被Git/Docker隔离的 `qa-samples/work/word-spacing-20261006/`。

参考 [pdf2docx TextSpan字体处理](https://github.com/ArtifexSoftware/pdf2docx/blob/3e1c2319d6a3fbf2ae4d46c3ab734b7fc87bd9b4/pdf2docx/text/TextSpan.py) 的字体替代宽度和半点字号/缩放处理；已核对同版本[MIT许可](https://github.com/ArtifexSoftware/pdf2docx/blob/3e1c2319d6a3fbf2ae4d46c3ab734b7fc87bd9b4/LICENSE)。本实现独立编写，未复制代码或增加运行依赖。[Tesseract官方说明](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#tables-recognition)明确指出表格需要额外布局分析，单换参数不应当作复杂表格修复。其余已有数值冲突、OFD失败和资源边界见[上轮产品实测](cloud-product-quality-20261006.md)。
