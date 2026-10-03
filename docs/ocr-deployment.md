# OCR 运行方式

扫描 PDF/OFD 和图片转可编辑 Word 使用本地 Tesseract。后续生成的 Windows x64、macOS Intel/Apple Silicon Lite 与 Full 安装包均默认内置引擎和中英文模型，用户不需要另外安装 OCR，也不需要联网识别。Lite/Full 的区别仍是是否内置 LibreOffice。

## 构建与校验

```bash
# 单独准备、编译并验证原生 OCR（不运行 Maven）
node desktop/scripts/prepare-ocr-runtime.mjs
# 通用运行包默认包括 OCR
scripts/package-runtime.sh
```

Windows 使用 `scripts/package-runtime.ps1`。构建机需要 Node.js、CMake、curl 和原生 C/C++ 编译器（Windows 使用 Visual Studio C++ Build Tools，macOS 使用 Xcode Command Line Tools）。首次构建下载源码与语言模型，之后复用通过哈希校验的 `desktop/.cache/ocr` 缓存和 `desktop/.runtime/ocr`。所有二进制必须在对应原生架构构建，不能把 Intel 引擎复制进 Apple Silicon 包。

`desktop/licenses/ocr-runtime-lock.json` 固定了 Tesseract、Leptonica、libpng、zlib 的源码地址、版本、SHA-256、许可证哈希及编译选项，以及 `tessdata_fast` 的固定提交和模型哈希。引擎仅静态链接应用 OCR 路线需要的 PNG 解码依赖，关闭下载、归档、训练工具、OpenMP 及额外图片编解码库。应用先把 PDF/OFD 页面或上传图片统一渲染为 PNG，再交给 OCR，因此不会降低上传格式支持。

每次准备和复制后都校验全部文件、语言列表、版本、平台/架构，并用中英文联合模型实际识别“文档转换 12345”的 PNG，检查 TSV 文字。macOS 额外拒绝依赖 Homebrew 或其他构建机绝对路径的动态库；Windows 检查 PE 导入表并拒绝第三方 DLL 依赖。缺引擎、缺中英模型、下载或许可证哈希不匹配、自检失败都会终止打包，不能静默生成缺少 OCR 的“完整包”。

正式 Lite/Full workflow 必须包含 OCR，并把引擎、全部静态依赖、模型来源和许可证纳入最终 `RUNTIME-COMPONENTS.json`。安装后的包和挂载的 DMG 会再次自检。已有旧版本安装包不会随源码更新自动改变，需要重新构建发布。

## 内置目录

```text
app/ocr/
├── OCR-RUNTIME.json        # 平台、架构、依赖来源及全文件 SHA-256
├── bin/tesseract           # Windows 为 tesseract.exe；无额外第三方 DLL
├── licenses/               # 完整许可证和 OCR-SOURCE-POLICY.json 固定来源策略
└── tessdata/
    ├── eng.traineddata
    ├── chi_sim.traineddata
    ├── chi_sim_vert.traineddata
    ├── osd.traineddata
    └── configs/tsv
```

需要复用已准备好的运行时可设置 `FORMAT_CONVERTER_OCR_HOME`，桌面暂存仍会验证其完整性。通用运行包在自身 `app/ocr` 生成同一套运行时。

## 桌面用户配置

新版桌面应用在“设置 → OCR 与 Office 配置”提供引擎来源、OCR 识别语言、本机 Tesseract 文件、独立 tessdata 语言包目录，以及本机 LibreOffice 文件选择。OCR 是离线引擎，不需要绑定账号、购买密钥或配置云服务。

默认使用内置引擎，并覆盖桌面启动时继承的旧 OCR/Office 开关和路径，避免随包引擎被系统环境意外关闭。需要停用时请在设置中选择“停用”。“检测配置”会实际识别应用自带的合成中英文图片；“检测并保存”拒绝无法识别、缺少所选语言包或不可用的自定义引擎，不覆盖先前有效配置。设置存入当前设备的 `engine-settings.json`，重启后传给后台及独立 Worker 生效。存在转换、排队或原生保存任务时不能从设置页重启。

选择内置/自动检测时，缺少可选 Office 不阻止保存；Lite 用户可以指定本机 LibreOffice，或使用 Full 获得完整离线 Office 能力。旧版安装包的状态展示不会自动获得新的选择入口和 OCR，仍需安装重新构建的新版。

