# 云端跨格式产品验收（2026-10-06）

本轮从 `6cdbda921c702d5be76c06a99e0109ba256dc343` 扩展检查。产品尚不能宣称复杂扫描可靠或已成熟；任务成功、文本守恒、视觉清晰与真正可编辑分别判断。[结构化结果](cloud-product-quality-20261006-results.json) 只含公开合成语料，私有原件和失败产物继续隔离。

## 实际修复

- Excel 转 PDF 界面原本不提交工作表范围和宽度设置。现在发送 `spreadsheetSheets`、`spreadsheetFitWidth`。新增回归执行真实 Vue 组件提交函数，旧源码负控制确实失败，修复后通过；不再仅测选项规范化函数。
- TXT/CSV 上传入口按原始零字节误拒绝合法 BOM UTF-16，尽管转换器支持解码。现在仅对这两种格式的 UTF-16 BOM 输入复用严格解码与文本校验。真正 NUL、奇数长度截断、无 BOM 二进制仍拒绝，HTML 契约不改。LE/BE 各自的 TXT/CSV 正控制以及十个二进制负控制经任务入口验证。
- 实际后端健康/诊断与前端回退都仍声明 `0.1.4`。后端版本由 Maven 过滤 `@project.version@`，前端回退取自 package.json；实际 JAR、健康接口、设置和页脚均为 `0.1.7`。Maven 只过滤 application.yml 的 `@` 标记，Spring `${…}` 环境变量原样保留。
- CI 增加前端测试和桌面脚本/单元测试；桌面依赖安装使用 `--ignore-scripts`，不是原生安装器验收。

## 覆盖与产物检查

| 场景 | 实际结果 | 验收边界 |
| --- | --- | --- |
| 中英文原生 PDF 规则表格→Word→Office | 两份两页文档、四张真实表格、64 格文字精确；每张 5×4 网格及合并区域保留，Office 页数为 2；已查看渲染 | 不推广到扫描或不规则表格；本轮未重新执行此前金额长编辑矩阵 |
| CSV→XLSX→CSV / Office | 逗号、双引号、单元格换行、前导零和正负金额守恒；`=1+1`、`@sample` 为文本 | 保留字符串，不执行公式；Office 耗时/内存仍需关注 |
| BOM UTF-16→Word→Office / TXT | 从上传 HTTP 400 恢复为成功；两页、四个原数字字面量精确，TXT 对应正文 | Word→TXT 不恢复版式或换页元数据 |
| 两页空白 PDF→Word→Office / TXT | Office 仍为两页；TXT 无编造内容 | 空白保留不等同于扫描无字页可靠识别 |
| 损坏 PDF、NUL / 截断文本 | 坏 PDF 失败无下载；后两者 HTTP 400，无效令牌 401，不存在任务 404 | 未放宽文件头或编码保护 |
| 真浏览器 Excel 设置→Office PDF | 同一三工作表宽表：只选第 3 表 + fit-width 为 1 页；保留设置为 4 页；全部可见表为 5 页；跨隐藏表依赖公式结果 84 保留 | 很宽的表缩到一页会变得很小，实际图已查看；冷启动约 26.8–32.9 秒 |
| 一好一坏 PDF 批量→TXT ZIP | 好文件成功、坏文件失败；ZIP 保留成功 TXT 和 conversion-report.txt；明确 PARTIAL_BATCH_OUTPUT；HEAD 下载 200 | 不把部分成功当作全部成功 |
| 冻结二十步 TXT/Word/PDF/OFD/扫描图片及编辑链路 | 18 成功、2 个 OFD 提取仍 OCR_NO_NEW_TEXT；成功文本数字/顺序守恒。金额 127.50→128.75 后实际 Office 仅见新值，API 回读旧值缺席 | 英文扫描标题视觉词间距过紧，即便忽略空白 CER 为 0，也不判版面完全合格 |

六份冻结 bilingual 真值语料通过固定 bundled Tesseract 的生产 HTTP/独立 Worker 执行。没有新增采样/全页遮罩/阈值放宽或本轮 OCR 策略变更：

