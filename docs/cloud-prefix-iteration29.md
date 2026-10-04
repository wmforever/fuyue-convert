# 稀疏 OFD 复核与命名空间前缀丢失修复

父提交 `f4a2649eb26085da465d66683abda51bfa369dc2` 的数字编辑修复已通过三项 CI 后，继续有限范围处理稀疏 OFD。未降低扫描判定阈值、放宽 `OCR_NO_NEW_TEXT`、信任自产标记或按高置信度推断完整性。

## 两个不同问题

既有 TXT→DOCX→Office PDF→OFD 原文件 SHA-256 为 `d10ea3329fab62a3af1c58da09750fac259c19d5bb5fb355c1d17ed1f66a932a`。原生 XML 含完整四行双语正文和 `127.50`，但少量原生文字不足扫描覆盖门槛；整页保真图 OCR 与原生文字重复，仍严格返回 `OCR_NO_NEW_TEXT`。当前最终 JAR 再次复现 TXT 和 Word 两条失败，且不可下载。**原始组合链路仍未通过验收。** 要放行需另有可靠内容完整性证据；重复识别、高置信度或“本工具生成”不能提供该证明。

为隔离两层，先生成仅原生层、仅图像层、删除原生金额但保留图像三项控制。Python XML 序列化将页面前缀变成 `ns0:`，这三项意外返回 SUCCESS、空 TXT、无警告：内容 CER 100%、字符召回 0、数字全部丢失。保留初始失败结果于 [基线记录](cloud-sparse-ofd29-results.json)，没有把 API 成功当作内容通过。

只在三个冻结控制内把字面 `ns0:` 改回 `ofd:`，保持命名空间 URI、元素、文本、属性、资源和绘制次序相同，父 JAR 即返回完整、逐字匹配的双语正文。原文件未重复执行。ZIP 内其他条目逐字节一致、页面 XML 按命名空间语义比较一致。

## 根因和有界修复

直接检查实际 OFDRW 2.3.9 字节码：`PageBlockType.getInstance` 以 `Element.getQualifiedName()` 分派，只列出 `ofd:TextObject`/`TextObject` 等字面名称；`CT_PageBlock.getPageBlocks()` 将无法分派的对象静默跳过。正确 URI 的其他前缀会把嵌套正文/图像整组丢掉，最终看起来像空白页。

`OfdrwParser` 现在对页面 Layer 和嵌套 PageBlock 逐层处理：只在读取器内存中将正确 `http://www.ofdspec.org/2016` URI 的对象前缀规范为库可识别的名称，再交给原有解析逻辑。不改源 ZIP、磁盘 XML、命名空间 URI、文字、坐标、对象顺序或 OCR 数字规则。保留既有无命名空间兼容；错误 URI 的已知页面对象明确返回 `OFD_UNSUPPORTED_NAMESPACE`，避免静默空页。子对象数受 ParseLimits 限制，原有总对象/深度保护仍适用，不复制整棵嵌套子树。

解析专项 **7 项通过**，新用例覆盖两个别名前缀、默认命名空间、嵌套带符号/前导零文字、矢量路径、真实空白页、源文件字节不变，以及错误 URI 拒绝。测试比较完整 PageModel，不只检查非空。

## 最终验收

[HTTP 结果](cloud-prefix-iteration29-results.json)，[构建和版本绑定](cloud-prefix-iteration29-validation.json)。

- Maven 全量 **471 项：470 通过、1 跳过、0 失败/错误**。唯一跳过为未提供的可选真实签名 OFD，固定 OCR 测试均执行通过。前端重建 7 文件与 JAR 内资源完全一致。
- 最终 JAR SHA-256：`eb46974700a79e276ad2831be3c8006e60d4bca556a0a7697cb4787ca58ecf09`。229 个应用类与 fresh target 逐一匹配；源码输入指纹 `0474de63ffeea08c27831a7a92ebed2199fc113ba4424f60f9b5791a8689cfec`。相对已验收 iteration28，仅 OfdrwParser 类族改变，所有其他应用类逐字节一致，包括金额可见性和 Word 宽度修复。
- 最终 **8 次真实 HTTP**：三个别名前缀控制由空成功恢复完整文本（CER **100%→0**，字符召回 **0→100%**，数值词精确）；三个标准前缀控制逐字节不变；原始稀疏文件两次预期 `OCR_NO_NEW_TEXT` 且无下载。8 个独立 Worker 全退出，ECHILD，无新僵尸。
- 最终 8 请求墙钟 **22.765 s**、子进程 user/system CPU **61.347/5.128 s**、最大子进程 RSS **297,932 KiB**。前后请求组合不同，不声称性能改善。
- 运行版本和固定引擎/模型/字体清单与 iteration27/28 相同，已复核 runtime 清单 SHA。云端 Linux 通过不代表旧安装器或原生 macOS/Windows 包验收。

复现：`verify_sparse_ofd29.py generate` 和父 JAR 保留初始 5 请求；`control_sparse_prefix29.py` 和父 JAR 仅运行前缀变化的 3 请求；`validate_sparse_prefix29.py generate` 冻结最终 8 请求，再用通用 `run_edit_iteration26.py` 和最终 JAR 运行到 `qa-samples/report/iteration29-after`，最后 `validate_sparse_prefix29.py verify`。私有任务/服务器数据、认证令牌和原始样本不进入 Git。初始空成功、标准前缀控制、最终修复和严格失败均保留，不覆盖、不重跑已完成矩阵。

## 剩余边界

页面对象前缀兼容并不证明所有 OFD 扩展、签名或加密内容均已支持。原始稀疏数字保真 OFD 的完整性失败仍待独立解决。英文标题固定框换行/读取顺序、任意更长金额编辑、复杂 PDF 多图/旋转/Form 可见性也未在本轮解决。没有合并、发布或接触 Mac；交付仍是同一草稿 PR 和云端审阅构建。
