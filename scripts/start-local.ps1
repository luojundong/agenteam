# AgenTeam 本机启动脚本（Windows / PowerShell）
# 用途：在已准备好的运行时目录基础上，一键启动 MariaDB / Redis / 后端 / 前端。
# 端口规划（与其他项目区分）：前端 3900、后端 9080、MariaDB 43306、Redis 46379、管理端口 19091。
# 前置（一次性准备，见 README 启动文档）：
#   - JDK21 解压到 $Runtime\jdk-21.0.12.1+1
#   - MariaDB 解压到 $Runtime\mariadb-13.0.2-winx64 并初始化数据目录 $Runtime\mariadb-data
#   - Redis 已通过 winget 安装（redis-server 在 PATH）

$ErrorActionPreference = "Stop"
$Runtime     = Join-Path $env:USERPROFILE "agenteam-runtime"
$JavaHome    = Join-Path $Runtime "jdk-21.0.12.1+1"
$MariaHome   = Join-Path $Runtime "mariadb-13.0.2-winx64"
$MariaData   = Join-Path $Runtime "mariadb-data"
$Repo        = "D:\wwwroot\agenteam-main"
$DbPw        = "123456"
$RedisPw     = "123456"

function Wait-Port($port, $sec=30) {
  for ($i=0; $i -lt $sec; $i++) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { return $true }
    Start-Sleep -Seconds 1
  }
  return $false
}
function Start-Bg($name, $exe, $argList) {
  $p = Start-Process -FilePath $exe -ArgumentList $argList -WindowStyle Hidden -PassThru
  Write-Host "[$name] pid=$($p.Id) started"
  return $p
}

# 1) MariaDB @43306
if (-not (Wait-Port 43306 1)) {
  Start-Bg "mariadb" (Join-Path $MariaHome "bin\mariadbd.exe") "--datadir=$MariaData --port=43306 --console"
  Wait-Port 43306 30 | Out-Null
}
# 2) Redis @46379
if (-not (Wait-Port 46379 1)) {
  Start-Bg "redis" "redis-server.exe" "--port 46379 --requirepass $RedisPw --save `"`" --appendonly no"
  Wait-Port 46379 15 | Out-Null
}
# 3) 后端 @9080（JDK21 + container profile，禁用沙盒因无 Docker）
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;" + $env:PATH
$env:SPRING_PROFILES_ACTIVE = "container"
$env:AGENTEAM_DB_URL = "jdbc:mariadb://localhost:43306/agenteam?createDatabaseIfNotExist=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true"
$env:AGENTEAM_DB_USERNAME = "root"
$env:AGENTEAM_DB_PASSWORD = $DbPw
$env:AGENTEAM_REDIS_HOST = "localhost"
$env:AGENTEAM_REDIS_PORT = "46379"
$env:AGENTEAM_REDIS_PASSWORD = $RedisPw
$env:AGENTEAM_SANDBOX_ENABLED = "false"
# 容器 profile 的 forward-headers=native 会在 AGENTEAM_TRUSTED_PROXIES 为空时触发 Tomcat 正则异常；
# 本机无反向代理，统一用 NONE 关闭 RemoteIpValve，并显式给出可信代理白名单兜底。
$env:AGENTEAM_TRUSTED_PROXIES = "127\.0\.0\.1|::1|0:0:0:0:0:0:0:1"
# container profile 必填项（无默认值）：公开 Web 基址，用于邮件/回调链接。指向本机前端。
$env:AGENTEAM_PUBLIC_ORIGIN = "http://localhost:3900"
Set-Location (Join-Path $Repo "agenteam-server")
Start-Bg "backend" ".\mvnw.cmd" "spring-boot:run -Dspring-boot.run.arguments=`"--server.port=9080 --management.server.port=19091 --server.forward-headers-strategy=NONE`""

# 4) 前端 @3900（指向后端 9080；AGENT_BACKEND_URL 缺失会默认 8080 导致代理 500）
$env:AGENT_BACKEND_URL = "http://localhost:9080"
$env:PORT = "3900"
Set-Location (Join-Path $Repo "agenteam-web")
Start-Bg "frontend" "pnpm.cmd" "dev"

Write-Host "all services launching — frontend http://localhost:3900  backend http://localhost:9080"
