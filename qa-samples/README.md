# QA Samples

本目录用于端到端转换 QA。

## 目录约定

- `input/`：可公开分发或已脱敏的测试样本。
- `run_qa.py`：启动本地服务、上传样本、下载结果并做视觉/数据对比。
- `output/`：本地转换结果，已在 `.gitignore` 中忽略。
- `work/`：本地渲染和中间文件，已忽略。
- `runtime-data/`：测试服务运行数据，已忽略。
- `report/`：本地 QA 报告和差异图，已忽略。

## 运行方式

```bash
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

QA 脚本需要本机具备：

- Java 17
- LibreOffice 或 `soffice`
- Poppler `pdftoppm`
- Python Pillow

`strictPass` 使用路线级判定：直接保真路线比较渲染像素，页面图层 DOCX 比较内嵌页面像素，可编辑文档比较规范化内容，OFD 还会检查 TXT 与 DOCX 中非空白字符及出现次数守恒，表格比较数据，JPEG 使用有损误差上限。`visualPass` 单独表示当前 LibreOffice/Poppler 环境中的二次渲染差异是否低于参考阈值。

服务启动前会把可执行 JAR 复制并校验到 `runtime-data/`。因此并行 Maven 构建即使替换 `web-api/target` 下的 JAR，也不会破坏正在运行的 QA 服务。

## 样本提交要求

提交到 `input/` 的样本必须满足：

- 来源清楚，允许公开分发；
- 不包含个人隐私、公司秘密、真实印章密钥或未脱敏合同；
- 文件尽量小；
- 能代表一个明确场景或失败用例。

不要提交本地输出、差异图和运行数据。
