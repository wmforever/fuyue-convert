# 灰度/RGB 输入表示审计43

生产起点 `49ea8f8f1876d155010c4e1af5e0d06a03fc05dc`，同一 `wmforever/fuyue-convert` 分支及草稿 PR #1。本批仅增加 QA 工具和证据，生产源码、JAR、OCR 模型、重试条件、数值保护、遮罩和超时不变。结论为**没有可采用的生产收益**，结束本次编码假设，不据此增加增益、角度、PSM 或尺度扫描。

## 等价定义及冻结输入

复用41的八个1600×1300、300DPI冻结 PNG 和原始文字真值；没有重新绘字、换字体、缩放或重复41 OCR。六个文字页涵盖中英文正常/渐变阴影/局部阴影，另有空白和稀疏噪点。数字包含 `00637`、`2091-04-26`、`-041.85`、`+12.50%`、`009.70`、`00846`、`73519`，逐字真值及字体位置由41原 manifest引用。输入色彩表示本身不能作为文字真值。

明确区分三个操作性定义：

* A：Pillow12.3解码后 RGB 样本字节完全相等。八对灰度/RGB PNG 满足此定义，尺寸和300DPI相同；均无 `iCCP/gAMA/sRGB/cHRM` 块。这不声称各解码器下的色度学解释相同。
* B：Java17 `ImageIO/getRGB` 后，按生产增强器的整数亮度及白底合成公式得到的字节完全相等。探针把该值写成不透明 RGB，再用 ImageIO 重读验证，而非只比较内存对象。
* C：原正常页的同一灰度通道加固定 `0/64/128/192/255` alpha，再按 `(g*a+255*(255-a)+127)//255` 显式合成白底。中英文各一对，四个输入。这些图的可见性被修改，**不沿用正常页 OCR 真值**，仅测试像素/增强透明路径。

再加入灰度/RGB两张256×128阶梯值诊断图，总计22个冻结输入。全部输入文件、PNG块、样本哈希、源真值引用和预算在 `qa-samples/generated/encoding43/expected.json`，该目录留在云端且不推送样本。

## 实测像素与有界 OCR

22个输入在定义B下的增强结果（含空白/噪点 null 回退）全部一致，透明合成两对在定义C下也一致。八对定义A图在 Java `getRGB` 下均不同：灰度被读为 `TYPE_BYTE_GRAY`/非sRGB色彩空间，RGB被读为 `TYPE_3BYTE_BGR`/sRGB。六个文字页的增强原始灰度输出不同；空白/噪点两对仍返回 null。诊断图样本128经 Java读取，灰度的 `getRGB` 为188，RGB仍为128。该差异是具体运行时的测量，不把灰度样本相等误称为 Java 色彩解释相等。[Java17官方文档说明 getRGB 返回默认sRGB并进行所需的色彩转换](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/java/awt/image/BufferedImage.html#getRGB(int,int))。

只有像素差异成立后，冻结中英文渐变阴影/正常页的 RGB原始和实际生产增强输出，执行**8次新 bundled OCR**（固定 `chi_sim+eng`/PSM3）。对应灰度原始/增强的8个TSV及完整模型复用41结果，校验文件哈希。四对原始 TSV逐字节相等，生产解析后的文本块、坐标、样式和置信度相等。没有重跑局部增益候选或旧矩阵。

| 输入 | 灰度原始CER | 灰度增强CER | RGB原始CER | RGB增强CER | 结论 |
| --- | ---: | ---: | ---: | ---: | --- |
| EN渐变阴影 | 50.6173% | 50.6173% | 50.6173% | 50.6173% | 四条淡字行仍遗漏；仅后三行完整 |
| ZH渐变阴影 | 60.9756% | 62.1951% | 60.9756% | 62.1951% | 四条淡字行仍遗漏；增强还把文字识别为广字 |
| EN正常 | 0% | 0% | 0% | 0% | 七行及原始数字保持正确 |
| ZH正常 | 0% | 14.6341% | 0% | 1.2195% | RGB增强恢复00846行，却新增+12.509%数字错误 |

正常中文 RGB 增强的 CER改善不能抵消新数字错误。对四个 RGB原始/增强结果，调用实际生产解析器、覆盖/几何检查和完整选择器；原始重试资格全部为false，完整选择全部不接受，部分选择实际调用0次，保留原始结果。中英文阴影漏字仍未解决，未获得任何实际选中文本的 CER、完整性或数值收益。

## 来源、成本和复现边界

运行时 Temurin17.0.16+8、Python3.12.14、Pillow12.3.0、NumPy2.3.5；固定 bundled Tesseract5.5.2/Leptonica1.87.0/libpng1.6.57，fast模型revision `87416418657359cb625c412a48b6e1d6d41c29bd`，12个payload重新校验。eng SHA256 `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`，chi_sim `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730`。LiberationSerif2.1.5和NotoSansCJK2.004字体/许可证/哈希沿用冻结41 manifest，详情见[结果JSON](cloud-encoding43-results.json)。没有系统OCR替代验收。

像素探针一条Java命令22输入/44次增强调用，wall16.633080秒、堆上限256MiB；Java总RSS未测量。8条native命令累计wall4.280470秒、user3.621838/system0.579520秒，最大单子进程RSS90852KiB；含四次Java选择器重放的整批wall8.337305秒，不是并发峰值或生产吞吐基准。单OCR限25秒/地址空间1GiB/RSS256MiB，整批180秒；像素探针外层限130秒。未发生预算超限；成功监督receipt全部exit0、ECHILD且登记PID身份消失。

JAR SHA256 `1ead3c12b621c612535ba7a9f338915484b79be3b74509cf3c3b8a304b2ce68b`，生产输入指纹 `07911c45cf0a510f7fa9f8ae279e977341c795e246c19711c3dce83aee95b198`，232类来源沿用40干净构建并对当前生产输入/JAR复核。40的完整本地测试505项/504通过/1可选签名OFD跳过，10个bundled条件测试实际执行；本批没有重跑旧全套构建。

唯一初始验证器错误为读取监督receipt不存在的 `remaining` 字段；修正为实际 `status/reaped`、ECHILD和PID身份检查后通过，初始源码/日志/exit1 receipt保留。修正仅重跑零OCR审计，没有重跑像素/识别命令。

有界复现须先有冻结41输入/结果及当前JAR。每个产物目录独占创建，勿重复活跃/已完成命令。依次运行 `freeze_encoding43.py`；编译 `OcrEncodingProbe43.java` 到独立QA classes（classpath是task-service/target/classes和既有Jackson依赖）；用256MiB堆对22输入运行探针；`measure_encoding43.py`；`verify_encoding43.py`。调用均置于现有 `iteration20-command.py` 监督器，保留完整命令和receipt。只有审计符合预算才使用其结果，不把候选直接纳入生产。

未运行：新HTTP/Word/Office/遮罩矩阵（生产artifact未变，没有宣称新增版面/编辑验收）；局部阴影、空白/噪点及透明控制的新OCR（仅像素检查）；ICC/gamma/16bit/索引色/彩色alpha/EXIF旋转；原生macOS/Windows包、Microsoft Word及签名fixture。原稀疏OFD、淡字覆盖漏判、小字CJK/‰、Word冲突叠印、旧扫描金额重读及mixed严格API问题仍列为未接受。

本批不调整颜色语义或保护阈值。编码敏感性已得到实证，但不是上述漏字的已证明修复；不能据此把Java色彩转换判为可安全替换的生产缺陷。
