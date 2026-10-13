# 安全策略（Security Policy）

> 本文档落实《升级思路分析》中 P0「自动化安全门禁」与「合规补齐」建议，作为 AgenTeam 的
> 安全准入基线。最近更新：2026-10-12。

## 1. 安全门禁（P0：决定「能不能安心用」）

AgenTeam 已具备较好的安全基础：

- **凭据落库加密**：`PayloadEncryption` / `CredentialService`（后端）
- **工具出网受限**：`RestrictedHttpClient`、MCP 的 `RestrictedMcpTransport`
- **生产强校验**：`ProductionSettingsValidation`（强制 HTTPS、Secure Cookie、可信代理白名单）
- **供应链合规**：第三方源码索引 + SHA-256（Apache-2.0 合规），MariaDB 驱动 LGPL-2.1 已在发行说明中明确

仍缺自动化闭环，按以下方案补齐：

### 1.1 依赖漏洞可见（SCA）

| 层 | 工具 | 接入点 |
|---|---|---|
| 前端（npm/pnpm） | `pnpm audit`（已纳入 CI `security` job，fail-on high） | `.github/workflows/ci.yml` |
| 后端（Maven） | OWASP Dependency-Check（离线 NVD 库）或 `dependency-check-maven` 插件 | CI `security` job 扩展；建议在 `pom.xml` 增加 `org.owasp:dependency-check-maven` |
| PR 评审 | GitHub `dependency-review-action`（已接入，fail-on high） | `.github/workflows/ci.yml` |

### 1.2 镜像漏洞扫描

- 使用 **Trivy** 在镜像构建流水线中扫描 `deploy/Dockerfile.*` 产物与 `sandbox` 镜像。
- 沙箱内置 Playwright + Chromium 体积与供应链面较大，应纳入定期扫描与最小基线复核。

### 1.3 依赖版本策略（Renovate）

- `renovate.json` 已建立：核心框架（Spring Boot 4 / Next 16 / React 19）**钉版**（`rangeStrategy: pin`）；
  补丁/小版本聚合为 `quarterly-deps` 分组，**季度安全跟进**；后端 Maven 依赖一并纳管。
- 保留 `AgentScope203RegressionTest` 类守护 AgentScope 2.x 行为，每次依赖升级后必须跑回归。

## 2. 合规补齐（P0：把「能跑」变成「能卖给企业」）

| 项 | 现状 | 建议 |
|---|---|---|
| 操作/访问审计日志 | 无 | 异步写审计（操作人、资源、时间、结果），采样降低性能影响 |
| 发布期漏洞扫描 | 无 | 在发布流水线接入 Trivy + Dependency-Check，阻断高危 |
| 第三方许可复核 | LGPL-2.1 驱动已说明 | 维持声明，新增依赖做许可准入检查 |
| 生产强校验清单 | 已有 `ProductionSettingsValidation` | 清单化、文档化，作为上线前检查项 |

## 3. 目标与范围（建议，待拍板）

- 优先面向**国内企业**落地（等保 / ISO 27001 路径）；GDPR / 出海按需扩展。
- 详见 `docs/decision-record-2026-10-12.md` 的 7 项待拍板决策。

## 4. 漏洞上报

如发现安全漏洞，请通过项目的安全披露渠道提交（参考 GitHub Security Advisories），勿在公开 Issue 披露细节。
