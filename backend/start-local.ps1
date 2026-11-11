# =============================================================================
# 本机开发启动脚本（Windows PowerShell 5.1）
#
# 为什么需要它：
#   application.yml 里的 DB_PASSWORD 和 APP_JWT_SECRET 都不带默认值
#   ——仓库是公开的，写默认值等于把密码和签名密钥一起公开。
#   所以直接 mvn spring-boot:run 会报：
#       Could not resolve placeholder 'DB_PASSWORD' in value "${DB_PASSWORD}"
#   这个脚本负责从 backend/.env.local 读取凭据并注入进程环境变量，
#   凭据本身留在 .env.local（已被 .gitignore 排除）里，不进仓库。
#
# 用法：
#   cd backend
#   copy .env.local.example .env.local     # 首次
#   notepad .env.local                    # 填 DB_PASSWORD 和 APP_JWT_SECRET
#   .\start-local.ps1                     # 启动
#
# 参数：
#   -Port 8081        换端口（默认 8080）
#   -Jar              用打包好的 jar 启动，而不是 target/classes
#   -SkipBuild        跳过 mvn compile（classes 已是最新时省十几秒）
# =============================================================================
param(
    [int]$Port = 8080,
    [switch]$Jar,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$backendDir = $PSScriptRoot
# 刻意不 Set-Location：PowerShell 的工作目录是会话级的，脚本里改了会连带影响
# 调用方（比如紧接着再调一次本脚本、或调用方后续用相对路径都会错）。
# 下面所有路径都用 $backendDir 拼成绝对路径。

# ---------------------------------------------------------------------------
# 1. 读 .env.local
# ---------------------------------------------------------------------------
$envFile = Join-Path $backendDir '.env.local'
if (-not (Test-Path $envFile)) {
    Write-Host '缺少 backend\.env.local' -ForegroundColor Red
    Write-Host '  先执行：copy .env.local.example .env.local' -ForegroundColor Yellow
    Write-Host '  然后填入 DB_PASSWORD 与 APP_JWT_SECRET（后者至少 32 字节）' -ForegroundColor Yellow
    exit 1
}

# 按行解析 KEY=VALUE；跳过空行与 # 注释；不覆盖已存在的环境变量由调用方决定
foreach ($line in Get-Content $envFile -Encoding UTF8) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $i = $t.IndexOf('=')
    if ($i -lt 1) { continue }
    $name = $t.Substring(0, $i).Trim()
    $value = $t.Substring($i + 1).Trim()
    # 去掉可能存在的成对引号
    if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ($value -eq '') { continue }   # 模板里的空项直接跳过
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    Write-Host ("  已注入 {0}（{1} 字符）" -f $name, $value.Length) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
# 2. 启动前自检：缺关键项直接报清楚，而不是让 Spring 抛一坨栈
# ---------------------------------------------------------------------------
$missing = @()
foreach ($k in 'DB_PASSWORD', 'APP_JWT_SECRET') {
    if (-not [Environment]::GetEnvironmentVariable($k, 'Process')) { $missing += $k }
}
if ($missing.Count -gt 0) {
    Write-Host ("backend\.env.local 里缺少：{0}" -f ($missing -join ', ')) -ForegroundColor Red
    exit 1
}
if ($env:APP_JWT_SECRET.Length -lt 32) {
    Write-Host ("APP_JWT_SECRET 只有 {0} 字节，HS256 要求至少 32 字节" -f $env:APP_JWT_SECRET.Length) -ForegroundColor Red
    Write-Host '  生成一个：openssl rand -base64 48' -ForegroundColor Yellow
    exit 1
}

# ---------------------------------------------------------------------------
# 3. 编译 / 打包
# ---------------------------------------------------------------------------
$env:JAVA_HOME = 'D:\huanjing\jdk-17'
$mvn = 'D:\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'

if (-not $SkipBuild) {
    if ($Jar) {
        Write-Host '打包中（mvn -o package）...' -ForegroundColor Cyan
        & $mvn -q -o -f "$backendDir\pom.xml" -DskipTests package
    } else {
        Write-Host '编译中（mvn -o compile）...' -ForegroundColor Cyan
        & $mvn -q -o -f "$backendDir\pom.xml" -DskipTests compile
    }
    if ($LASTEXITCODE -ne 0) { Write-Host '构建失败' -ForegroundColor Red; exit 1 }
}

# ---------------------------------------------------------------------------
# 4. 已在跑的实例先停掉，避免端口冲突后报一堆看不懂的错
# ---------------------------------------------------------------------------
$old = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
       Where-Object { $_.CommandLine -like '*Backend1Application*' -or $_.CommandLine -like '*backend1-*.jar*' }
if ($old) {
    foreach ($p in $old) {
        Write-Host ("停止旧进程 PID {0}" -f $p.ProcessId) -ForegroundColor Yellow
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------------------
# 5. 启动
# ---------------------------------------------------------------------------
$logDir = Join-Path $backendDir 'target'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$outLog = Join-Path $logDir 'run.log'
$errLog = Join-Path $logDir 'run.err.log'

if ($Jar) {
    $jarFile = Get-ChildItem (Join-Path $backendDir 'target') -Filter 'backend1-*.jar' |
           Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jarFile) { Write-Host 'target 下没有 jar，请先打包' -ForegroundColor Red; exit 1 }
    $argList = @('-jar', $jarFile.FullName, "--server.port=$Port")
    Write-Host ("启动 jar：{0}" -f $jarFile.Name) -ForegroundColor Cyan
} else {
    # cp.txt 是依赖清单。改动 pom 后必须重新生成，否则会报 NoClassDefFoundError。
    $cpFile = Join-Path $logDir 'cp.txt'
    if (-not (Test-Path $cpFile)) {
        Write-Host '缺少 target\cp.txt，正在生成依赖清单...' -ForegroundColor Yellow
        # -Dmdep.outputFile 必须传绝对路径：传相对路径时 Maven 以 pom 所在目录为基准，
        # 产物会落到 backend\backend\target\ 下，启动时读到的还是旧文件。
        & $mvn -q -o -f "$backendDir\pom.xml" dependency:build-classpath "-Dmdep.outputFile=$cpFile"
        if ($LASTEXITCODE -ne 0) { Write-Host '生成 cp.txt 失败' -ForegroundColor Red; exit 1 }
    }
    $cp = (Get-Content $cpFile -Raw).Trim() + ';' + (Join-Path $backendDir 'target\classes')
    $argList = @('-cp', $cp, 'com.yjh.backend1.Backend1Application', "--server.port=$Port")
}

Start-Process -FilePath $javaExe -ArgumentList $argList `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
    -WindowStyle Hidden

Write-Host '等待启动...' -ForegroundColor Cyan
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest "http://localhost:$Port/api/health" -TimeoutSec 3 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { }
}
if ($ok) {
    Write-Host ''
    Write-Host ("后端已启动  http://localhost:{0}" -f $Port) -ForegroundColor Green
    Write-Host ("  Swagger      http://localhost:{0}/swagger-ui.html" -f $Port) -ForegroundColor Gray
    Write-Host ("  健康检查     http://localhost:{0}/api/health" -f $Port) -ForegroundColor Gray
    Write-Host ("  运行日志     backend\target\run.log" ) -ForegroundColor Gray
} else {
    Write-Host '30 秒内没起来，看日志：' -ForegroundColor Red
    Write-Host '  backend\target\run.log' -ForegroundColor Yellow
    Write-Host '  backend\target\run.err.log' -ForegroundColor Yellow
    Get-Content $errLog -Tail 15 -ErrorAction SilentlyContinue
    exit 1
}
