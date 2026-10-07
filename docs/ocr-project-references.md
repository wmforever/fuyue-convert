# 扫描 PDF 到 Word 的项目参考与验证边界

本项目的重影来自识别、几何和背景覆盖共同作用，不能把“生成了 DOCX”或单元测试通过当作视觉验收。

参考资料：

- [RapidOCR](https://github.com/RapidAI/RapidOCR)提供本地 ONNX 中文和英文识别接口。本次真实扫描对照中，其独立文本行框比既有 Tesseract 路线更适合安全叠加；已接入[本地实验路线](rapid-ocr-local.md)，仍保留模型不确定或无法安全覆盖的原图区域。
- [PaddleOCR 表格识别](https://github.com/PaddlePaddle/PaddleOCR/blob/main/ppstructure/table/README.md)将表格区域、文本识别和单元格结构分开处理。
- [PaddleOCR Word 重建实现](https://github.com/PaddlePaddle/PaddleOCR/blob/main/ppstructure/recovery/recovery_to_doc.py)按区域类型生成标题、段落、图片和真实 Word 表格。它提供重建思路，不能证明任意扫描文件的版式可完全恢复。
- [OCRmyPDF 图像预处理](https://ocrmypdf.readthedocs.io/en/latest/cookbook.html#image-processing)区分用于 OCR 的清理副本和最终显示图像。保留源图有利于人工复核。
- [Tesseract 质量说明](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#tables-recognition)说明表格通常需要自定义分区和布局分析。
- [OpenCV 横竖线提取教程](https://docs.opencv.org/4.x/dd/dd7/tutorial_morph_lines_detection.html)提供分离长横线、长竖线的处理思路。
- [pdf2docx](https://github.com/ArtifexSoftware/pdf2docx)可作为 PDF 到 Word 的布局处理参考；其转换能力本身不能证明本项目的扫描识别问题已经解决。

据此实现的 `OcrTableLineRecovery` 是独立编写的局部实验，不引入以上项目的运行依赖或复制其代码。只有跨表格低置信异常词框触发检测；检测范围、像素、区域数量和时间受限。连续细横竖线及交点建立局部网格后，仅在 OCR 副本上清理框线和灰底。候选必须达到局部置信度增益、保留已有数字字面值和高置信文字；原扫描字节保留，区域外词框保留，替换候选写入诊断。

通过 `-Dformatconverter.ocr.table-line-recovery=true` 开启实验。默认关闭，直到真实扫描文件的区域边界、字段、数字及 Word 渲染均通过复核。置信度不等于正确率，新增字段仍可能误识别。

背景覆盖的扫描色边判断要求蓝色/互补黄色杂边紧邻深黑字迹，仍拒绝大块彩色区域和饱和标记。必须使用彩色印章、不同纸色、真实扫描及多个 Office 渲染器验证，不能把有限样本测试解释成所有标记均受保护。
