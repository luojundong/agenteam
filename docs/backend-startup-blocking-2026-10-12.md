# 后端启动阻塞与补齐清单（AgenTeam 后端 · 静态评估）

> 评估日期：2026-10-12
> 评估方式：仅静态分析（阅读 `agenteam-server/pom.xml`、`src/main/resources` 配置、Flyway 脚本、`deploy/*`），
> **未运行任何后端命令、未改动后端源码**（本机缺 Java 21 / Docker，无法运行验证，改了也测不了）。
> 评估人：软件工程师「寇豆码」

---

## 1. 后端完整跑起来需要什么

| 组件 | 要求 | 本机现状 | 是否阻塞 |
|---|---|---|---|
| JDK | **Java 21** | 仅 JDK 17.0.20.1（Temurin） | ❌ 阻塞 |
| 构建工具 | Maven 3.9.x 或项目自带 `./mvnw` | `mvnw`/`mvnw.cmd` 存在（wrapper 3.3.4，`distributionType=only-script`） | ⚠️ 首次需联网下载 Maven 发行包 |
| 容器运行时 | Docker + Docker Compose | 未安装 | ❌ 阻塞 |
| 依赖服务 | MySQL（业务数据）、Redis（会话/实时对话）、sandbox 镜像 | 无（依赖 Docker 启动） | ❌ 随 Docker 缺失而阻塞 |
| 配置文件 | `deploy/.env`（含随机密码 + `SETUP_CREDENTIAL`） | 未生成（仅有 `.env.example`） | ⚠️ 需先跑 configure 脚本 |
| 前端（联调） | Node 24 / pnpm 11.15.1（README 推荐；本机 Node 22.22.2 / pnpm 10.32.1 已可用） | 已 `pnpm install` 成功 | ✅ 不阻塞后端 |

### 关键说明
- **Maven wrapper 不需要 `maven-wrapper.jar`**：`pom.xml` 同级 `.mvn/wrapper/` 下只有 `maven-wrapper.properties`（`distributionType=only-script`）。`mvnw`/`mvnw.cmd` 脚本会**直接下载 `apache-maven-3.9.16-bin.zip`**，不依赖 jar 文件。故“缺少 jar”不是问题；真正的缺口是 Java 21 与 Docker。
- **首次运行 `./mvnw` 需联网**：下载 Maven 发行包到 `~/.m2/wrapper/dists/`（仅一次，可离线缓存复用）。

---

## 2. 关键配置与环境变量

- **应用配置入口**：`src/main/resources/application.yaml`，按 `SPRING_PROFILES_ACTIVE` 叠加 `application-container.yaml` / `application-production.yaml` / `application-recovery.yaml`。
- **本地覆盖**：`spring.config.import=optional:file:./.agenteam/local.properties`（可选，用于本机覆盖）。
- **Flyway 自动建表**：`flyway.locations=classpath:db/schema`，`clean-disabled=true`，`validate-on-migrate=true`。
  现有 5 个迁移脚本：`V0__init.sql`、`V0.1.0.1`、`V0.1.1.1`、`V0.1.1.2`、`V0.2.0.1__`——首次启动由 Flyway 自动顺序执行建表，已有库保留业务数据。
- **数据库连接**（默认回退值）：
  - `AGENTEAM_DB_URL` → `jdbc:mariadb://localhost:43306/agenteam?createDatabaseIfNotExist=true&...`
  - `AGENTEAM_DB_USERNAME` → `root`（Docker 部署时由 compose 设为 `agenteam`）
  - `AGENTEAM_DB_PASSWORD` → `123456`
  - 驱动：`org.mariadb.jdbc.Driver`
- **Redis 连接**（默认回退值）：
  - `AGENTEAM_REDIS_HOST` → `localhost`；`AGENTEAM_REDIS_PORT` → `46379`；`AGENTEAM_REDIS_PASSWORD` → `123456`
  - 用途：登录会话（`spring-session`）、对话实时事件（Redis Stream）。
