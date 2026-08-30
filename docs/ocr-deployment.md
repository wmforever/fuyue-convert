# OCR 运行方式

OCR 仍是 Tesseract 原生进程，不是 JDK、PDFBox 或 OFDRW 提供的继承能力。源码运行可使用系统 Tesseract，本地构建脚本也能暂存引擎、动态库和语言模型；官方 Windows x64 Lite 桌面版明确不内置 OCR。原生依赖逐项许可与来源审核完成前，不得把本地暂存的 OCR 目录作为公开发行物分发。

## 各运行方式

| 运行方式 | OCR 来源 | 是否需要用户安装 |
| --- | --- | --- |
| 本地 macOS/Linux/Windows 构建 | 可在 `app/ocr` 暂存运行时，仅限开发验收 | 否 |
| 本地 Docker 构建 | 镜像构建阶段从发行版包管理器安装 | 否 |
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

## 本地构建运行包

在构建机安装 Tesseract 后执行：

```bash
FORMAT_CONVERTER_BUNDLE_OCR=true scripts/package-runtime.sh
```

Windows 使用：

```powershell
$env:FORMAT_CONVERTER_BUNDLE_OCR = "true"
.\scripts\package-runtime.ps1
```

`prepare-ocr-runtime.sh`/`.ps1` 会复制平台二进制、动态库、选定模型和 TSV 配置，然后用复制后的引擎执行 `--version`、`--list-langs` 自检。缺少任何必需文件时打包直接失败。可通过 `FORMAT_CONVERTER_BUNDLED_OCR_LANGUAGES` 调整模型集合，默认是 `eng chi_sim chi_sim_vert`。功能自检不等于再分发许可审核，生成目录只能用于本地开发验收。

官方 Lite 发布 Workflow 强制 `FORMAT_CONVERTER_BUNDLE_OCR=false`，并校验安装后目录不存在 OCR Runtime。若未来恢复带 OCR 的发行物，必须先为 Tesseract、Leptonica 及复制的每个原生库补齐固定版本、来源、哈希、许可证文本和必要源码义务；`ocr.bundled=true` 只证明功能发现成功，不代表许可审核完成。

## 系统引擎回退

源码或独立 JAR 可这样启用系统引擎：

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
