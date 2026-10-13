# AgenTeam 前端登录后主流程冒烟报告

- 日期：2026-10-12
- 环境：本机（前端 3900 / 后端 9080 / MariaDB 43306 / Redis 46379 / 管理 19091）
- 账号：`admin` / `AgenTeam@Local2026`（超管，企业 `AgenTeam Dev`）
- 测试路径：全部经前端代理 `http://localhost:3900/api/v1/...` → 后端 9080（验证前后端代理链路）
- enterpriseId：`4c7e6e51-912b-4919-9815-96d07bb036a7`

## 结论

**集成链路全绿**：前端代理 → 后端 → DB/Redis 全部打通；鉴权、CSRF、幂等键（Idempotency-Key）防护均按预期工作。
**唯一阻塞**：AI 执行链路（智能体→会话→运行）因全新本地库**未种子任何模型档案 / 智能体**而无法跑通。模型档案接口仅 `GET`、无 API 创建入口，须先配置 AI provider（外部密钥或本地/mock provider）后才能解锁。

## 一、认证与主流程读路径（全部 200）

| 步骤 | 请求 | 结果 |
|---|---|---|
| CSRF 获取 | GET /api/v1/auth/csrf | 200 |
| 登录 | POST /api/v1/auth/login（`identifier`+`password`，非 `username`） | 200，写入 `SESSION` |
| 当前身份 | GET /api/v1/auth/me | 200 |
| 企业上下文 | GET /enterprises/{id}/context | 200 |
| 企业详情 | GET /enterprises/{id} | 200 |
| 成员列表 | GET /enterprises/{id}/members | 200 |
| 工作台首页 | GET /enterprises/{id}/home | 200 |
| 搜索 | GET /enterprises/{id}/search?query=test | 200 |
| 资源-智能体 | GET /resources?kind=agent | 200（0 条） |
| 资源-技能/工作流/知识库 | GET /resources?kind=skill\|workflow\|knowledge | 200（各 0 条） |
| 会话列表 | GET /conversations | 200（0 条） |
| 项目列表 | GET /projects | 200 |
| 模型档案 | GET /model-profiles | 200（0 条） |
| 通知列表/未读 | GET /notifications, /unread-count | 200 |
| 用量 | GET /usage | 200 |
| 工作区技能 | GET /workspace/skills | 200 |
| 待办 | GET /todos | 200 |

## 二、写路径防护（可达且按预期拦截）

| 步骤 | 请求 | 结果 | 说明 |
|---|---|---|---|
| 选择企业 | POST /enterprises/{id}/select（需 `Idempotency-Key`） | 200 | 不带幂等键 → 400「缺少有效的请求编号」 |
| 创建资源(智能体) | POST /resources（kind=agent） | 422 | 缺 `config.modelProfileId`（无模型档案可填） |
| 创建会话 | POST /conversations（带 CSRF+Idempotency-Key，无效 agent） | 404 RESOURCE_NOT_FOUND | 路由/鉴权/幂等全正常，仅数据缺失 |

## 三、阻塞与解锁步骤

1. **缺模型档案**：`GET /model-profiles` 返回空，且 `/model-profiles` 无创建接口 → 需先配置 AI provider（外部模型密钥，或本地/mock provider）以产生模型档案。
2. **缺智能体**：无模型档案 → 无法创建智能体 → 无法创建会话/触发运行。
3. 解锁执行链路的下一步：配置一个可用的模型 provider 并生成模型档案，随后即可 `POST /resources` 建智能体 → `POST /conversations` 开会话 → `POST .../messages` 触发运行。

## 四、认证注意点（与前端约定一致）

- 登录字段为 `identifier`（用户名或已验证邮箱），**不是** `username`。
- 所有状态变更（POST/PUT/PATCH/DELETE）必须带：
  - `X-CSRF-Token`（取自 `/api/v1/auth/csrf`）
  - `Idempotency-Key`（16–100 位可打印 ASCII，建议 UUID）
- 会话 Cookie：`SESSION`（HttpOnly）。

## 五、AI 执行链路（本地 mock provider 配置 + 全绿）

> 目标：解锁前文「三」的阻塞项——配置一个本地/mock 模型 provider 并生成模型档案，把「智能体 → 会话 → 运行」整条链路跑成全绿。

### 5.1 本地 mock 模型服务（新增，非仓库文件）

仓库模型调用走 `OpenAIChatModel`（`stream=true`）→ `POST {baseUrl}/chat/completions`(SSE) + `GET {baseUrl}/models`。
provider 的 `protocol` 仅允许 `openai`；`baseUrl` 校验对 loopback 放行 `http`（仅 `127.0.0.1`，不含 `localhost`），非 loopback 必须 `https`。

新增 `C:/Users/Admin/agenteam-runtime/mock-openai-server.py`：
- 监听 `127.0.0.1:18080`
- `GET /v1/models` → 返回 OpenAI 列表，`data[].id = "mock-local-1"`（context_length=32768）
- `POST /v1/chat/completions` → 同时支持 `stream=true`(SSE, `data: {...}\n\n` 结尾 `data: [DONE]`) 与 非流式；回显用户消息并标注「本地 mock 离线模型」
- 启动：`python mock-openai-server.py`（后台），已验证 `:18080/v1/models` 可达

