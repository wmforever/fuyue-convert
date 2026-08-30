## Summary / 摘要

- Related Issue / 关联 Issue:
- User impact / 用户影响:
- Compatibility impact / 兼容性影响:
- Known limitations / 已知限制:

## Type / 类型

- [ ] Converter route / 转换路线
- [ ] Conversion quality / 转换质量
- [ ] API/backend / 接口或后端
- [ ] Frontend / 前端
- [ ] Desktop / 桌面端
- [ ] Release/package / 发布打包
- [ ] Security / 安全
- [ ] Documentation / 文档
- [ ] Tests/QA / 测试

## Route and contract checklist / 路线与契约

- [ ] Not applicable / 不涉及转换路线
- [ ] Route ID、status、qualityLevel、strategy、dependencies 和 limitations 已更新
- [ ] 扩展名/MIME、请求参数、下载名和 Content-Type 已覆盖
- [ ] 错误码、警告码、失败/部分成功语义已有测试
- [ ] 单元/集成测试和 HTTP QA 证据已补充
- [ ] `README.md`、`README_EN.md`、质量标准和已知限制已同步

## Fixtures and dependencies / 样本与依赖

- [ ] 没有新增二进制样本或第三方依赖
- [ ] 样本已记录来源、版本、许可、用途、SHA-256 和脱敏审核
- [ ] 依赖已记录版本、许可证、运行时是否捆绑，并更新 `THIRD_PARTY_NOTICES.md`
- [ ] 没有提交 token、个人/商业敏感信息、本地输出或未授权文件

## Verification / 验证结果

请填写实际结果。未运行的命令写明原因，不要只勾选。

| Check | Result |
| --- | --- |
| `mvn -B -ntp -Dskip.frontend=true test` | |
| `cd frontend && npm ci --no-audit --no-fund && npm run build` | |
| `cd desktop && npm ci --no-audit --no-fund && npm run check && npm test` | |
| `mvn -DskipTests package && python3 qa-samples/run_qa.py` | |
| Platform package/install/smoke/license verification | |

## Evidence / 证据

粘贴脱敏日志摘要、截图或报告；视觉问题请说明页码和比较方式。
