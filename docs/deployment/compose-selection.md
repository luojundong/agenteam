# AgenTeam 部署选型指南（一键推荐）

AgenTeam 提供 8 套 Docker Compose 变体（`deploy/` 下）。先用 `configure.sh`（Linux/macOS）或 `configure.ps1`（Windows）生成 `.env`，再按本表选择叠加文件。

## 1. 先跑配置初始化（所有场景通用）

```bash
# Linux / macOS
bash deploy/configure.sh
# Windows (PowerShell)
pwsh deploy/configure.ps1
```

脚本会交互式生成 `deploy/.env`（数据库/缓存密码、公网地址、沙箱令牌等）。**未生成 `.env` 前，compose 会因 `${DB_PASSWORD:?...}` 等必填项直接报错。**

## 2. 选型矩阵（按场景挑一个基础文件）

| 场景 | 基础文件 | 启动命令 | 说明 |
| --- | --- | --- | --- |
| **本机/单机自建（推荐默认）** | `compose.yaml` | `docker compose -f compose.yaml up -d` | 全栈源码构建：MySQL + Redis + backend + sandbox-executor + frontend + nginx，一键拉起 |
| **本机开发（改代码）** | `compose.yaml` + `compose.dev.yaml` | `docker compose -f compose.yaml -f compose.dev.yaml up -d` | 数据库/缓存仅向回环地址开放，便于本机直接跑前后端调试 |
| **生产（预构建镜像，不构建源码）** | `compose.registry.yaml` | `docker compose -f compose.registry.yaml up -d` | 服务器只拉取已发布镜像，不读取源码、不执行构建，适合 CI 出包后部署 |
| **已有 MySQL，不想再起一套** | `compose.external-mysql.yaml` | `docker compose -f compose.yaml -f compose.external-mysql.yaml up -d` | 连接外部/另一套部署的 MySQL，本套不再启动 MySQL（需 Compose 2.24.4+） |
| **多套部署共用一个 MySQL** | `compose.shared-mysql.yaml` | 在已运行的部署上 `docker compose -f compose.shared-mysql.yaml up -d` | 保留当前 MySQL 服务与数据卷，为另一套部署提供数据库连接 |
| **小规格主机（4 GB）** | `compose.low-memory.yaml` | `docker compose -f compose.yaml -f compose.low-memory.yaml up -d` | 资源限额收紧（backend 512m / sandbox-executor 160m / frontend 160m / redis 64m / nginx 32m）；启用前需安装 `agenteam-sandboxes.slice` 并创建 `agenteam-sandbox-capacity` 卷 |
| **前后端拆分部署** | `compose.split.yaml` | `docker compose -f compose.yaml -f compose.split.yaml up -d` | 将前端/后端拆分为独立部署单元，便于分别扩缩容或独立域名 |
| **单文件极简** | `compose.single.yaml` | `docker compose -f compose.single.yaml up -d` | 单容器/最简形态，适合演示或受限环境 |

> 叠加文件（dev / low-memory / external-mysql / shared-mysql / split / single / registry）都是对 `compose.yaml` 的**覆盖或扩展**，必须与 `compose.yaml` 一起传入 `-f`。

## 3. 选型决策树（30 秒）

1. 是生产还是本机玩？→ 生产优先 `registry`，否则默认 `compose.yaml`。
2. 机器内存 < 8 GB？→ 叠加 `low-memory`。
3. 已经有 MySQL？→ 叠加 `external-mysql`；要两套共用？→ `shared-mysql`。
4. 要改代码调试？→ 叠加 `dev`。
5. 要前后端分离运维？→ 叠加 `split`。

## 4. 健康检查与排障

- 后端健康：`http://<host>:9091/actuator/health`（容器内固定端口 9091，对外经 nginx 8088）。
- 沙箱执行器：`http://127.0.0.1:8092/executions/health`（返回 401 即正常）。
- 前端：`http://<host>:8088/login`。
- 启动顺序由 `depends_on` + `healthcheck` 保证；若 MySQL/Redis 未健康，backend 不会启动。
- 常见坑：未跑 `configure.sh` 导致必填变量缺失；低内存模式未装 cgroup slice 导致沙箱起不来。
