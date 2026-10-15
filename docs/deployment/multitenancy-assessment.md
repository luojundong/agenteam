# P2-F 多租户机制化隔离评估报告

结论：**不建议引入 `TenantLineInnerInterceptor`**，改为建立**机器可校验的豁免登记表**作为长期控制手段。
理由不是"改造难"，而是测量后发现它能保护的部分已经受保护，真正需要它保护的部分它恰好管不到。

## 1. 测量范围与结果

对持久层做了全量清点（不是抽样）：

| 类别 | 语句/方法数 | 已带租户条件 | 无租户条件 |
| --- | --- | --- | --- |
| MyBatis XML 语句 | 163 | 136 | 27 |
| Java 侧 Wrapper 方法 | 264 | 232 | 32 |

XML 必须先内联 `<include refid>` 引用的 `<sql>` 片段再判定，否则租户条件藏在片段里会被漏掉
（这一坑在 P2-B 中曾导致 `todo_item`、`resource` 被误判为越权）。

## 2. 27 条无租户条件的 XML 语句分类

**触及本身带 `enterprise_id` 的表（15 条）**——即必须豁免清单：

| 映射文件 | 语句 | 涉及表 |
| --- | --- | --- |
| `BackgroundJobSqlMapper.xml` | `lockOwnedBackgroundJob` | `background_job` |
| `RunJobSqlMapper.xml` | `claimBackgroundJob` / `expiredAndLockedBackgroundJob` / `lastEventBatchBackgroundJob` / `savedEventBatchBackgroundJob` | `background_job` |
| `ExecutionEventSqlMapper.xml` | `appendAgentEvent` | `agent_event` |
| `OperationalStateSqlMapper.xml` | `readBackgroundJob` / `readAgentRun` / `readAgentEvent` | `background_job` / `agent_run` / `agent_event` |
| `ApiRequestMapper.xml` | `findLocked` | `api_request` |
| `IdentityRetentionSqlMapper.xml` | `expiredRequestIds` | `api_request` |
| `AgentHireApplicationSqlMapper.xml` | `expireAgentHireRequest` | `agent_hire_request` |
| `MemorySqlMapper.xml` | `expireAgentMemory2` | `agent_memory` |
| `ToolCallSqlMapper.xml` | `failAbandonedManualReadsToolCall` | `tool_call` |
| `WorkspaceProjectSqlMapper.xml` | `conflictingDirectory` | `workspace_project` |

**其余 12 条**涉及本身没有 `enterprise_id` 列的表：`auth_token`、`app_user`、`enterprise`、
`system_super_admin_lock`、`INFORMATION_SCHEMA`。这些是全局身份表与企业主键本身，
本来就跨租户，不属于缺陷。

Java 侧 32 个方法同理，绝大多数是按用户主键（`updateProfile`、`updateVerifiedEmail`、
`updatePreference`）或后台基础设施（`BackgroundJob`、`RunJob`、`IdentityRetention`、
`OperationalState`）组织的。

## 3. 为什么拦截器不合适

`TenantLineInnerInterceptor` 的收益面 = "当前忘记写租户条件的地方"。但测量发现：

- **已有租户条件的 136 + 232 条**：拦截器加了也不会更正确，只是把同一个条件写两遍。
- **没有租户条件的地方（27 + 32 条）**：几乎全是**后台任务扫描、全局身份表、运维聚合**。
  这些正是必须放进豁免清单的部分——**拦截器对它们一分钱保护都给不了**。

更关键的是运行前提：拦截器依赖一个 `ThreadLocal` 租户上下文，而上下文来自请求。
后台任务在调度线程上运行，**没有 HTTP 请求、没有租户上下文**，因此它们在构造上就必须
被豁免。于是形成了一个自我否定的结构：

> 能被拦截器保护的语句已经带着租户条件；缺租户条件的语句按其性质必须被豁免。

同时它还引入一个新的全局失效模式：上下文没设或设错时，拦截器会静默改写 SQL，
要么返回错误数据，要么让后台任务全线报错。这是一类比"某条查询漏了条件"更难排查的风险。

## 4. 建议的替代控制

把"人工复核"变成"机器校验"——这才是持久的收益：

1. **豁免登记表**：把第 2 节那 15 条（以及判定为全局表的 12 条）登记为常量清单。
2. **校验测试**：断言"无租户条件的语句集合 == 豁免清单"。
   今后任何新增的无租户条件查询都会让该测试失败，**强制开发者显式登记并说明原因**，
   而不是悄悄混进代码。
3. 清单条目需写明**为什么可以没有租户条件**（后台任务扫描 / 全局身份表 / 主键本身），
   并标注将来的加固方向：任务行携带 `enterprise_id` + 按租户分发，
   而不是简单补一条 `WHERE`（补了会让后台扫描永远扫不到这批数据）。

> 这与 P2-B 的处置思路一致：P2-B 扫出的 3 个入口里，最终只有 1 个是真缺陷。
> 数量本身不代表风险，逐条定性才是。

## 5. 逐条复核中确认无问题但值得记录的结论

为避免后续重复排查，这几处经过人工确认属于**安全**：

- **`FileSqlMapper.referencesKeyFileObject(key)`**：按 `storage_key` 计数，看似跨租户。
  但存储键由服务端构造为 `enterprise + "/" + UUID + ".data"`（`FileContentStorage`），
  且写入前经 `FileStorageKeys.valid` 正则校验、读取前校验前缀属于本企业。
  因此一个租户在构造上无法取得他租户的存储键，**不构成存在性探针**。返回的是计数而非数据。
- **`OperationalStateMapper.read`**：跨租户聚合，但只产出 `Map<String, Double>` 指标计数，
  经由 `OperationalMetrics` 供运维指标使用，不含租户数据。
- **邀请 token 全局查询**：见 P2-B 结论，接受邀请者本就在企业外，无上下文可比对；
  `token_hash` 有唯一键，后续 `matchEmail` 强制邮箱一致。已书面豁免。