## 启用与关闭

存在完整内置运行时时 OCR 自动启用。安装器启动器路径和应用目录均可发现运行时；主程序将已发现的目录传给独立转换 Worker，切换任务工作目录后仍使用同一引擎。原生文字页继续使用 PDF/OFD 解析器，扫描页才启动 Tesseract；不回退云服务，不把无法识别的扫描页当作成功的空白输出。

- 源码/独立 JAR 强制关闭：`FORMAT_CONVERTER_OCR_ENABLED=false`；新版桌面由保存的引擎设置控制。
- 仅在本地开发打包时省略 OCR：`FORMAT_CONVERTER_BUNDLE_OCR=false`；正式发布拒绝该选项。
- 源码/独立 JAR 使用自有引擎：设置 `FORMAT_CONVERTER_OCR_ENABLED=true`、`FORMAT_CONVERTER_TESSERACT_BINARY` 和 `FORMAT_CONVERTER_OCR_LANGUAGES=chi_sim+eng`；语言包分开放置时可设置 `FORMAT_CONVERTER_TESSDATA_DIR`，检测与实际识别均显式使用该目录。

启动后 `/api/health` 的 `ocr.enabled`、`ocr.available`、`ocr.bundled`、版本和语言列表反映实际能力。显式指定的引擎优先于内置引擎；系统 PATH 仅在显式启用时回退。

## 扫描 Word 的显示

识别词使用可编辑坐标文本框，中文相邻词不会人为加入空格。同一行采用一致字号和基线；标准文本框避免 Office 阅读器添加额外内边距。白底仅覆盖已识别词框及 0.15 mm 抗锯齿边缘，原始底图仍完整保留，词间空隙不整行涂白。对规则全角中文，只有源像素逐字空白分隔能通过校验时才细化词框。

这不是原字体和复杂版面的无损重建。彩色底纹、印章交叠、手写体和不规则词框仍需人工核对；引擎平均置信度也不能当作逐字准确率。

## 平台验收边界

Linux 通用包也使用相同源码构建，但没有官方 Linux 桌面安装器，基础系统 C/C++ 运行库兼容性应在目标发行版验证。Windows、macOS Intel、macOS Apple Silicon 的安装器仍需各自原生 CI 完成编译、安装后自检和打包验证；本机通过不能替代其他架构的真实验收。

## 低置信度图片增强

图片、扫描 PDF/OFD 共用 OCR 流程。原图先识别；无文字或平均置信度低于复核阈值时，在同一页面时限内最多增加一次灰底/缓变阴影归一化和对比度增强识别。增强只生成私有临时 PNG，不旋转、缩放、裁切或覆盖源图，Word 底图和几何校正仍依据原始像素；临时 PNG 在成功、失败和超时后均释放。

只有候选达到最低置信度、比原结果至少提高 5 个百分点、字母/数字字符量不低于原结果的 90%，并按顺序保留原结果中置信度至少 85% 的文字（包括标点、金额小数点及数字边界），才采用增强结果并返回 `OCR_IMAGE_ENHANCED`。这些检查只能减少明显退步，不能证明内容完整或逐字正确；增强失败则保留原识别结果，并继续按原有最低/复核阈值判断。空白页不启动增强识别，任一边长超过 32768 像素的超长图片也跳过增强，以限制附加内存。倾斜、手写、严重污损、彩色印章和复杂表格仍需人工复核。

## 扫描 Word 背景衔接

PDF/OFD 扫描文字生成 Word 时，遮罩仍限制在各个识别词框及 0.15 mm 抗锯齿边缘内，词间未知区域保持可见。渲染器从词框外侧估计局部纸色：均匀灰底/浅色纸张用邻近颜色，缓变阴影每词最多使用 8 个色块近似；深色区域使用浅色识别文字以保持可读。原扫描图完整保留为独立图片部件，遮罩不修改图片字节或识别坐标，也不增加新的图片部件。

采样发现彩色标记、明显纹理或证据不足时不加遮罩，原像素保留，可编辑文字仍生成，但可能与原字产生重影。彩色检查是有限采样，不能证明所有印章、图案均已保护，复杂背景仍需复核。逐张解码背景并及时释放；超过 2500 万像素、任一边长超过 32768 像素或无法解码时跳过这一可选细化。此行为只适用于标记为 OCR 背景的图片，普通照片和原生文字不参与纸色遮罩。
