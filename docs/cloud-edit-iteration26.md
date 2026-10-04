# 扫描 Word 金额编辑：可视遮罩修复，API 重读仍未解决

本批从 `ea5467d5f11748c6a4246abaeba5fa04ed62dbab` 继续。修复纯扫描页经实际 LibreOffice 导出时的底图文字重影；**没有通过完整“编辑→Office PDF→API TXT”验收**。API 仍可能输出旧、新金额；不能把接口成功、全文 CER 为零或高置信度当作数字正确。

## 根因与最小改动

原失败 `127.50→128.75` 的 DOCX 含九个合法白色逐词遮罩，几何和纸色采样正确。Office PDF 的实际绘制顺序却是“九个遮罩→整页扫描图→原生文字”：负层级 VML 遮罩被 DrawingML 扫描底图盖住，修改前已存在重影。根因是两类 Word 图形的层级互操作，不是此次编辑改变坐标、扩大框或采样误判。

仅在单张扫描底图、全部文字为 OCR、无白色 OCR 前景、无原生段落/表格/矢量线/其他图片的页面，将已有采样通过的逐词 VML 遮罩放到前景层级 1，OCR 文字框至少在其上一层。实际导出顺序变为“底图→遮罩→文字”。原始扫描图、0.15 mm 词级抗锯齿边缘、纸色/阴影估计、彩色标记拒绝、低置信度处理、OCR 数字保护与时限不变。混合页保留原层级，未声明修复其编辑能力。

对照中，换成负层级 DrawingML 遮罩仍失败，未采用。另一个只渲染可见背景的实验仍含 2078 个非白像素（最低通道 239），没有据此放宽亮度阈值或跳过 OCR 完整性检查。

## 实际编辑与独立真值

原始失败的 Office 全页可视图经固定 bundled Tesseract 重新识别：全文 CER **24.5283%→0%**，金额裁剪 OCR **123.39→128.75**，原生 PDF 文字前后均为 128.75。API 输出前后仍同时含 **127.50 和 128.75**，CER 仍为 **52.8302%**。原失败 DOCX/PDF/TXT 未覆盖或重跑；新 JAR 对该原始扫描 PDF 完成四个 HTTP 请求。

七个冻结的新输入保留小数、负号、前导零、缩短/加长金额、灰底、渐变阴影和红色注记。每例实际 PNG→PDF→扫描 DOCX→Office PDF、只改一个 w:t 后再导出 PDF→API TXT；修复前后各 35 个认证 HTTP/独立 JVM Worker 请求，均为 API SUCCESS。

| 对照 | 原金额→新金额 | 可视全文 CER 前→后 | 修复后金额裁剪 OCR |
| --- | --- | --- | --- |
| 白纸小数 | 312.40→319.65 | 7.9365%→0 | 319.65 |
| 负号 | -48.20→-49.70 | 9.5238%→0 | -49.70 |
| 前导零 | 00412.30→00419.80 | 7.6923%→0 | 00419.80 |
| 灰纸缩短 | 9827.50→27.50 | 16.1290%→0 | 27.50 |
| 灰纸加长 | 7.50→187.50 | 14.2857%→0 | **187.5 换行 0；数字边界失败** |
| 阴影 | -062.40→-068.95 | 15.6250%→0 | -068.95 |
| 不确定红色注记 | 542.10→542.70 | 7.9365%→0 | 542.70；仍不承诺一般编辑能力 |

CER 忽略空白、统一大小写，但保留标点；另外独立检查原始数字词法边界，因此加长金额不会因 CER 为零被计作编辑通过。七例 API 数字验收全部仍失败。API 从保留的底层扫描图提取 OCR 输入，而非 Office 可见合成页面；本批未修改该独立问题或放宽去重/完整性规则。

逐例验证：原 PNG 像素保留在输入 PDF；DOCX 扫描底图与 PDFBox 300 DPI 渲染像素一致；前后 DOCX 媒体字节相同，除遮罩层级外 XML 几何/文字相同；Office 原生文字框相同；修改仅改变一个指定文字节点，所有其他包部件不变；金额编辑区域外像素不变；修复前后所有采样遮罩区域外像素不变（栅格边缘容许 2 px）。未知区域和红色注记未因本次层级修复被扩大遮盖。

最初额外完成的 28 次 PNG 直接转 Word 控制没有扫描底图，不能验收遮罩。本批明确保留这些结果并换用正确路线，没有把它们充作修复证据。

## 构建、资源与边界

显式设置 bundled app-home 和 Office 后，运行 `qa-samples/accept_cloud_build.sh`：重新构建前端及 Maven clean package；**453 项测试，452 通过，0 失败/错误，1 个缺少可选真实签章 OFD 样本的跳过**。全部原有 bundled OCR 方法实际执行。新增实际 Office 测试把扫描金额 127.50 改为 1，确认尾部旧笔画消失、框外红注记可见；原扫描底图/未知内容测试通过。首次专项的红色精确值断言遇到 Office 255→254 色彩转换，保留该失败日志后将该颜色断言改为窄范围；白色旧笔画消除断言未放宽。

