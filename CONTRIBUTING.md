# Contributing

感谢你愿意参与 FormatConverter。这个项目的核心不是“把所有格式都说成已完美支持”，而是让每条转换路线的能力、限制和测试证据都清楚可查。

## 贡献类型

- 新增或改进转换器。
- 增加真实失败样本或可公开测试样本。
- 改进安全校验、任务隔离、超时控制和错误提示。
- 改进文档、部署脚本和前端体验。
- 补充不同操作系统、CPU 架构和字体环境下的 QA 报告。

## 新增转换路线

1. 在 `DocumentFormat` 中确认源格式和目标格式。
2. 新增一个实现 `FileConverter` 的转换器。
3. 在 `ApplicationConfiguration` 中注册转换器。
4. 为核心逻辑增加单元测试。
5. 在 `qa-samples/run_qa.py` 中增加端到端样本测试。
6. 更新 `README.md` 的能力矩阵和 `docs/quality-standard.md` 的质量等级。

每条路线都应明确是以下哪一种目标：

- `stable`：已通过严格数据回环或多个视觉样本，无已知严重分页/内容丢失问题。
- `beta`：可用于普通场景，但复杂样本可能存在版式或字体差异。
- `experimental`：可运行，但仍依赖特定外部引擎、样本覆盖不足或存在明显取舍。

## 样本要求

提交样本前请确认：

- 样本可以公开分发，或已经做了脱敏处理。
- 不包含个人信息、合同编号、证件号、印章密钥或商业敏感内容。
- 能说明来源、格式、预期结果和当前失败现象。
- 体积尽量小，优先放在 `qa-samples/input`。

不要提交 `qa-samples/output`、`qa-samples/work`、`qa-samples/runtime-data` 和差异图，这些都是本地 QA 产物。

## 本地验证

提交前至少运行：

```bash
mvn -Dskip.frontend=true test
```

如果改动影响前端、打包或转换路线，还需要运行：

```bash
mvn -DskipTests package
python3 qa-samples/run_qa.py
```

QA 严格通过标准见 [docs/quality-standard.md](docs/quality-standard.md)。

## 编码约定

- 遵循现有模块边界，不把格式解析、任务状态和 Web API 混在一起。
- 转换失败要抛出有意义的异常或返回清晰警告，不要静默吞掉内容。
- 依赖外部命令时必须设置超时，并在健康检查或错误信息中暴露缺失原因。
- 不新增闭源依赖作为默认实现；商业或私有引擎只能作为可选插件方向讨论。

## 许可证

提交到本项目的贡献默认以 Apache License 2.0 授权。