### 5.2 配置 provider 与生成模型档案

| 步骤 | 请求 | 结果 |
|---|---|---|
| 创建模型 provider | POST /model-providers（`protocol=openai`, `baseUrl=http://127.0.0.1:18080/v1`, `apiKey` 任意, `enabled=true`） | 201，providerId=`a666b80a-cf73-4e28-9dd1-2d267f6381ac` |
| 生成模型档案 | POST /models（providerId 同上, `modelName=mock-local-1`, capabilities: supportsTools=false, supportsTemperature=true, maxOutputTokens=4096, maxContextTokens=32768, inputTypes=[text]） | 201，modelProfileId=`d64108e5-eac0-47a1-91b7-afd1354a0e61` |

### 5.3 执行链路（全绿）

| 步骤 | 请求 | 关键头 | 结果 |
|---|---|---|---|
| 创建智能体(草稿) | POST /resources（kind=agent, `config.modelProfileId` 必填） | CSRF + Idempotency-Key | 201，agentId=`56ec0c57-ea73-4f57-935a-3c5fa6cdcf0c` |
| 发布 | POST /resources/{id}/publish（`{"releaseNote":"冒烟测试发布 v1"}`，`listing` 不可为 null） | CSRF + Idempotency-Key + `If-Match:"1"` | 201，publishedVersion=`6ce86b8c-e92a-4376-b975-0a48fc2d51fe`（v1 available） |
| 上架（listing） | PUT /agents/{id}/listing（`{"listed":true,"hirePolicy":"automatic"}`） | CSRF + Idempotency-Key + `If-Match:"2"` | 200，`listed=true`（**修复 `AgentListingMapper.xml` 后**） |
| 雇佣 | POST /hires（`{"agentId":...}`） | CSRF + Idempotency-Key | 201，status=active（雇佣要求 `listed=true`，上架是前置） |
| 创建会话 | POST /conversations | CSRF + Idempotency-Key | 201，conversationId=`9964fe1b-7a7f-488d-b928-58d0e00ab94f` |
| 首轮消息 | POST /conversations/{id}/messages | CSRF + Idempotency-Key | 202 排队 → run=`efecced2-...` → **completed**（stepErrors=None，assistant 已回） |
| 多轮消息 | POST /conversations/{id}/messages（第二轮） | CSRF + Idempotency-Key | 202 排队 → run=`fb670ee4-61ba-485c-897d-771bfac0d387` → **completed**（无步错） |

最终会话含 4 条消息（2 用户 + 2 assistant），两轮 assistant 回复均来自本地 mock 模型：
`「（本地 mock 模型回复）已收到你的消息：…。这是 AgenTeam 本机冒烟测试用的离线模型，未连接任何外部服务。」`

**结论：集成链路 + AI 执行链路均已全绿（含多轮对话）。**

### 5.4 过程 Bug 修复：AgentListingMapper.xml（MariaDB 语法不兼容）

- 现象：上架 `PUT /agents/{id}/listing` 返回 `500 BadSqlGrammarException`，SQL 报错在 `... AS proposed ON DUPLICATE KEY UPDATE ...`
- 根因：原 mapper 使用 MySQL 8.0.19+ 的行别名语法 `INSERT ... VALUES(...) AS proposed ON DUPLICATE KEY UPDATE proposed.x = ...`，MariaDB 不支持行别名，应改用 `VALUES(col)` 形式。
- 修复（已改 `src/main/resources/mapper/agent/AgentListingMapper.xml`）：
  ```xml
  VALUES (#{enterpriseId}, #{agentId}, #{listed}, #{hirePolicy}, #{actor}, #{now}, #{now})
  ON DUPLICATE KEY UPDATE listed = VALUES(listed), hire_policy = VALUES(hire_policy),
  updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)
  ```
- 验证：重启后端（重载 mapper）后上架返回 200、`listed=true`，雇佣与后续会话运行链路打通。
- 同类风险：Flyway 迁移曾用 `DROP CHECK`(MySQL 专属, MariaDB 需 `DROP CONSTRAINT`)，本次未再触发；建议统一以 MariaDB 语法为准做 CI 校验。

### 5.5 复跑注意事项（踩坑记录）

- CSRF：令牌存于会话属性 `agenteam.auth.csrf-token`，`verify()` 用 `getSession(false)` + 常量时间比较。同一 SESSION 多次取 CSRF 返回同一令牌；若会话 Cookie 提取失败导致每次新建匿名会话，则令牌每次变化 → `403 CSRF_INVALID`。**务必用正确方式提取 HttpOnly `SESSION` Cookie**（Netscape cookie 文件为 Tab 分隔，取末字段）。
- 幂等：`Idempotency-Key` 每次请求用新 UUID（16–100 可打印 ASCII）。
- 乐观锁：发布/上架等资源写操作需 `If-Match:"<revision>"`（发布前 revision=1，上架前=2）。
