# 第一阶段测试报告

## 2026-08-08 开源转换平台 QA

本轮测试使用 `qa-samples/run_qa.py` 启动本地 Spring Boot JAR，通过 HTTP 创建任务、下载结果，并用 LibreOffice/Poppler 渲染后做像素级或数据级对比。

执行命令：

```bash
mvn -Dskip.frontend=true test
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

结果：

- Java 单元/集成测试：通过。
- Spring Boot 可执行 JAR：生成成功。
- 前端生产构建：通过并打入 JAR。
- QA 样本总数：11。
- 严格通过：7。
- 严格失败：4。

严格通过路线：

- `DOCX -> PDF`
- `XLSX -> PDF`
- `PPTX -> PDF`
- `DPS -> PPTX`
- `PDF -> PNG`
- `PNG -> PDF`
- `CSV -> XLSX -> CSV`

严格失败但可运行路线：

- `WPS -> DOCX`：页数一致，视觉差异 `0.00375444`。
- `ET -> XLSX`：页数一致，视觉差异 `0.00434043`。
- `UOF -> DOCX`：页数已从不一致修复为一致，当前视觉差异 `0.08600494`。
- `PDF -> DOCX`：页数一致，视觉差异 `0.00214321`。

结论：当前项目适合作为开源转换平台基础版。已具备可扩展转换器、端到端 QA 和失败透明能力，但不能宣称所有路线达到生产级严格保真。质量等级详见 `docs/quality-standard.md`。

## 自动化范围

- 中间模型：矩形交集、合并和非法尺寸。
- 安全解压：最小容器、Zip Slip、DOCTYPE/外部实体拒绝。
- 表格：2×2 网格、文字分配、横向/纵向合并、小断口修复、同列对齐归一化。
- DOCX：生成后重新打开，检查真实段落、真实表格、`gridSpan`、纵向 `vMerge` 和单元格内多段落统一对齐。
- 任务服务：损坏文件失败不导致服务退出，任务删除生效。
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
