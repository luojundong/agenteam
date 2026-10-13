# AgenTeam 构建与运行前置要求

> 目标：让贡献者与运维不再被环境卡住（产品经理升级思路 WS4）。CI 已按本文件钉定的版本运行（`ci.yml` / `community-checks.yml`）。

## 1. 构建基线（强制）

| 工具 | 版本 | 用途 | 校验命令 |
| --- | --- | --- | --- |
| **JDK** | **21**（LTS） | 后端 Maven 构建与运行 | `java -version` → 21.x |
| **Node.js** | **24** | 前端构建（CI 用 24；本地 22+ 亦可，锁文件基于 24） | `node -v` → v24.x |
| **pnpm** | **11.15.1** | 前端包管理（由 `packageManager` 字段钉定） | `pnpm -v` → 11.15.1 |
| **Docker** | 24+ | 全栈与沙箱镜像 | `docker -v` |
| **Docker Compose** | **2.24.4+** | 多文件叠加部署（external-mysql 等需要） | `docker compose version` |
| **Maven** | 3.9+（或用仓库自带 `./mvnw`） | 后端构建 | `./mvnw -v` |

> ⚠️ **JDK 21 是硬门槛**：Spring Boot 4 + MyBatis-Plus `spring-boot4` starter 需要 Java 21。`JDK 17` 无法编译/运行。贡献者请先用 `sdkman` 或 Temurin 装好 21。

## 2. 本地装好固定版本（推荐方式）

### JDK 21（Eclipse Temurin）

- Windows：`winget install EclipseAdoptium.Temurin.21.JDK`
- macOS/Linux：`sdk install java 21.0.7-tem` 或下载 [Adoptium](https://adoptium.net) 安装包
- 校验：`java -version` 显示 `21.x`

### Node 24 + pnpm 11.15.1（用 corepack 钉定，避免全局版本漂移）

```bash
# 启用 corepack（Node 16.13+ 自带）
corepack enable
# 按 packageManager 字段精确准备 pnpm 11.15.1
corepack prepare pnpm@11.15.1 --activate
pnpm -v   # 11.15.1
```

> 仓库 `agenteam-web/package.json` 的 `packageManager: pnpm@11.15.1` 会在支持 corepack 的环境自动对齐版本，CI 也用同样方式钉定。

## 3. 不自己装数据库时

使用 `deploy/` 的 Compose 变体会自动拉起 MariaDB 与 Redis。**仅当**你要脱离容器运行后端时，才需要：

- MariaDB **13.x**（驱动 `mariadb-java-client 3.5.10`，字符集 `utf8mb4`、排序 `utf8mb4_0900_ai_ci`）
- Redis 7.x（需密码）

> 本地开发（本机直接跑前后端）已验证可用 MariaDB 13.0.2 + Redis 7，端口分别映射到 `43306` / `46379` 以避开默认端口冲突。

## 4. 一键起全栈（本地）

```bash
bash deploy/configure.sh          # 生成 .env
docker compose -f compose.yaml up -d
```

前端默认 `:8088`（经 nginx），后端管理端口 `:9091`（actuator）。

## 5. 常见环境坑

- **`JAVA_HOME` 指向 17**：后端 Maven 直接失败，请确认 `java -version` 为 21。
- **pnpm 版本不对**：锁文件基于 11.15.1，`pnpm install --frozen-lockfile` 在版本不符时报错 → 用 corepack 对齐。
- **Node 版本过低**：Next.js 16 + React 19 需要 Node 20+，CI 用 24；本地建议 ≥ 22。
- **Compose 版本过旧**：`external-mysql` 等叠加文件需要 Compose 2.24.4+，`docker compose version` 确认。
