# PDF 金额重读：按实际遮罩排除隐藏的底图 OCR

父提交为 `6d163a92806253a2a2895c2dee7088d0e4fd3bba`，沿用 `improve/cloud-ocr-completeness-20261003` 和草稿 PR #1。本轮仅改变 PDF→TXT 的混合原生文字/扫描图合并；Word、OFD、OCR 识别阈值及去重数字规则不变。

## 复现与根因

原始 `127.50→128.75` 编辑 DOCX 的 `w:t`、实际 Office PDF 原生文字均只有 `128.75`；同一 PDF 内保留的原扫描图单独送入固定 OCR 后仍识别为 `127.50`。PDF 绘制顺序为原图、逐词不透明遮罩、可编辑新文字，9 个底图 OCR 词框均被后绘制遮罩完全覆盖。API 原实现仍把原图提取为 PNG，按原图运行 OCR，再与原生文字去重；不同金额不能判为重复，因此同时输出旧、新金额。这是可见性丢失，不是任务缓存或 DOCX 未保存。

原始 Office PDF SHA-256：`ea58c03d0ad977f2ff397c281e237fbfff1409f325496784fef7e2978fbbebeb`。提取 JPEG SHA-256：`a387a56635dc0982d30175ebeffe14a910b75e2e75eec7ddf037720af602271a`。首次直接 JPEG CLI 调用失败：固定运行时不含 JPEG 解码；保留该失败，再用 PNG 解码副本验证。生产已有的 JPEG→PNG 归一化路径未改变。

## 有界实现

`PdfOcrVisibility` 用 PDFBox 3.0.8 读取实际绘制顺序、变换、裁剪和图形状态。仅对单张轴对齐图像、未旋转且 UserUnit=1 的页面建立与原始 OCR 坐标的一一对应；没有移动/重采样源图或修改词框。Office 原图略超出页边时，逐词核对实际裁剪范围。

只将**后绘制、普通混合、无透明/软遮罩/叠印的矩形实色填充**计为确定覆盖，支持多个相邻矩形联合覆盖。OCR 一整行的每个词都完全被覆盖才移除该行；部分词、部分字符、透明或不明确重叠返回 `OCR_VISIBILITY_UNCERTAIN`，不发布可下载的可能含旧值结果。不依据高置信度、金额近似或自产文件标记忽略数字冲突。保留原生文字及不受遮罩影响的完整 OCR 行；原有 `OCR_NO_NEW_TEXT` 只在已证明该图的所有识别行均被覆盖时得到有证据的例外。

成功排除隐藏词时返回 `OCR_OCCLUDED_TEXT_IGNORED`，数量为底图 OCR 词数；该警告不证明其他内容完整。分析最多 20,000 操作（也受 ParseLimits 限制）、512 次填充、250 ms；超限严格失败，原有 OCR/Worker 超时不变。复杂图形、旋转、多图、嵌套 Form 不属于已证明范围，不宣称已普遍解决旧文字问题。

## 冻结证据和复现

先完成独立 5 个金额样本的 25 次父构建 HTTP 基线，再冻结 10 次补充基线控制；不重复之前的 iteration26 可视 OCR 矩阵。生成器、HTTP runner 与验证器均进入仓库：

```bash
python3 qa-samples/generate_edit_iteration27.py
# 使用父构建 JAR 和固定运行时生成 iteration27-before（25 次）。
python3 qa-samples/run_edit_iteration26.py --jar PARENT.jar \
  --corpus qa-samples/generated/edit-iteration27 \
  --out qa-samples/report/iteration27-before --keep-going
# 还需保留 iteration26-final 的原始编辑 DOCX/PDF/TXT。
python3 qa-samples/generate_visibility_iteration27.py
python3 qa-samples/run_edit_iteration26.py --jar PARENT.jar \
  --corpus qa-samples/generated/visibility-iteration27/before \
  --out qa-samples/report/iteration27-controls-before --keep-going
python3 qa-samples/record_cloud_provenance.py --record BUILD.json --before
bash qa-samples/accept_cloud_build.sh
python3 qa-samples/record_cloud_provenance.py --record BUILD.json --after
python3 qa-samples/run_edit_iteration26.py --jar web-api/target/web-api-0.1.5.jar \
  --corpus qa-samples/generated/visibility-iteration27 \
  --out qa-samples/report/iteration27-after --keep-going
python3 qa-samples/verify_visibility_iteration27.py
```

