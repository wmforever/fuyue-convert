# Contributing / 参与贡献

感谢你愿意参与 Fuyue Convert。项目重视可验证的转换质量、明确的失败边界和可公开
复现的证据，不会把所有格式都描述成“完美支持”。

Thank you for contributing. Fuyue Convert values verifiable conversion quality,
explicit failure boundaries, and publicly reproducible evidence over broad but
unqualified format claims.

参与前请遵守 [社区行为准则](CODE_OF_CONDUCT.md)。疑似安全问题不要开公开 Issue，
请按 [安全策略](SECURITY.md) 私密报告。

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report suspected
vulnerabilities privately according to the [Security Policy](SECURITY.md).

## 贡献类型 / Ways to contribute

- 新增或改进转换器、解析器和渲染器。
- 提交已获授权且完成脱敏的最小失败样本。
- 改进安全校验、任务隔离、超时、资源限制和错误提示。
- 改进前端、桌面外壳、部署、文档和无障碍体验。
- 补充不同操作系统、CPU、字体、LibreOffice、Poppler 或 OCR 环境下的测试证据。

- Add or improve converters, parsers, and renderers.
- Contribute licensed, privacy-reviewed minimal fixtures.
- Improve security boundaries, isolation, limits, diagnostics, UI, packaging,
  documentation, accessibility, and cross-platform evidence.

## 开始之前 / Before implementation

新增路线、默认外部依赖、公开 API 变更或质量等级晋级，请先开 Issue。至少说明：

- 源格式、目标格式和建议的稳定 route ID；
- 目标是保真、可编辑、数据一致还是文本提取；
- 实现引擎、版本、许可证和是否需要本机命令；
- 样本来源、再分发许可、脱敏状态与预期结果；
- 不能安全处理时的 error/warning 契约。

Before proposing a new route, default external dependency, public API change,
or quality promotion, open an Issue describing the source/target formats,
quality goal, stable route ID, engine and license, fixture provenance, and
explicit error/warning behavior.

许可不明、只能依赖闭源会员服务、要求默认上传云端或无法提供可公开复现证据的路线，
可能不会被接受。

## 新增转换路线 / Adding a conversion route

1. 在 `DocumentFormat` 中定义并验证格式、扩展名和 MIME 边界。
2. 新增一个实现 `FileConverter` 的转换器；不得只改扩展名伪装输出。
3. 用 `ConversionRoute` 声明 route ID、策略、质量等级、依赖、限制和状态。
4. 在 `DefaultConverterRegistry` 中注册可用或不可用路线。
5. 为核心逻辑、失败契约和资源边界增加单元/集成测试。
6. 对可公开样本增加 HTTP QA 证据，并更新中英文 README、质量标准和已知限制。

New routes start as `experimental`. Promotion to `beta` or `stable` is based
on representative public fixtures, route-specific QA, known limitations, and
maintainer review—not only on whether one file converts successfully.

任何路线都不得静默丢页、静默跳过失败输入、返回错误 MIME/扩展名，或把降级结果标成
无警告成功。复杂文档无法完整处理时，应明确失败或返回结构化 warning。

## 兼容性契约 / Compatibility contract

以下内容属于用户和自动化依赖的公开契约，修改时必须说明兼容性影响并补测试：

- capability route ID、source/target format、status、quality level 和 strategy；
- API 请求参数、响应字段、下载名称和 Content-Type；
- error code、warning code 及其触发语义；
- 批量顺序、部分成功、重试、取消、过期和历史恢复行为。

Route IDs, request parameters, response fields, error/warning codes, content
types, ordering, retry, cancellation, expiry, and history behavior are public
contracts. Avoid changing them silently.

## 样本、隐私与许可 / Fixtures, privacy, and licensing

提交样本前必须确认：

- 有权公开分发，且记录来源 URL/版本、许可证、用途和 SHA-256；
- 已移除姓名、账号、手机号、证件号、合同、租户、批注、隐藏元数据和真实签章信息；
- 体积尽量小，优先使用代码生成的确定性合成 fixture；
- 不包含恶意载荷；安全复现材料按 `SECURITY.md` 私密提交。

`qa-samples/input/` 默认是维护者本地语料，不会自动纳入 Git。不要提交
`qa-samples/output/`、`work/`、`runtime-data/`、`report/` 或差异图。完整 HTTP QA 的
必需/可选文件和许可边界见 [qa-samples/README.md](qa-samples/README.md)。

Do not publish a fixture merely because it is already present on your machine.
Prefer deterministic synthetic fixtures and include provenance, redistribution
permission, privacy review, purpose, and checksum for every public binary file.

新增运行时或构建依赖时，同时更新 `THIRD_PARTY_NOTICES.md`，并说明是否会进入 fat
JAR、容器、桌面安装器或平台运行包。默认实现不得新增闭源或云端引擎。

## 本地验证 / Local verification

只运行与你的改动匹配的命令，并在 PR 中记录实际结果；未运行的项目需要写明原因。

```bash
# Java 核心 / Java core
mvn -B -ntp -Dskip.frontend=true test

# 前端 / Frontend
(cd frontend && npm ci --no-audit --no-fund && npm run build)

# 桌面外壳 / Desktop shell
(cd desktop && npm ci --no-audit --no-fund && npm run check && npm test)

# 完整转换路线验收 / Full route QA (requires the local corpus)
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

QA 会在启动服务前列出缺失样本。严格标准见
[docs/quality-standard.md](docs/quality-standard.md)。平台打包和原生依赖相关修改还需在
对应操作系统执行真实安装、启动、转换、退出与许可证检查。

## Pull Request 要求

- 关联 Issue，并说明用户影响、兼容性和已知限制。
- 将无关重构与行为修改拆开；不要提交生成物或本地数据。
- 更新相关测试、中英文文档、第三方声明和 capability 元数据。
- 提供验证命令及结果；视觉问题附脱敏前后对比。
- 确认新增代码和样本有权按本项目及其声明的许可证贡献。

## 维护边界 / Maintainer responsibilities

贡献者负责最小复现、实现、相关测试、样本权利和文档。缺少特定平台或外部引擎时，
可以在 PR 中明确标记未运行，由维护者补最终环境验收。

维护者负责最终完整 QA、兼容性判断、质量等级、合并顺序和发布安全。`stable` 晋级、
默认外部依赖、公开 API/error code 变化及二进制再分发必须由维护者批准。项目不承诺
Issue 响应 SLA，也不保证接受每条路线。

Contributors own the implementation evidence and rights to submitted material.
Maintainers own final compatibility, quality-level, merge-order, and release
safety decisions. Missing platform coverage may be documented for maintainers
to complete, but it must not be represented as tested.

## 许可证 / Contribution license

提交到本项目的贡献默认以 Apache License 2.0 授权。项目目前不要求单独 CLA；提交者
必须有权提供其代码、文档和样本。第三方组件继续适用各自许可证，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Contributions are accepted under Apache License 2.0 unless explicitly agreed
otherwise. Third-party components remain under their own licenses.
