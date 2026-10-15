# AgenTeam Phase 2 实施计划（多租户 / 合规安全期）

> - **目标版本**：v0.3.0
> - **依据**：`docs/upgrade-v0.2.0-release-notes.md` §5 路线图；2026-10-15 代码基线调研
> - **范围**：合规安全期（审计防篡改、跨租户越权、生产强校验、出网/凭据复核）+ 多租户隔离机制化

---

## 1. 基线事实（调研结论，非推测）

| 维度 | 现状 | 证据 |
| --- | --- | --- |
| 租户模型 | 无 `tenant` 概念，租户即 `enterprise`（`EnterpriseRow` / `enterprise_member`） | `model/enterprise/entity/` |
| 隔离机制 | **手动约定式**：表复合外键强制 `enterprise_id` 一致 + service 显式传 `actor.enterpriseId()` + 控制器 `requireEnterprise` 校验 | `AuthContextService.requireEnterprise` |
| MyBatis-Plus 多租户插件 | **未使用**（无 `TenantLineInnerInterceptor`），仅注册 `PaginationInnerInterceptor` | `MybatisPaginationConfiguration:16-19` |
| 租户上下文 | **无 ThreadLocal**；`AuthContext` record（`user`/`enterpriseId`/`permissions`）随方法参数显式传递 | `model/auth/entity/AuthContext.java` |
| 凭据加密 | AES-256-GCM（12B nonce / 128bit tag）+ AAD 绑定 `credential:{enterpriseId}:{id}`；密钥 32B 随机版本化 | `PayloadEncryption:28-59`、`CredentialService:74-143` |
| 审计防篡改 | **无**。`audit_event` 只追加、有权限查看，无 `hash`/`prev_hash` 列 | `V0__init.sql:405-424` |
| 出网防护 | `OutboundAddressPolicy` 覆盖主路径（禁私网/元数据/IMDS、DNS pinning 防 rebinding、≤3 跳、凭据仅同源转发） | `service/network/` |
| 出网盲区 | `IntegrationHttpClient`（飞书/企微）、`RemoteSandboxExecutor` 系列未接入该策略 | 见 P2-D |
| 生产强校验 | 仅 `@Profile("production")` 生效，7 项 fail-fast | `ProductionSettingsValidation:16-40` |
| schema 契约 | `TargetSchemaContractTest` 严格比对表数/列数/FK 数 | `schema-target.sql` + `data-model.json` |

> **重要约束**：任何新增列必须**三处同步**——Flyway 迁移、`schema-target.sql`、`data-model.json`（列数被断言），否则契约测试红。

---

## 2. 任务清单

| ID | 任务 | 优先级 | 关键内容 | 状态 |
| --- | --- | --- | --- | --- |
| **P2-A** | 审计防篡改哈希链 | P0 | `audit_event` 增 `prev_hash`/`hash`，append 时串联 | 设计中 |
| **P2-B** | 跨租户越权修复 | P0 | `Invitation` token 查询、`ApiRequest` 幂等更新 | 待开始 |
| **P2-C** | 生产强校验清单化 | P1 | 扩大 fail-fast 覆盖面 + 上线清单文档 | 待开始 |
| **P2-D** | 出站 SSRF 盲区补齐 | P1 | `IntegrationHttpClient` / Sandbox 接入 `OutboundAddressPolicy` | 待开始 |
| **P2-E** | 凭据密钥托管强化 | P1 | 禁止生产自动落盘、备份恢复、轮换 | 待开始 |
| **P2-F** | 多租户机制化隔离 | P2 | 评估 `TenantLineInnerInterceptor` + 全局任务豁免清单 | 待开始 |

---

## 3. P2-A 详细设计：审计防篡改哈希链

### 3.1 目标
使 `audit_event` 具备**篡改可检测**能力：任意一条记录被修改或删除，都能通过重算哈希链发现。这是合规审计（GMP / 21 CFR Part 11 类要求）的基础能力。

### 3.2 数据结构（新增列）