设置 `FORMAT_CONVERTER_APP_HOME` 指向准备好的固定 OCR、`FORMAT_CONVERTER_OFFICE_BINARY` 指向实际 Linux Office；每个输出目录只能创建一次。HTTP 使用本机随机认证令牌、独立 JVM Worker、实际下载与进程回收证明。生成器不访问网络，不含真实用户资料；金额/日期为合成真值。`generate_edit_iteration27.py` 保留冻结时的代码字节和 SHA（包含继承但本轮未选用的阴影/注记生成分支）。

原始图片、Office 文件、私有服务目录和原始日志不提交 Git。机器可读公开结果见 [本轮实测](cloud-visibility-iteration27-results.json)；构建计数、版本和 JAR 绑定见 [验证记录](cloud-visibility-iteration27-validation.json)。CER 忽略大小写/空白，**另外**严格比较带符号、小数点、日期分隔及前导零的数值词，不能用去空白掩盖金额换行。

## 最终实测

- Maven 全量 **466 项：465 通过、1 跳过、0 失败/错误**；唯一跳过为未提供真实签名 OFD 的可选用例，所有固定 OCR 用例执行通过。13 项新增可见性用例通过。前端重新构建的 7 个文件与 JAR 内静态资源完全一致。
- 最终 JAR SHA-256：`84558b3f3e9c70cde80e54f6a9bebbd650b3959b16d966dbf1c25ecc362b7041`，228 个应用类与 fresh target 一致；构建输入指纹 `339ab86a60c87aa71ad173dc285a2dd7732793e2cdb386ac9f31081b590e6ed3`。
- 最终 HTTP **20 次：18 成功，2 个预期 `OCR_VISIBILITY_UNCERTAIN` 且不可下载**；20 个独立 Worker 均退出，subreaper 确认 ECHILD，无新僵尸。后续验证器只读取这些冻结产物，没有重跑请求。
- 原始 `127.50→128.75` API CER **52.8302%→0**，输出仅新金额一次。四个独立白/灰纸金额（小数、负数、前导零、灰底）及各自未编辑对照 CER **81.9672%–82.8125%→0**；暗底编辑/未编辑 API 均 **0→0** 且逐字节不变。全部 11 项数值词完全匹配真值；改善是去掉错误插入的隐藏文字，并非证明 OCR 没有漏识别。
- 普通扫描和图像前底色两项 TXT 逐字节不变；完全遮蔽控制只输出 `Visible replacement 128.75`。白/灰/暗三份 Word 除 core 时间戳外每个部件逐字节一致，包含原扫描图和全部可编辑文字。原始编辑 DOCX 经新 JAR/Office 输出的 PDF，与已验收版本的 300 DPI 像素、原生词框完全相同。
- 同一冻结编辑 PDF→TXT 单次配对耗时（秒）：小数 **2.714→2.888**、负数 **2.052→2.156**、前导零 **2.693→2.698**、灰底 **2.469→2.354**、暗底 **1.600→2.252**。各一次、含 JVM 启动，不能认定性能提升。最终 20 请求墙钟 **62.738 s**、子进程 user/system CPU **167.211/12.810 s**、最大子进程 RSS **345,192 KiB**；不同请求组合的整体资源不直接比较。

运行版本：Temurin 17.0.16+8、Maven 3.9.11、Node 22.17.0、Python 3.12.14、PyMuPDF 1.26.6、PDFBox 3.0.8、Tesseract 5.5.2/Leptonica 1.87.0、固定 fast 模型提交 `87416418657359cb625c412a48b6e1d6d41c29bd`、Liberation Sans 2.1.5、Noto CJK 2.004、LibreOfficeDev 26.8.0.0.alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`。固定运行时清单和字体 SHA 均复核，系统 OCR 不能替代该验收。

## 保留的限制

- 深色纸面沿用已有 Word 保守层级；API 对照不变不能证明深色 Word 任意编辑可视正确。
- 加长金额在固定框中换行、稀疏 PDF→OFD 后的 `OCR_NO_NEW_TEXT` 分别排队处理，本轮不放宽保护规则。
- 复杂多图/旋转/Form、原生 PDF 隐藏文字的一般可见性、低置信度或完全漏识别字迹未解决。Word 布局、置信度与全文完整性仍需分别复核。
- 只有 Linux 云端构建和固定 OCR 验收；旧安装器、原生 macOS/Windows 包未验收。可选真实签名 OFD 未提供，不将跳过当作通过。