- **首次初始化凭据**：`AGENTEAM_SETUP_CREDENTIAL`（写入 `deploy/.env`），用于创建管理员/企业，不打印到终端。
- **安全密钥**：`AGENTEAM_REQUEST_SIGNING_KEY`、`AGENTEAM_ENCRYPTION_KEYS` 留空时自动生成并保存到 `security/application-keys.properties`。

---

## 3. 默认端口

| 服务 | 容器内端口 | 主机端口 | 备注 |
|---|---|---|---|
| MySQL | 3306 | **43306** | `compose.dev.yaml` 限定 `127.0.0.1` |
| Redis | 6379 | **46379** | 同上 |
| 后端（Spring Boot） | 8080 | 8080 | 应用 HTTP 端口 |
| 沙盒执行器 | 8092 | 容器内回环 | `AGENTEAM_SANDBOX_REMOTE_URL=http://127.0.0.1:8092/` |
| 管理端点 | — | `-1`（关闭独立端口） | actuator 仅在容器内 `9091` 做健康探针 |

---

## 4. 环境就绪后的首次设置流程（精确命令）

> 前置：安装 **Java 21** 并设为默认 JDK；安装 **Docker Desktop（Linux 容器模式）** 与 Docker Compose。

**步骤 1 — 生成 `deploy/.env`（随机密码 + SETUP_CREDENTIAL）**
```sh
# Windows (PowerShell，项目根 deploy 目录)
powershell -ExecutionPolicy ByPass -File configure.ps1 single      # 或 split
# Linux / macOS
sh configure.sh single                                            # 或 split
```
脚本读取 `.env.example`：替换 `DEPLOY_MODE`/`PUBLIC_URL`，并为
`DB_PASSWORD`、`DB_ROOT_PASSWORD`、`REDIS_PASSWORD`、`SANDBOX_TOKEN`、`SETUP_CREDENTIAL`
生成随机值；若 `.env` 已存在则**拒绝覆盖**（需手动编辑）。

**步骤 2 — 启动依赖服务（开发模式只起 mysql + redis）并构建沙盒镜像**
```sh
cd deploy
docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis
docker build -t agenteam/sandbox-office:local sandbox
```

**步骤 3 — 启动后端**
```sh
cd agenteam-server
# 把 .env 中的密码导出为后端所需变量（Windows PowerShell 示例）
$env:AGENTEAM_DB_USERNAME="agenteam"
$env:AGENTEAM_DB_PASSWORD=(Select-String '^DB_PASSWORD=' .\..\deploy\.env | ForEach-Object { $_.Line.Split('=')[1] })
$env:AGENTEAM_REDIS_PASSWORD=(Select-String '^REDIS_PASSWORD=' .\..\deploy\.env | ForEach-Object { $_.Line.Split('=')[1] })
.\mvnw.cmd spring-boot:run        # Linux/macOS: ./mvnw spring-boot:run
```
Flyway 自动建表，后端监听 `8080`。

**步骤 4 — 启动前端（详见 `docs/startup-status-2026-10-12.md`）**
```sh
cd agenteam-web
pnpm install --frozen-lockfile
pnpm dev                          # 默认 AGENT_BACKEND_URL=http://localhost:8080
```

**步骤 5 — 首次初始化**
浏览器打开前端（默认 `http://localhost:3000`），使用 `deploy/.env` 中的
`SETUP_CREDENTIAL` 完成管理员与企业创建。

---

## 5. 补充结论

- **本机缺失项（阻塞）**：Java 21（硬阻塞编译/运行）、Docker（硬阻塞 MySQL/Redis/sandbox）。
- **本机缺失项（非硬阻塞，但需处理）**：`deploy/.env` 未生成（需跑 configure）、Maven 发行包首次需联网下载。
- **未改动任何后端源码**（遵循约束：无法运行验证，改了也无法测试）。
- 仅静态分析，未执行任何 `mvnw` / `docker` 命令。
