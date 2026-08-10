# 测试报告

## 2026-08-09 全量验证

本轮以 Java 17 从干净构建目录执行：

```bash
mvn clean verify
python3 qa-samples/run_qa.py
```

结果：

- Java 单元/集成测试：113 个，通过 113 个，失败 0，错误 0，跳过 0。
- Vue 生产构建：通过并打入 Spring Boot 可执行 JAR。
- HTTP 端到端样本：21 条；严格通过 20，严格失败 1，视觉阈值通过 5。
- PDF -> DOCX：纯文字多页样本文字可编辑、页数一致、零内嵌图片；扫描样本未配置 OCR 时严格返回 `OCR_REQUIRED`。
- PDF -> PNG：透明语义先白底合成后跨 PDFBox/Poppler 的差异率为 `0.00068802`，通过 `0.001` 门禁。
- PNG -> PDF：源图 DPI 推导尺寸期望 `54.0075 x 36.0050 pt`，实际 `54.0075 x 36.005 pt`，单页严格通过。
- 唯一严格失败：experimental `UOF -> DOCX`。LibreOffice 可生成可编辑 DOCX，但复杂样本由 7 页变为 6 页，尾注罗马编号发生漂移，因此不宣称内容或视觉严格通过。

本轮还覆盖取消、失败重试、重启恢复、TTL 清理、单任务上传配额、磁盘安全水位、进程树终止、Worker 输出边界及 Unix/Windows 绝对路径脱敏。完整机器可读报告由 `qa-samples/run_qa.py` 写入 `qa-samples/report/qa-report.json`。

### OCR 加强验证

- 无真实引擎的契约测试覆盖：源码模式默认关闭、内置运行时自动启用、显式关闭优先、引擎缺失、内置包损坏、语言包缺失、非法置信度关系、超时、最低置信度失败、跨进程配额、OCR 独立像素上限和页面模型缺页。
- 内置运行时集成测试从独立 `app/ocr` 目录启动真实 Tesseract，验证二进制、动态库、语言模型和 `TESSDATA_PREFIX` 均不依赖系统安装路径。
- 真实 Tesseract 金样覆盖：英文与数字、简体中文与标点、EXIF 方向校正、竖排中文、图片到 TXT/DOCX、PDF/OFD 文字/扫描混合页。
- 竖排中文使用 `chi_sim_vert` 和 PSM 5。当前本机 Tesseract 5.5.2 对“天地玄黄”的结果为“天地去重”，因此门禁明确设为字符召回率至少 50%，并继续保持 experimental，不把“能运行”描述为逐字准确。
- OCR CI 不继承 Runner 偶然存在的依赖：任务显式安装 `eng`、`chi_sim`、`chi_sim_vert` 和 Noto CJK 字体，先硬校验 `tesseract --list-langs`，再验证实际路线注册。Release 流程还会为 Linux、macOS、Windows 生成 `app/ocr`，安装包冒烟测试要求健康接口报告 `ocr.bundled=true`。

> 历史记录：本报告记录 2026-08-08 的页面图层 PDF -> DOCX 基线。2026-08-09 默认路线已改为可编辑文字并严格拒绝扫描页；当前验收标准与最新结果以 `docs/quality-standard.md` 和重新运行 `qa-samples/run_qa.py` 生成的报告为准。

## 2026-08-08 开源转换平台 QA

本轮测试使用 `qa-samples/run_qa.py` 启动本地 Spring Boot JAR，通过 HTTP 创建任务、下载结果，并按路线目标执行像素、内嵌页面、规范化内容或数据级严格检查，同时单独记录跨引擎二次渲染差异。

执行命令：

