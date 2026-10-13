# AgenTeam 版本钉定与安全跟进策略（ADR）

- 状态：采纳（2026-10-13）
- 关联：产品经理升级思路 §2「依赖版本升级（P0）」、§5 第 2 条
- 适用范围：所有主干依赖与构建基线

## 1. 决策

采用 **「钉版 + 季度安全跟进」** 的保守版本策略：

1. **大版本钉死，禁止自动跨大版本升级**。Spring Boot 4、Next.js 16、React 19、AgentScope 2.0.3 等年轻大版本组合是本项目首要稳定性风险源，任何跨大版本升级必须走立项评审，不得由 Renovate/Dependabot 自动合并。
2. **补丁级安全补丁优先自动跟进**。对安全相关与已钉组件的 patch 版本，允许 Renovate 自动合并（`automergeMinor` 关闭、`patch.automerge` 开启）。
3. **季度安全评审**。每季度（Q1/Q2/Q3/Q4 首月）集中评估：上游安全公告、钉版组件的补丁、AgentScope 2.x 回归测试状态，输出一份评审记录。
4. **回归守护优先于升级**。AgentScope 的 `AgentScope203RegressionTest` 必须随任意相关改动运行；任何 AgentScope 跟进前先确认该回归套件通过。

## 2. 当前钉版基线（v0.2.0）

| 组件 | 版本 | 钉版方式 | 风险 | 跟进策略 |
| --- | --- | --- | --- | --- |
| Spring Boot | 4.1.1 | parent BOM | 🔴 高（大版本首发） | 仅 patch；跨大版本立项 |
| AgentScope | 2.0.3 | `agentscope.version` 属性 | 🔴 高（年轻框架） | 仅 patch；保留 203 回归 |
| Next.js | 16.3.2 | `package.json` 精确钉定 | 🔴 高 | 仅 patch；跨大版本立项 |
| React / react-dom | 19.2.8 | 精确钉定 | 🟡 中-高 | 仅 patch |
| MyBatis-Plus | 3.5.17 | 精确钉定（spring-boot4 专用 starter） | 🟡 中 | 仅 patch |
| MariaDB Connector/J | 3.5.10 | 精确钉定 | 🟢 低 | patch 优先 |
| mybatis-plus-join | 1.5.9 | 精确钉定 | 🟡 中 | patch 优先 |
| springdoc-openapi | 3.1.0 | 精确钉定 | 🟢 低 | patch 优先 |
| jsoup | 1.22.2 | 精确钉定 | 🟢 低 | 安全 patch 优先 |
| PDFBox | 3.0.8 | 精确钉定 | 🟢 低 | 安全 patch 优先 |
| AWS SDK (S3) | 2.49.6 | `aws-sdk.version` 属性 | 🟢 低 | patch 优先 |
| BouncyCastle | 1.85 | 精确钉定 | 🟢 低 | 安全 patch 优先 |
| json-schema-validator | 2.0.0 | 精确钉定 | 🟢 低 | patch 优先 |
| @base-ui/react | 1.8.0 | 精确钉定 | 🟡 中 | 仅 patch |
| tiptap（core/pm/react/starter-kit） | 3.31.3 | 精确钉定 | 🟡 中 | 仅 patch |
| @xyflow/react | ^12.11.6 | caret（允许 12.x patch） | 🟡 中 | 维持 12.x |
| shadcn | 4.21.0 | 精确钉定 | 🟡 中 | 仅 patch |
| Tailwind | ^4 | caret | 🟡 中 | 维持 4.x |
| TypeScript | ^5.1.3 | caret | 🟢 低 | 维持 5.x |
| ESLint | ^9 | caret | 🟢 低 | 维持 9.x |
| Prettier | 3.9.9 | 精确钉定 | 🟢 低 | patch 优先 |
| pnpm | 11.15.1 | `packageManager` + CI 钉定 | 🟢 低 | 仅 patch |

> 构建基线：Java **21**（LTS，强制）、Node **24** + pnpm **11.15.1**（CI 用 `corepack prepare pnpm@11.15.1` 钉定）。

## 3. Renovate 对应规则

见 `.github/renovate.json`：

- 第一类（Spring Boot / Spring / Next / React / AgentScope）：`rangeStrategy=pin`，`major/minor` 关闭、`patch.automerge=true`。
- 第二类（加密/BouncyCastle、PDFBox、jsoup、AWS SDK、MariaDB、json-schema-validator 等安全相关）：`patch.automerge=true`、`minor.automerge=false`。
- GitHub Actions / Dockerfile 单独分组审阅。
- 每周一（Asia/Shanghai）统一排程，避免日常噪声。

## 4. 季度评审清单（模板）

每次评审记录至少包含：

- [ ] 上游安全公告是否影响钉版组件（NVD / GitHub Advisory / 厂商公告）
- [ ] 钉版组件是否有可用 patch 版本，是否需合入
- [ ] `AgentScope203RegressionTest` 是否通过（任意 AgentScope 改动前必查）
- [ ] 是否有跨大版本升级提案，是否走立项评审
- [ ] 依赖扫描（Trivy / OWASP Dependency-Check / `pnpm audit`）是否有新增 HIGH/CRITICAL 且未处理项

## 5. 例外与升级通道

- **紧急安全修复**：若出现被利用的 0-day 且仅有跨大版本补丁，可走紧急通道（安全负责人审批 + 回归套件全绿 + 发布说明标注），不受「禁止跨大版本」约束。
- **技术预览分支**：允许在 `preview/*` 分支试验跨大版本升级，不影响主干策略。
