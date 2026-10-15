# 生产部署检查清单

面向 `production` profile。启动时由 `ProductionSettingsValidation` 逐条执行，
**一次报出全部未通过项**（形如 `生产配置未就绪（共 3 项）：...`），不修完不提供服务。

清单与代码一一对应，改动任一侧都要同步另一侧：

| 编号 | 配置项 | 要求 | 为什么 |
| --- | --- | --- | --- |
| `PUBLIC_ORIGIN` | `agenteam.web.public-base-url` | 必须是 HTTPS，且不带路径、查询参数、片段、用户信息 | 用于生成邀请与找回密码链接；带路径会让链接拼错，非 HTTPS 会让凭据走明文 |
| `SESSION_COOKIE` | `server.servlet.session.cookie.secure` | 必须为 `true` | 否则会话 Cookie 会随明文请求发出 |
| `TRUSTED_PROXIES` | `server.tomcat.remoteip.internal-proxies` | 逐条填写实际地址，IPv4 用 `/32`、IPv6 用 `/128` | 写成网段（如 `10.0.0.0/8`）或正则（`.*`）等于信任整段来源，`X-Forwarded-For` 可被伪造 |
| `MAIL_HOST` | `spring.mail.host` | 必须配置 | 邀请与找回密码依赖邮件 |
| `MAIL_FROM` | `agenteam.mail.from` | 必须是合法邮箱地址 | 非法发件人会被对端拒收，且问题要到发信时才发现 |
| `SIGNING_KEY` | `agenteam.security.request-signing-key` | 必须能取得 32 字节密钥 | 请求摘要密钥不可用会让写操作无法提交 |
| `ENCRYPTION_KEY` | `agenteam.security.encryption-keys` + `agenteam.security.active-encryption-key` | 必须包含当前版本的 32 字节 AES 密钥 | 内容加密密钥缺失会让已加密内容无法读写 |

## 1. 由 Spring 占位符隐式强制的配置

`application-production.yaml` 里这些变量**没有默认值**，未提供时占位符无法解析，启动直接失败，
因此不重复进清单，但部署时必须提供：

```
AGENTEAM_DB_URL / AGENTEAM_DB_USERNAME / AGENTEAM_DB_PASSWORD
AGENTEAM_REDIS_HOST / AGENTEAM_REDIS_PORT / AGENTEAM_REDIS_PASSWORD
AGENTEAM_MAIL_HOST / AGENTEAM_MAIL_USERNAME / AGENTEAM_MAIL_PASSWORD
AGENTEAM_PUBLIC_ORIGIN / AGENTEAM_MAIL_FROM / AGENTEAM_TRUSTED_PROXIES
```

## 2. 应用密钥

密钥缺失时（非生产环境）会自动生成并保存到应用目录下的 `.agenteam/security/application-keys.properties`。
这仅适用于本地开发：该文件与数据库同机保存、没有备份、多实例不共享，**换机或重建容器后
用旧密钥写入的内容将无法解开**。

- **非生产环境**：自动生成，启动时打印 WARN，说明当前正在使用本机生成的密钥。
- **生产环境**：**必须外部注入，缺少任一密钥即拒绝启动**。
  判定发生在写文件之前，因此生产目录里不会留下自动生成的密钥文件。
  缺少时启动报 `IllegalArgumentException` 并逐一点名缺少的环境变量。

### 2.1 注入格式

Base64 编码的 32 字节随机值；`encryption-keys` 为 JSON 对象（`{"v1":"<base64>"}`），
键为版本名（1~64 位 `[A-Za-z0-9_-]`），且必须包含 `active-encryption-key` 指定的版本。

生成一条新密钥：

```bash
openssl rand -base64 32
```

### 2.2 备份与恢复

**密钥必须备份，且备份要与数据备份同等对待。** 丢失 AES 密钥后，以下数据永久无法恢复：

- `key_version` 列指向该版本的加密业务内容；
- 依赖请求签名密钥的下载令牌与分页游标（同样不可重算）。

备份做法：把注入的密钥值存入密钥管理服务或离线保险库（密码管理器、HashiCorp Vault、
K8s Secret 的带备份 etcd 等）。**不要只留一份在部署脚本里**——脚本重写或环境重建即丢失。

恢复要点：把原值原样注入回同名环境变量，版本名一并保持一致。已加密内容按自身记录的
`key_version` 取对应版本解密，因此恢复时**版本号不能改**，改了等于指向另一条密钥。

### 2.3 轮换

多版本共存的能力是轮换的基础：写入一律用
`active-encryption-key` 指定的版本，读取按每条密文自带的版本号取对应密钥。
因此**旧版本在存量内容淘汰前不能删除**。

步骤：

1. 生成新版本密钥（例如 `v2`）；
2. 把旧版本与新版本一起写进 `AGENTEAM_ENCRYPTION_KEYS`：`{"v1":"<旧>","v2":"<新>"}`；
3. 把 `AGENTEAM_ACTIVE_ENCRYPTION_KEY` 改为 `v2`；
4. 滚动重启。此后新增内容用 `v2`，`v1` 的存量内容仍可读——这一点由
   `ApplicationSecretKeysTest#rotatedActiveVersionKeepsPreviouslyEncryptedContentReadable`
   锁定，同一用例同时验证了反例：**一旦从配置里摘掉 `v1`，存量内容立刻读不出来**。
5. 待确认无 `v1` 内容后，才可从配置中移除 `v1`。

请求签名密钥（`AGENTEAM_REQUEST_SIGNING_KEY`）是**单版本、无版本号**的：换它的代价是
已签发的下载令牌与分页游标全部失效（用户需重新获取），不像内容加密那样可以平滑过渡。
轮换前需评估该影响窗口。

## 3. 初始化凭据

`AGENTEAM_SETUP_CREDENTIAL` **不要求配置**：未配置时首次启动会生成随机值，
**只在启动日志中打印一次**（不落盘），重启会换新值。这是有意的设计——
比配置一个可能长期不变的弱凭据更安全。

部署时二选一：

1. 不配置，从首次启动日志里取凭据，完成初始化后即失效；
2. 预先注入长度 16~256 的固定值，便于自动化部署。

若未完成初始化就重启后端，之前打印的凭据作废，需要重新查看日志。

## 4. 上线前核对

- [ ] 上述清单 7 项全部通过（启动日志无 `生产配置未就绪`）。
- [ ] 密钥已外部注入，启动日志**没有**出现"正在使用自动生成的本机密钥文件"。
- [ ] 注入的密钥值已存入密钥管理服务或离线保险库，且版本名与注入值一并留存。
- [ ] `AGENTEAM_TRUSTED_PROXIES` 填的是实际反向代理地址，不是网段。
- [ ] 管理端口（`AGENTEAM_MANAGEMENT_PORT`，默认 9091）绑定在本机或运维网络，
      未经过面向用户的代理暴露。
- [ ] `springdoc`（OpenAPI 文档与 Swagger UI）在生产配置中已关闭。
- [ ] 数据库与 Redis 使用独立凭据，非开发默认值。
