# OCR 运行方式

OCR 仍是 Tesseract 原生进程，不是 JDK、PDFBox 或 OFDRW 提供的继承能力；但正式运行包已将引擎、动态库和语言模型一起封装，最终用户不需要再安装 Tesseract。

## 各运行方式

| 运行方式 | OCR 来源 | 是否需要用户安装 |
| --- | --- | --- |
| 官方 macOS/Linux/Windows 运行包 | `app/ocr` 内置运行时 | 否 |
| 官方 Docker 镜像 | 镜像构建阶段安装 Tesseract 和模型 | 否 |
| 源码运行 | 系统 Tesseract 或自行准备 `app/ocr` | 是 |
| 单独运行 Spring Boot JAR | JAR 相邻的 `ocr`/`app/ocr`，或系统 Tesseract | 视部署结构而定 |

应用按以下优先级选择引擎：

1. `FORMAT_CONVERTER_TESSERACT_BINARY` 指定的引擎；
2. 应用目录中的内置 `ocr` 运行时；
3. 显式启用时搜索系统 `PATH`。

存在完整内置运行时时，OCR 能力自动启用；设置 `FORMAT_CONVERTER_OCR_ENABLED=false` 可强制关闭。自动启用只表示 OCR 路线可用，不会改变转换策略：PDF/OFD 原生文字页仍由解析器处理，只有扫描页或明确选择的图片 OCR 路线才启动 Tesseract。

## 内置目录

```text
app/ocr/
├── bin/
│   ├── tesseract          # Windows 为 tesseract.exe
│   └── *.dll              # Windows 运行库
├── lib/                   # Linux/macOS 动态库
└── tessdata/
    ├── eng.traineddata
    ├── chi_sim.traineddata
    ├── chi_sim_vert.traineddata
    ├── osd.traineddata
    └── configs/tsv
```

应用会自动传递 `--tessdata-dir`，并在子进程范围内设置 `TESSDATA_PREFIX`、`LD_LIBRARY_PATH`、`DYLD_LIBRARY_PATH` 或 Windows `PATH`，不会污染主机全局环境。

## 构建运行包

在构建机安装 Tesseract 后执行：

```bash
FORMAT_CONVERTER_BUNDLE_OCR=true scripts/package-runtime.sh
```

Windows 使用：

```powershell
$env:FORMAT_CONVERTER_BUNDLE_OCR = "true"
.\scripts\package-runtime.ps1
```

`prepare-ocr-runtime.sh`/`.ps1` 会复制平台二进制、动态库、选定模型和 TSV 配置，然后用复制后的引擎执行 `--version`、`--list-langs` 自检。缺少任何必需文件时打包直接失败。可通过 `FORMAT_CONVERTER_BUNDLED_OCR_LANGUAGES` 调整模型集合，默认是 `eng chi_sim chi_sim_vert`。

发布 Workflow 会显式安装各平台 Tesseract。Windows 的模型固定到指定 `tessdata_fast` 提交，并在进入安装包前验证 SHA-256；运行包冒烟测试要求 `/api/health` 返回 `ocr.bundled=true`。

## 系统引擎回退

不使用官方运行包时仍可这样启用：

```bash
export FORMAT_CONVERTER_OCR_ENABLED=true
export FORMAT_CONVERTER_TESSERACT_BINARY=/usr/bin/tesseract
export FORMAT_CONVERTER_OCR_LANGUAGES=chi_sim+eng
```

Ubuntu/Debian 示例：

```bash
sudo apt-get update
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim tesseract-ocr-chi-sim-vert
```

启动后通过 `/api/health` 检查 `ocr.enabled`、`ocr.available`、`ocr.bundled`、版本和语言列表。项目不会回退到云 OCR，也不会把扫描页当成成功的空白输出。