```sql
ALTER TABLE `audit_event`
  ADD COLUMN `prev_hash` char(64) DEFAULT NULL COMMENT '同一作用域上一条记录的哈希，作用域首条为空',
  ADD COLUMN `hash`      char(64) DEFAULT NULL COMMENT '本记录防篡改哈希';
```

- **作用域（chain scope）**：按 `enterprise_id` 分链；`enterprise_id IS NULL`（全局身份动作）视为一个独立作用域。
- 分链理由：避免跨企业耦合，便于按企业单独导出与校验；也避免单链成为写入热点。

### 3.3 哈希算法

```
canonical = join('|', [
  id, nullToMarker(enterpriseId), actorUserId, actorName, action,
  objectType, nullToMarker(objectId), result, summary,
  detailRedactedJson, requestId, createdAt(ISO-8601, 毫秒)
])
hash      = SHA-256( nullToMarker(prevHash) + '|' + canonical )
```

- 所有可空字段统一用 `-` 占位，**避免 `NULL` 与空串产生相同摘要**。
- `created_at` 用固定精度格式化，避免时区/精度差异导致摘要漂移。
- `detail_redacted_json` 取规范化后的字符串（写入前已由 `ObjectMapper` 序列化，直接参与）。

### 3.4 并发与串行化（关键取舍）

哈希链要求严格线性，两个并发 append 若读到同一个 `prev_hash` 会造成**链分叉**。

- **方案 A（推荐）**：新增链头表 `audit_event_chain`（`scope_key` PK、`last_event_id`、`last_hash`）。append 时先 `SELECT ... FOR UPDATE` 锁住该作用域链头行，再计算、插入、更新链头。**用行锁把同作用域 append 串行化**，彻底消除分叉。
  - 代价：多一张表（需同步 `schema-target.sql` / `data-model.json`）；每作用域一个链头行需 upsert 初始化。
- **方案 B（简化）**：`SELECT hash ... ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE`。实现简单，但**存在残余竞态**——T2 锁住的是旧尾行 R，T1 提交后新增 R'，T2 仍以 R 为前驱。

> **结论：采用方案 A。** 合规场景下链分叉不可接受；方案 B 只在"检测内容篡改"层面有效，无法保证顺序完整性。

### 3.4.1 落地实现（与原设计的差异，已按实测约束调整）

- **哈希规范化**：字段写成「长度:内容」再拼接，null / 空串 / 恰好等于 `-` 的内容三者不会碰撞；分隔符与转义符先转义。
- **JSON 列**：MySQL 会把 JSON 重新序列化（键间补空格、数字可能改写），因此写入与校验两侧都先用 Jackson 解析再规范化输出，避免摘要漂移。
- **时间精度**：`created_at` 是 `DATETIME(3)`，写入前先截断到毫秒，否则"写入用纳秒、读回被截断"会导致校验必然不一致。
- **事务要求**：链头行锁必须在事务内，否则 `SELECT ... FOR UPDATE` 的行锁在语句结束时就释放。追加逻辑用 `REQUIRED` 事务模板包住（有调用方事务就加入，没有就自开一个）。
- **校验走链接而非时间序**：沿 `prev_hash → hash` 走查，链序等于写入顺序；业务时间只用于显示与筛选，避免时间回拨/并发写入误报。

### 3.5 历史数据回填（迁移策略）

`set-based` SQL **无法**计算 `hash_i = SHA(prev_hash_i || content_i)`（存在递推依赖），因此：

- 迁移**只加列不写数据**（`prev_hash` / `hash` 均为 NULL，表示"尚未封印"）。原计划的 SQL 单条封印已放弃：`SHA2()` 无法表达递推依赖只是其一，更关键的是 JSON 列在 SQL 与 Java 两侧的规范化结果不一致，SQL 算出的摘要无法被 Java 侧校验复现。
- 封印由应用侧用同一套算法完成：启动后按**发生时间升序**把未封印记录接到链尾，每批一个独立事务，中断后下次启动继续（单次启动批次上限默认 20 × 500 条）。
- **已披露的限制**：迁移前遗留记录接在链尾，其链序晚于迁移后的新记录，与业务发生时间顺序不一致；但内容篡改、整行删除、链分叉仍然可检测。
- 未封印记录不通过完整性校验（校验会明确报出"没有任何已封印记录"），因此未回填完成的库不会被误判为"链完整"。