| 语料 | TXT CER（忽略空白/大小写） | 字符召回 | 数字组精确 |
| --- | --- | --- | --- |
| 英文 0° | 0.00% | 100.00% | 是 |
| 英文 −6° | 27.99% | 86.01% | 否 |
| 英文 +6° | 0.34% | 99.66% | 否：80421 仍为 80424，返回冲突警告 |
| 中文 0° | 3.06% | 96.94% | 是 |
| 中文 −6° | 1.02% | 98.98% | 是，另有文字冲突警告 |
| 中文 +6° | 1.02% | 98.98% | 是，另有文字冲突警告 |

正常中英文另完成 Word 与实际 Office 重开，各一页并查看图像；Word/Office 文本 CER 与 TXT 一致。倾斜 Word 本轮未重跑，不把 TXT 校正视为定位 Word 验收。这里是现有 OCR 的新实测，不把其既有改进归功于上述输入/界面修复。高置信度不等于内容完整。

## 测试、运行成本与可复现性

UTF-16 修复后完整 Maven package：527 项，525 通过、2 条件跳过、0 失败/错误，4 分 9 秒。随后版本元数据修复完成 Web 28/28 和重打包；最终前端 22/22、桌面 71/71 和脚本检查通过。精确提交的 CI 状态另在草稿 PR 记录，不沿用父提交的绿色状态。两个 Java 跳过为可选嵌套 OFD 签章样本和仅 Windows javaw 探针。

最后 JAR SHA-256：`8dd0894fd8592dde5adae5501f63ba18fb9ea33bfd9f92f7a1a1a02500c6230b`。237 个应用类与 target 一致；打包前端与最后 frontend/dist 文件清单及字节完全一致。最后清理了增量构建遗留的两份未引用旧静态资产，保留已实测的旧 JAR；应用类和实际引用前端完全相同，没有重跑大型矩阵。输入指纹 `bfadee40136537f7534d093e99396844fba964d0cf0fa96bcca3de208f6a615f`。

环境：Temurin 17.0.16+8、Maven 3.9.11、Node 22.17.0、LibreOfficeDev 26.8 alpha0 / `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`、Tesseract 5.5.2、tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`、chi_sim+eng / PSM3、Noto Sans CJK 2.004、Liberation Sans 2.1.5、PyMuPDF 1.26.6。固定 bundled 运行时可用已在 HTTP 健康接口核实；系统引擎 CI 不能替代它。

二十步链路墙钟 89.829 秒、最大单子进程 330868 KiB；OCR 十步 44.287 秒、329832 KiB。初轮 CSV/Office 等七步曾记录最大单子进程 3380004 KiB。该指标不是总 RSS，不据此承诺内存受控；OS 总内存/并发负载、生产固定 Office 与原生平台仍需独立验收。未延长任何生产或 QA 超时。所有已结束命令保留 ECHILD 回执；最初两次浏览器辅助脚本失败和 UTF-16 首轮失败均保留，没有覆盖证据。

```bash
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix desktop run check
npm --prefix desktop test
mvn -B -ntp -Dskip.frontend=true package
python3 qa-samples/generate_product_quality.py
python3 qa-samples/run_cloud_smoke25.py \
  --jar web-api/target/web-api-0.1.7.jar \
  --corpus qa-samples/generated/product-quality \
  --out qa-samples/work/product-quality-http --keep-going
```

生成器复用仓库许可字体的原生表格生成器，输出合成 CSV、UTF-16 文本、空白页和坏 PDF。运行器要求已配置的 app-home/ocr 固定引擎与可用 Office，输入/模型哈希固定且输出目录不可覆盖；不能只据脚本退出状态验收内容。新生成器与实际测试输入逐字节对应，空白 PDF 因随机对象 ID 单独确认两页无内容。云工作区详细证据在 `qa-samples/work/product-qa-20261006/`，不进入公开 Git/Docker。

## 未通过与未执行

[真实复杂扫描的巨大伪词、短标题重影、缺失字段、图内错字和非逻辑表格](cloud-scan-layout-20261006.md) 仍在，本轮保留既有修复和证据，没有重复十一页矩阵。一般扫描→可编辑 Word 的质量尚未达标；严格失败的 OFD 组合链路、倾斜英文数字冲突、标题词间距也未解决。Microsoft Word 重开、Windows/macOS 安装包、实际 UOF/WPS 客户文档和持续并发负载未新增验收，不改 main、不发布安装包、不合并此草稿 PR。
