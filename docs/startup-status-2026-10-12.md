# 启动状态报告（AgenTeam · 2026-10-12）

> 负责人：软件工程师「寇豆码」
> 环境：Windows（Git Bash）、Node v22.22.2、pnpm 10.32.1（corepack 自动取 11.15.1）、JDK 17.0.20.1、Docker 未安装。

---

## TL;DR

**前端已可完整构建（install / tsc / lint / build 全部通过，零代码改动）；后端被环境硬阻塞，无法在本机运行——缺 Java 21 与 Docker（MySQL/Redis/沙盒镜像起不来）。** 待装齐 Java 21 + Docker 后，按本文档命令序列即可一键启动。

---

## 1. 哪些能跑 / 哪些被阻塞

### ✅ 前端（agenteam-web）—— 全部通过，无需改动
| 检查项 | 命令 | 结果 |
|---|---|---|
| 依赖安装 | `pnpm install` | ✅ 成功（解析 907 包，corepack 取 pnpm 11.15.1，`--frozen-lockfile` 等价的锁文件校验通过） |
| 类型检查 | `pnpm exec tsc --noEmit` | ✅ 退出码 0，无错误 |
| 代码规范 | `pnpm lint`（ESLint 9，配置 `eslint.config.mjs`） | ✅ 退出码 0，无错误 |
| 生产构建 | `pnpm build`（Next.js 16.3.2 Turbopack） | ✅ 退出码 0，`Compiled successfully`，33 条路由全部产出 |

> 说明：`next-env.d.ts` 由 `next build` 自动生成；`.next/` 构建产物已生成。
> ESLint 配置中 `eslint-config-next/core-web-vitals` 与 `eslint-config-next/typescript` 两个子路径在 `eslint-config-next@16.3.2` 下**可正常解析**，无需修配置。

### ❌ 后端（agenteam-server）—— 环境硬阻塞，未运行
| 阻塞项 | 现状 | 影响 |
|---|---|---|
| **Java 21** | 仅 JDK 17.0.20.1 | `pom.xml` 中 `java.version=21`，编译与运行均失败（Spring Boot 4 也需较新 JDK） |
| **Docker / Compose** | 未安装 | MySQL（43306）、Redis（46379）、sandbox 镜像无法启动，后端连不上库与缓存 |

详见配套文档：`docs/backend-startup-blocking-2026-10-12.md`

---

## 2. 环境就绪后的精确启动命令序列（一键启动）

> 前置：安装 **Java 21**（设为默认 JDK）、**Docker Desktop（Linux 容器）** + Docker Compose。

```sh
# —— 1. 生成 deploy/.env（随机密码 + SETUP_CREDENTIAL）——
# Windows PowerShell（在 deploy 目录）：
powershell -ExecutionPolicy ByPass -File configure.ps1 single
# Linux/macOS：
# sh configure.sh single

# —— 2. 启动依赖服务 + 构建沙盒镜像（在 deploy 目录）——
cd deploy
docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis
docker build -t agenteam/sandbox-office:local sandbox

# —— 3. 启动后端（在 agenteam-server 目录，导出密码变量）——
cd ../agenteam-server
# PowerShell 示例：
$env:AGENTEAM_DB_USERNAME="agenteam"
$env:AGENTEAM_DB_PASSWORD=(Select-String '^DB_PASSWORD=' ..\deploy\.env | %{ $_.Line.Split('=')[1] })
$env:AGENTEAM_REDIS_PASSWORD=(Select-String '^REDIS_PASSWORD=' ..\deploy\.env | %{ $_.Line.Split('=')[1] })
.\mvnw.cmd spring-boot:run
# Linux/macOS：./mvnw spring-boot:run

# —— 4. 启动前端（在 agenteam-web 目录）——
cd ../agenteam-web
pnpm install --frozen-lockfile
pnpm dev        # 默认 AGENT_BACKEND_URL=http://localhost:8080

# —— 5. 浏览器打开 http://localhost:3000，用 deploy/.env 的 SETUP_CREDENTIAL 完成首次初始化 ——
```

端口速查：MySQL 43306、Redis 46379、后端 8080、前端 3000（split 模式经 nginx 对外 8088）。
前端通过 `AGENT_BACKEND_URL`（默认 `http://localhost:8080`）连接后端。

---

## 3. 所做的代码 / 配置修复

**本次未修改任何文件。** 前端在开箱状态下即通过 install / tsc / lint / build 全部校验，
不存在需要修复的 TypeScript 编译错误、ESLint 配置错误或构建阻塞问题。
遵循“最小、可回滚”原则，无改动即是最稳妥的结果。

（如后续确需调整，所有修复均放在 `agenteam-web/` 内，且不会升级 Next/React 主版本；
任何非平凡改动会随附回滚方式说明。）

---

## 4. 后端阻塞清单要点（摘抄自 `docs/backend-startup-blocking-2026-10-12.md`）

1. **缺 Java 21**：`pom.xml` 固定 `java.version=21`，本机仅 JDK 17 → 编译/运行双失败。
2. **缺 Docker**：MySQL、Redis、沙盒镜像都依赖容器；无 Docker 则后端无法连库连缓存。
3. **Maven wrapper 可用**：`mvnw` 用 `only-script` 模式直连下载 `apache-maven-3.9.16`，**不需要** `maven-wrapper.jar`（仓库里只缺 jar 属正常，非问题）。
4. **需先生成 `deploy/.env`**：跑 `configure.ps1` / `configure.sh`，生成 DB/Redis 随机密码与 `SETUP_CREDENTIAL`；缺失时 compose 直接报“请先运行配置初始化脚本”。
5. **Flyway 自动建表**：`db/schema` 下 5 个迁移脚本首次启动自动执行。
6. 未改动后端源码（无法运行验证，改了也测不了）。

---

## 5. 产出文档绝对路径

- 启动状态报告（本文件）：`D:/wwwroot/agenteam-main/docs/startup-status-2026-10-12.md`
- 后端启动阻塞与补齐清单：`D:/wwwroot/agenteam-main/docs/backend-startup-blocking-2026-10-12.md`
