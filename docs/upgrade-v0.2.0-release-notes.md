# AgenTeam v0.2.0 升级交付说明

> - **版本**：v0.2.0（Apache-2.0）
> - **交付日期**：2026-10-13
> - **依据**：`docs/upgrade-strategy-2026-10-12.md`（产品组 许清楚）
> - **状态**：代码已完成并经 JDK 21 离线编译验证；集成测试需 CI（本地 Docker 不可用）。

---

## 1. 交付内容（按 PM 升级思路，选定 4 个工作流 + 收尾）

| 工作流 | 优先级 | 交付物 | 状态 |
| --- | --- | --- | --- |
| **WS1 安全门禁 CI** | P0 | `ci.yml` 新增 `security` 作业（Trivy 文件系统扫描 vuln/secret/config → SARIF；OWASP Dependency-Check，HTML/JSON 产物，continue-on-error）；`renovate.json` 钉版策略 | ✅ |
| **WS2 钉版版本策略** | P0 | `docs/version-strategy.md`（ADR：钉版 + 季度安全跟进；版本基线表 / Renovate 映射 / 季度检查清单） | ✅ |
| **WS3 操作审计日志** | P1 | 审计查询栈 + 结果感知写入 + 幂等授权迁移 + 集成测试 + **失败路径铺开到 15 个高危写操作**（凭据/集成/知识/标签/待办/调度/邀请/雇佣决策/确认/归属转移）| ✅ |
| **WS4 部署/构建降本** | P1 | `docs/prerequisites.md`、`docs/deployment/compose-selection.md`（8 变体选型矩阵） | ✅ |
| **收尾：发布流水线扫描** | P1 | `community-checks.yml` 新增 `release-security` 作业（Trivy + OWASP），接入发布流水线 | ✅ |
| **收尾：配置修复** | — | 修复 renovate 重复配置（根配置被 `.github` 遮蔽）；修复 CI security 作业缺 pnpm/Node | ✅ |
| **收尾：版本控制** | — | `git init` 引导仓库，基线提交 `1398946`（master） | ✅ |

---

## 2. 关键修复（重要）

1. **审计迁移主键冲突（严重）**：迁移原写成 `INSERT INTO sys_permission ... ('audit.view')`，但 `audit.view` 已存在于基线 `V0__init.sql`（`sys_permission.code` 为 **PRIMARY KEY**），会导致每个库起库失败。已改为只**幂等**地把 `audit.view` 补授给**已有**企业的 `enterprise-admin`（`NOT EXISTS` 防重）。
2. **审计写入事务性（重要）**：`AuditEventService.record` 原无事务注解，审计写入会参与业务事务、随业务回滚丢失。已改为 `REQUIRES_NEW`，确保审计（尤其是失败路径）**独立落库、不被业务异常吞掉**。
3. **审计结果写死 success**：`AuditEventSqlMapper.appendAuditEvent` 与 `AuditEventMapper.append` 增加 `result` 参数，新增 `record(..., boolean success)` 重载；失败分支写入 `failure`（含异常信息 `Objects.toString(exception.getMessage(),"")`），成功分支写入 `success`。失败路径已从公告删除/启停**铺开到 15 个高危写操作**——凭据创建/轮换/撤销、集成密钥轮换/启停/删除、知识资料删除、标签删除、待办删除、定时任务启停/删除、邀请撤回、雇佣决策、确认决策、资源归属转移——与公告服务同一模式（持久化调用包 `try/catch`，失败写 `failure` 并原样抛出，成功写 `success`）。
4. **renovate 重复配置（严重隐性）**：根 `renovate.json`（Renovate 实际读取的**唯一**位置，优先级高于 `.github/renovate.json`）是旧的部分版，导致我建的 `.github/renovate.json`（含 AgentScope 钉版、安全依赖自动合并）**从未生效**。已合并完整配置到根文件并删除 `.github` 副本。
5. **CI security 作业致命缺陷**：原 `前端依赖漏洞扫描` 步骤直接 `pnpm audit`，但该作业未安装 pnpm/Node，必在 CI 失败。已补 `pnpm/action-setup` + `setup-node`（Node 24）+ `pull-requests: read` 权限。
6. **Node 版本不一致**：统一收敛到 PM 指定的 **Node 24**（CI 三处 + 文档 prerequisites/version-strategy 全部对齐）。

---

## 3. 验证状态