### 3.6 校验能力

提供按作用域的完整性校验：按 `created_at, id` 升序遍历，逐条重算并比对 `prev_hash` 与 `hash`，输出首个不一致点。
- 先以内部服务方法落地 + 单元测试覆盖（正常链 / 篡改内容 / 删除中间行 / 修改 prev_hash 四种场景）。
- 是否暴露为 API 端点（需新增权限，如 `audit.verify`）另议，避免在权限体系未确认前扩大接口面。

### 3.7 验收标准
1. 迁移后 `TargetSchemaContractTest` / `IntegrationSchemaMigrationTest` 仍绿（三处同步已核对）。
2. 连续写入 N 条，`prev_hash` 严格串联，重算全部匹配。
3. 篡改任意字段 / 删除中间行 / 改 `prev_hash`，校验均可定位到具体记录。
4. 并发写入同作用域不产生分叉（方案 A 保证）。

---

## 4. 其余任务要点

**P2-B 跨租户越权**
- `InvitationSqlMapper.findByHashEnterpriseInvitation` 当前**仅按 `token_hash` 全局查询**，是最明确的跨租户入口。邀请 token 本身全局唯一，因此不能简单加 `WHERE enterprise_id`——正确做法是**先全局定位、再校验该邀请所属企业与当前上下文一致**，不一致则拒绝。
- `ApiRequestMapper.completeProcessing`：幂等记录更新无租户条件，需补 `enterprise_id`。

**P2-C 生产强校验清单化**
- 将 `ProductionSettingsValidation` 的 7 项抽取为清单；对**非生产环境也强制**校验密钥类配置（`AGENTEAM_ENCRYPTION_KEYS`、`AGENTEAM_REQUEST_SIGNING_KEY`），避免"本地能跑、上线炸"。
- 输出 `docs/deployment/production-checklist.md`。

**P2-D 出站盲区**
- `IntegrationHttpClient`：渠道地址来自配置/平台常量，需接入 `OutboundAddressPolicy` 或给出书面豁免 + 补偿控制（配置白名单校验）。
- `RemoteSandboxExecutor` 系列：已有 https/token 校验，缺 IP 层私网校验，需评估沙盒出网由 `SANDBOX_NETWORK` 控制是否足够。

**P2-E 凭据密钥**
- 现状：密钥缺失时自动生成并落盘应用目录（与 DB 同机）、无备份、无自动轮换。
- 方向：生产强制外部注入（拒绝自动生成落盘）；补备份/恢复流程；评估轮换调度。

**P2-F 多租户机制化**
- 引入 `TenantLineInnerInterceptor` 需先产出**全局任务豁免清单**（`ExecutionEventSqlMapper`、`RunJobSqlMapper`、`BackgroundJobSqlMapper` 等按设计不带 `enterprise_id` 的后台扫描）。
- 该类查询正确的加固方向是"任务行携带 `enterprise_id` + 按租户分发"，而非简单加 `WHERE`。
- 先出评估报告，再决定是否实施。

---

## 5. 风险与取舍

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| schema 三处不同步 | 契约测试红、CI 挂 | 每次加列必须同步迁移 / `schema-target.sql` / `data-model.json` |
| 哈希链并发分叉 | 防篡改能力打折 | 采用方案 A 链头表行锁串行化 |
| 历史区间无法检测删除 | 合规声明需留口 | 交付说明显式披露 + 可选历史链重建脚本 |
| `TenantLineInnerInterceptor` 误伤全局任务 | 后台任务失效 | 评估先行，产出豁免清单 |
| `mvnw` 可执行位再次丢失 | CI 后端作业 0 秒失败 | 已在 v0.2.0 修复（100755）；Windows 上新建脚本需显式 `--chmod=+x` |
