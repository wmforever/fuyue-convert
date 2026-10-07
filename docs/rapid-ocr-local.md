# 本地 RapidOCR 实验路线

参考 [RapidOCR](https://github.com/RapidAI/RapidOCR) 的本地 ONNX 推理接口，新增可选 PDF OCR 路线。它使用 PP-OCRv6 的中文和英文模型，保留检测出的独立文本行框。默认仍使用既有 Tesseract；本实验未自动下载运行时、未替换安装软件，也未调用云端识别。

## 准备和启用

使用独立 Python 3.12 环境安装 `scripts/requirements-rapid-ocr.txt`。本机验证版本为 RapidOCR 3.9.2、ONNX Runtime 1.23.2。官方模型清单见 [RapidOCR default_models.yaml](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/default_models.yaml)。单独准备该版本的三个公开 ONNX 模型：

- `PP-OCRv6_det_small.onnx`
- `PP-OCRv6_rec_small.onnx`
- `ch_ppocr_mobile_v2.0_cls_mobile.onnx`

`scripts/rapid-ocr-sidecar.py` 校验文件及 SHA-256 后，只使用已存在的本地模型。缺少模型或版本不匹配直接失败；转换时不下载模型、不上传图像、不切换到另一引擎。

启动 Java 后端时显式设置以下系统属性，三个路径均使用绝对路径：

```text
-Dformatconverter.ocr.rapid=true
-Dformatconverter.ocr.rapid.python=/path/to/isolated/python
-Dformatconverter.ocr.rapid.script=/path/to/scripts/rapid-ocr-sidecar.py
-Dformatconverter.ocr.rapid.models=/path/to/offline-models
```

目前这些属性用于本地实验，尚未提供普通用户的界面设置或可分发的 Python 打包。既有 OCR 能力探测和像素限制仍依赖 Tesseract 配置；将它改成独立的多引擎能力探测属于后续产品化工作。

## 转换边界

PDF 路线沿用原有页面渲染、图像/文档限制和跨进程 OCR 并发许可。RapidOCR 子进程与许可等待共用页面超时，输出文件先清除，避免复用失败前的旧识别。JSON 结构、坐标、置信度和条目数均验证；原扫描像素保持完整。不可用的引擎或无效输出返回明确错误，不伪装成转换成功。

该路线自动启用保真叠加检查：可以安全覆盖原扫描文字的识别行成为可编辑文本框；其余区域保留原图并返回 `OCR_REGION_RETAINED_AS_IMAGE`。模型置信度低于 0.85、旋转超过 2°，以及覆盖到原图长连线的检测区域也保留扫描内容。任务与 Word 属性明确说明部分区域不可直接编辑，不包含隐藏的错误 OCR 副本。

模型分数与 Tesseract 分数不能直接比较，也不是字符正确率。印章、灰底表头、屏幕截图及少数正文可能仍为图像。当前路线没有生成原生 Word 表格；可编辑文字采用定位文本框。仍需对照原文件复核数字、标点、遗漏和不同 Office 阅读器的显示。
