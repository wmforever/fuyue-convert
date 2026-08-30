# Security Policy / 安全策略

## Supported code / 支持范围

Security fixes are developed for the latest `main` branch. Public binary
redistribution is currently paused while third-party runtime licensing and
provenance are being completed; old runtime packages should not be mirrored or
treated as supported releases.

安全修复以最新 `main` 分支为准。第三方运行时许可和来源尚在收口期间，公开二进制
发布暂时停用；请勿镜像旧运行包，也不要将其视为受支持版本。

## Report privately / 私密报告

Do not open a public Issue for a suspected vulnerability and do not upload a
malicious, confidential, or personally identifiable document to the public
repository.

请不要用公开 Issue 报告疑似漏洞，也不要向公开仓库上传恶意文档、机密文档或包含
个人信息的原始文件。

Send the report to `520fuyue@gmail.com` with the subject
`[Fuyue Convert Security]`. After GitHub Private Vulnerability Reporting is
enabled, the repository Security page will become the preferred channel.

请发送邮件至 `520fuyue@gmail.com`，主题使用 `[Fuyue Convert Security]`。仓库启用
GitHub Private Vulnerability Reporting 后，Security 页面将成为首选入口。

Include, after redaction:

- affected commit, version, and installation mode;
- impact and the boundary an attacker must cross;
- minimal reproduction steps or a synthetic fixture;
- relevant sanitized logs and environment details;
- whether the issue is already public and your disclosure timeline.

脱敏后请提供：受影响版本/提交、安装方式、影响与攻击边界、最小复现或合成样本、
必要的脱敏日志和环境信息，以及是否已公开、期望的披露时间。

## What belongs here / 哪些问题属于安全问题

Examples include malicious document parsing, archive/path traversal, command
execution, API-token bypass, unsafe non-loopback exposure, cross-task file
access, result leakage, desktop privilege boundary failures, and exploitable
dependency vulnerabilities.

Conversion fidelity, font substitution, unsupported files, ordinary crashes,
and non-security feature requests should use the public Issue forms unless they
also cross a security boundary.

安全问题包括恶意文档解析、归档或路径穿越、命令执行、API Token 绕过、非回环暴露、
跨任务文件访问、结果泄露、桌面权限边界失效和可利用的依赖漏洞。单纯的转换保真度、
字体替代、不支持文件、普通崩溃和功能建议，请使用公开 Issue；除非它们同时跨越了
安全边界。

## Process / 处理方式

The maintainers will acknowledge receipt when practical, reproduce and assess
the report, coordinate a fix, and agree on a disclosure point. There is no
guaranteed response SLA and the project does not currently operate a bug bounty
program. Please avoid publishing exploit details before a fix or agreed
mitigation is available.

维护者会在条件允许时确认收到、复现评估、协调修复并商定披露时间。项目不承诺固定
响应 SLA，目前也没有漏洞赏金计划；在修复或约定缓解措施可用前，请不要公开利用细节。