```bash
mvn -Dskip.frontend=true test
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

结果：

- Java 单元/集成测试：49 个，通过 49 个，失败 0。
- Spring Boot 可执行 JAR：生成成功。
- 前端生产构建：通过并打入 JAR。
- QA 样本总数：13。
- 路线级严格通过：13。
- 路线级严格失败：0。
- 二次渲染视觉阈值通过：6。

严格通过路线：

- `DOCX -> PDF`
- `XLSX -> PDF`
- `PPTX -> PDF`
- `WPS -> DOCX`（规范化文本一致）
- `ET -> XLSX`（首个工作表数据一致）
- `DPS -> PPTX`
- `UOF -> DOCX`（内嵌页面像素一致）
- `PDF -> PNG`
- `PDF -> JPEG`（平均绝对误差在阈值内）
- `PDF -> DOCX`（内嵌页面像素一致）
- `PNG -> PDF`
- `CSV -> XLSX -> CSV`
- `OFD -> DOCX`（文字字符及数量一致，561/561）

仍需关注的二次渲染差异：

- `WPS -> DOCX`：页数一致，视觉差异 `0.00375444`。
- `ET -> XLSX`：页数一致，视觉差异 `0.00434043`。
- `UOF -> DOCX`：内嵌页面严格一致，经 LibreOffice 二次渲染后的视觉差异 `0.08600494`。
- `PDF -> DOCX`：内嵌页面严格一致，经 LibreOffice 二次渲染后的视觉差异 `0.00214321`。
- `PDF -> JPEG`：平均绝对误差通过有损质量阈值，差异像素比例 `0.00172693`。

修复内容包括：PDFBox 渲染兜底、混合页面尺寸 PDF 的逐页 DOCX 分节、输出文件结构校验、OFD 递归/路径复杂度限制、转换器 `Error` 失败落盘、OFD 表格文字不再从 TXT 静默丢失，以及签章渲染组件不兼容时的显式警告。结论：本轮样本的路线级目标全部通过，但公开样本规模和跨平台覆盖仍不足，不能宣称任意文件、任意环境都达到 100% 保真。质量等级详见 `docs/quality-standard.md`。

## 自动化范围

- 中间模型：矩形交集、合并和非法尺寸。
- 安全解压：最小容器、Zip Slip、DOCTYPE/外部实体拒绝。
- 表格：2×2 网格、文字分配、横向/纵向合并、小断口修复、同列对齐归一化。
- DOCX：生成后重新打开，检查真实段落、真实表格、`gridSpan`、纵向 `vMerge` 和单元格内多段落统一对齐。
- 任务服务：损坏文件失败不导致服务退出，任务删除生效。
- 任务服务：转换器抛出 `Error` 时任务会进入失败态，不会永久停留在 `CONVERTING`。
- Worker 隔离：真实独立 JVM 转换成功、无响应崩溃识别、硬超时和子进程终止。
- 发布包：jlink 内置 Runtime 启动主服务后，再由同一 Runtime 启动 Worker，`TXT -> DOCX` 转换、下载和内容校验通过。
- PDF：无 Poppler 时使用 PDFBox 兜底；混合页面尺寸按页写入独立 Word 分节。
- OFD 文本：正文、表格和浮动文字全部参与输出，不因段落分析结果而漏字。
- Web：Spring Boot 上下文和 `/api/health`。

最终测试命令：

```bash
mvn clean verify
```

## 2026-08-04 执行结果

- Java 单元/集成测试：20 个，通过 20 个，失败 0，错误 0，跳过 0。
- Vue 生产构建：通过；静态资源已打入 Spring Boot JAR。
- Spring Boot 可执行 JAR：生成成功，约 54 MB。
- 启动冒烟：`GET /` 返回 Vue 首页；`GET /api/health` 返回 `UP`。
- 异常文件冒烟：伪造 `.ofd` 被异步任务识别为 `INVALID_OFD_MAGIC`，服务随后仍为 `UP`，任务删除返回 HTTP 204。
- 多页解析：用 OFDRW 生成两页、不同方向的真实 OFD 容器，确认解析器返回全部页面及对应文字。
- 多页 DOCX：确认每页文字均可重新提取，不同页面尺寸使用独立 `sectPr` 分节。
- 完整链路：三页 OFD 经任务服务转换后生成一个 DOCX，三页正文均存在，API 返回 `pageCount: 3`。
- 同行重建：使用真实样本坐标验证同基线标题片段、编号和正文被合并到同一个 Word 段落，不再逐 TextObject 强制换行。
- 表格对齐：同一列使用整列投票确定水平对齐；多行表头和单元格内全部段落服从单元格对齐，避免同列文字左右漂移。
- 视觉复验：真实三页询价单转换结果逐页渲染检查，正文无错行，物品名称列和多行表头对齐一致。

本轮因前端已先完成生产构建，Java 全量复验使用：

```bash
mvn clean verify -Dskip.frontend=true
```

标准的 `mvn clean verify` 仍是源码包的一键构建入口；首次运行会由 Maven 前端插件下载隔离的 Node/npm 并执行 `npm ci`、`npm run build`。

## 仍需扩大样本验收

已完成一个用户真实三页 OFD 样本的转换与逐页视觉复验；单一样本仍不能替代以下兼容性和环境验收：

- 不同 OFD 生成厂商的 TextCode、CTM 和字体子集；
- Word/WPS 双端的页面视觉对比；
- 规则表格准确率统计；
- 麒麟/统信 x86_64 和 ARM64 真机运行；
- 数字签章、复杂背景和跨页表格。