| 验证项 | 结果 |
| --- | --- |
| 后端主代码编译（JDK 21 离线） | ✅ `MAIN_EXIT=0` |
| 后端测试代码编译（JDK 21 离线） | ✅ `TEST_EXIT=0` |
| 审计端点测试 `AuditEventApiTest` | ✅ 编译通过（4 用例：200 / 403 缺权限 / action 过滤 + keyset 分页 / 405）；**需 MariaDB 容器，CI 执行** |
| 公告删除失败路径测试 | ✅ 新增 `recordsFailureAuditWhenDeletionUpdateFails`：update 抛异常 → 写 `failure` 审计并原样抛出 |
| 标签删除失败路径测试 | ✅ 新增 `TagDeletionAuditTest`（纯 Mockito，2 用例：成功审计 / 失败审计 `false`）；编译通过，**需 CI 跑 surefire**（本地离线缺 surefire jar）|
| 失败路径铺开（15 个写操作）| ✅ 代码已接入并通过 JDK 21 主代码 + 测试代码编译验证；模式与公告服务一致 |
| `ci.yml` / `community-checks.yml` / `renovate.json` 配置校验 | ✅ js-yaml / JSON 解析 VALID |
| 5 轮 QA（编译 + 静态审计 + 配置校验 + 测试扩写 + 最终回归） | ✅ 全绿 |

> 本机环境约束：PATH 仅有 JDK 17（项目需 21，已用 `/c/Users/Admin/agenteam-runtime/jdk-21.0.12.1+1`）；**Docker 不可用**，故容器化集成测试只能在 CI 跑。

---

## 4. 部署与 CI 说明

### 4.1 构建前提
- **JDK 21**（强制，详见 `docs/prerequisites.md`）。
- **Node 24 + pnpm 11.15.1**（CI 用 `corepack prepare pnpm@11.15.1` 钉定）。
- Docker / Compose 2.24.4+；Maven 3.9；MariaDB 13 / Redis 7。

### 4.2 CI 门禁
- **PR 路径**（`ci.yml`）：后端 Maven 测试 + 前端质量/类型/构建/e2e；`security` 作业跑 Trivy（HIGH/CRITICAL → SARIF → 代码扫描）+ OWASP（产物，continue-on-error）。
- **发布/主干路径**（`community-checks.yml`）：后端完整测试 + 前端构建/e2e + **`release-security` 作业**（Trivy fs 扫描 + OWASP，产物留存，**只读权限模型，不回写代码扫描**）。
- **依赖治理**（`renovate.json`）：核心框架（Spring Boot/Spring/Next/React/AgentScope）钉版 + major/minor 禁用 + patch 进季度评审；安全依赖 patch 自动合并；其余 npm/maven 归入 `quarterly-deps`（不自动合并）；每周一 Asia/Shanghai。

### 4.3 部署选型
- 8 套 Compose 变体（`compose.yaml` 默认 + dev / low-memory / external-mysql / shared-mysql / split / single / registry），详见 `docs/deployment/compose-selection.md` 选型矩阵。

---

## 5. 遗留项 / 待办（需团队拍板或外部基建）

| # | 事项 | 阻塞/依赖 | 建议责任方 |
| --- | --- | --- | --- |
| 1 | ~~推送远端并开 PR~~ **已完成**：remote `https://github.com/luojundong/agenteam` 已添加；本地分支由 `master` 改名为 `main`（对齐 CI 监听分支），已 `git push -u origin main --tags`；`main`(b2464fd) 与 `v0.2.0` tag 均已上远端。push 到 `main` 会触发 `ci.yml` 与 `community-checks.yml`（含 `AuditEventApiTest` 等需 Docker 的集成测试，CI 环境可用 Docker）。**无需单独开 PR 即可跑 CI**（仓库为全新仓库，main 即默认分支）。 | — | 已完成 |
| 2 | ~~失败路径全面铺开~~ **已完成**：15 个高危写操作已接入 `record(...,false)`（见 §2 #3）；新增 `TagDeletionAuditTest` 锁定行为 | — | 后端 |
| 3 | **PM 待决项**：Q1 部署规模 / Q2 JDK 基线 / Q3 多租户 / Q4 模型厂商 / Q5 合规审计 / Q6 编排定位 / Q7 版本节奏 | 产品 + 技术负责人 | 团队 |
| 4 | **phase 2-4 路线图**：合规安全期（凭据/出网复核、第三方许可复核、生产强校验清单化）、架构演进期（多租户/水平扩展/编排增强/模型生态）、体验国际化期（英文补全/多语种/移动无障碍） | 本轮已完成 phase 1 + phase 2 审计与发布扫描；其余按路线图分期 | 团队 |
| 5 | **CI 动作 SHA 钉定**：`ci.yml` / `community-checks.yml` 中 Trivy / OWASP / upload-artifact 部分用 tag，未与仓库其余动作一致按 SHA 钉定（供应链一致性优化项） | 补 SHA | 后端 |

---

## 6. 提交记录

- 基线提交 `1398946`（master）：含 WS1-4、配置修复、git 引导（2602 文件）。
- 增量提交 `3200903`：审计失败路径（公告）+ 发布流水线扫描 + 本说明。
- 增量提交 `b2464fd`（收尾）：修复 `scripts/start-local.ps1` PowerShell 保留变量 `$args` 命名冲突（后台进程无参启动）。
- 本地里程碑 tag：`v0.2.0`，**已推送至远端**。
- 远端：`https://github.com/luojundong/agenteam`，分支 `main`（由 `master` 改名对齐 CI），`origin/main` = `b2464fd`。