JAR SHA-256：`f4960b0dc8789f3865101bdbb86a4c797bd1e4bd016b91f373db916db61c9871`。构建输入指纹：`208fc9c414e4dc2be5b268c97d53c6a99d3884b47199641d6ca66a50c72e148f`。226 个打包应用类与 fresh target 相符；仅 FixedLayoutDocxRenderer 及其编译附属类改变；七个前端文件与打包内容一致。

两次 35 请求矩阵：墙钟 **132.22→97.21 s**，子进程 CPU **357.80→274.11 s**，最大子进程 RSS **342852→345600 KiB**。仅各一次测量，缓存/负载可能影响结果，**不宣称性能提升**。70 个 Worker 均已退出，两个监督器 ECHILD、无新增僵尸。

原故障四请求的全局进程观察器曾看到同时运行的只读 PDFBox 校验子进程退出瞬间，故原报告仍标为 failed。该 PID/startTicks 与 PDFBox 回收凭据一致（exit 0、ECHILD），四个 API Worker 均已退出；最终仍仅有原有 17 个历史僵尸，未重跑请求或篡改原报告。详见验证 JSON 的独立核实记录。

固定环境：Temurin 17.0.16+8、Maven 3.9.11、Node 22.17.0、Python 3.12.14、PyMuPDF 1.26.6；LibreOfficeDev 26.8.0.0.alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`。Bundled Tesseract 5.5.2、Leptonica 1.87.0、libpng 1.6.57、zlib 1.3.1，tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd`；Liberation Sans 2.1.5、Noto CJK 2.004。完整运行时/模型/字体哈希和辅助脚本来源见验证 JSON。系统 OCR CI 不能代替本次 bundled 验收。

本批未复跑旧的 39 focused/53 CLI 矩阵；未验证原生 Microsoft Word、macOS/Windows 安装包、任意用户扩框或复杂混合扫描件。加长文字仍受固定框限制；不确定纸色及彩色注记不保证可编辑擦除。稀疏数字 OFD 的 `OCR_NO_NEW_TEXT` 严格失败仍排在本批之后，未并行修改。未 merge/release、访问 Mac 或重试 Library。

## CI 兼容性门禁与最终产物衔接

候选 `096f0eda722c626e9680268846979c19c9ee054f` 的 CI `37207251967`：主构建和 Tesseract 通过，但 LibreOffice 24.2.7 作业 `111450914787` 的原有深色纸面白字可见性断言失败；新增金额编辑测试通过。云端 26.8 对同一旧断言通过，不能用云端结果覆盖 CI 失败。

因此最终实现先收集同页采样结果；只要一个采样词使用白色前景，**整页保留原有负层级遮罩**。复用原有纸色/前景判定，没有引入放宽的纸色阈值；旧深色纸面断言原样保留，单元测试新增该回退的层级约束。这是保守缩小修复范围，不宣称修复 Office 版本间所有绘制差异。最终源码重新 clean package，仍为 453 项、452 通过、1 个可选签章样本跳过。

前表及 35+35 请求是候选的完整矩阵，原数据不覆盖。最终 JAR 另执行七个冻结扫描 PDF→DOCX，以及原故障完整四请求，共 11 请求；逐 ZIP 部件比较七个最终 DOCX 与候选，只有创建/修改时间可忽略，其余元数据、XML、关系、字体、媒体均必须一致，才衔接原有 Office/OCR 测量。原故障最终 Office PDF 的 300 DPI 可视像素和原生文字框也必须与候选一致，API 错误文本字节一致；不会用候选 JAR 的结果冒称最终完整矩阵重跑。核实结果见 [最终产物对照](cloud-edit-final26-results.json)。

## 复现与证据

新环境有固定字体/Pillow、已准备的 bundled OCR 和 Linux Office 后，依次运行：

```sh
python3 qa-samples/generate_edit_direct26.py
python3 qa-samples/generate_edit_iteration26.py --via-pdf
python3 qa-samples/run_edit_iteration26.py --jar web-api/target/web-api-0.1.5.jar \
  --corpus qa-samples/generated/edit-iteration26-pdf --out qa-samples/report/iteration26-scan-after --keep-going
python3 qa-samples/verify_edit_iteration26.py --report qa-samples/report/iteration26-scan-after \
  --corpus qa-samples/generated/edit-iteration26-pdf --out qa-samples/work/iteration26-scan-after-visible
```

脚本拒绝覆盖已有输出。比较器读取保留的 before/after 产物；原故障验证器读取 iteration25 的原始失败产物。所有转换仍由认证 HTTP、独立 Worker 和真实 Office 完成；可视 OCR 单独读取合成页面。

- [七个独立样本逐项测量](cloud-edit-iteration26-results.json)
- [原始故障前后测量](cloud-edit-original26-results.json)
- [构建、运行时、真值、辅助脚本和清理凭据](cloud-edit-iteration26-validation.json)

最终 guard 验证：保留此前冻结输入和报告，运行 `generate_edit_final26.py`，以 `--corpus qa-samples/generated/edit-iteration26-final --out qa-samples/report/iteration26-final` 调用相同 HTTP runner，然后运行 `verify_edit_final26.py`。不覆盖候选产物。
