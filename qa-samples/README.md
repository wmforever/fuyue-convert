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

## 样本提交要求

提交到 `input/` 的样本必须满足：

- 来源清楚，允许公开分发；
- 不包含个人隐私、公司秘密、真实印章密钥或未脱敏合同；
- 文件尽量小；
- 能代表一个明确场景或失败用例。

不要提交本地输出、差异图和运行数据。
