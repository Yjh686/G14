#Requires -Version 5.1
<#
.SYNOPSIS
    用 Cloudflare 快速隧道把本机站点临时暴露到公网，供手机访问 / 评委演示。

.DESCRIPTION
    为什么需要它：
      校园网（本项目实测 HZCU-GONGYU）启用了客户端隔离，同一 Wi-Fi 下的手机
      访问本机局域网 IP 会一直转圈直到超时。这不是 Windows 防火墙的问题
      （Docker 的入站规则已覆盖 Public 配置文件），而是交换机不让终端互访。

    隧道从本机主动向外连接 Cloudflare 边缘，再把外部请求回灌到 localhost:80，
    因此不需要公网 IP、不需要端口映射、不需要改路由器，也不受机房隔离影响。
    顺带自带 HTTPS —— 手机用 4G 流量也能打开。

    代价：每次启动的地址都是随机子域名且会变；隧道进程退出后地址立即失效。

.PARAMETER Port
    本地端口，默认 80（Docker 里 nginx 的端口）。若用 start-local.ps1 跑的是
    Vite 前端，传 5173。

.PARAMETER Protocol
    隧道传输协议，默认 http2。校园网普遍封 UDP 7844（QUIC），不指定的话
    cloudflared 会反复重试 quic 然后失败，日志里是一堆 "Failed to dial a quic connection"。

.EXAMPLE
    .\start-tunnel.ps1
    .\start-tunnel.ps1 -Port 5173
#>
param(
    [int]$Port = 80,
    [ValidateSet('http2', 'quic')]
    [string]$Protocol = 'http2'
)

$ErrorActionPreference = 'Stop'

$cf = Join-Path $env:LOCALAPPDATA 'cloudflared\cloudflared.exe'
if (-not (Test-Path $cf)) {
    Write-Host "未找到 cloudflared：$cf" -ForegroundColor Red
    Write-Host "安装方式：" -ForegroundColor Yellow
    Write-Host "  winget install --id Cloudflare.cloudflared   # 需要能访问 github release" -ForegroundColor Gray
    Write-Host "  若下载失败，用代理加速下载后放到上面这个路径：" -ForegroundColor Gray
    Write-Host "  curl.exe -L -o `"$cf`" `"https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe`"" -ForegroundColor Gray
    exit 1
}

# 先确认本地站点是活的，避免隧道建好了却指向一个没人监听的端口
try {
    $probe = Invoke-WebRequest "http://localhost:$Port/api/health" -TimeoutSec 8 -UseBasicParsing
    Write-Host "本地 $Port 端口正常（/api/health = $($probe.StatusCode)）" -ForegroundColor Green
} catch {
    Write-Host "警告：本地 $Port 端口没有响应 /api/health。" -ForegroundColor Yellow
    Write-Host "      先把服务起来：cd deploy; docker compose up -d" -ForegroundColor Yellow
}

# 上一个隧道还开着就先关掉，否则会有两条隧道抢同一个本地端口
Get-Process cloudflared -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "关闭已存在的 cloudflared（PID $($_.Id)）" -ForegroundColor DarkGray
    Stop-Process -Id $_.Id -Force
}
Start-Sleep 2

$log = Join-Path $env:TEMP 'g14-tunnel.err.log'
Remove-Item $log -Force -ErrorAction SilentlyContinue

Write-Host "正在建立隧道（协议 $Protocol）..." -ForegroundColor Cyan
Start-Process -FilePath $cf `
    -ArgumentList @('tunnel', '--url', "http://localhost:$Port", '--protocol', $Protocol, '--no-autoupdate') `
    -RedirectStandardOutput (Join-Path $env:TEMP 'g14-tunnel.log') `
    -RedirectStandardError $log -WindowStyle Hidden

# 轮询日志等地址出现。cloudflared 大约 5~15 秒分配子域名，注册连接还要几秒。
$url = $null
for ($i = 1; $i -le 40; $i++) {
    Start-Sleep -Seconds 1
    if (-not (Test-Path $log)) { continue }
    $m = Select-String -Path $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($m) { $url = ($m.Matches[0].Value); break }
}

if (-not $url) {
    Write-Host "20 秒内没拿到公网地址，最近日志：" -ForegroundColor Red
    Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 15
    exit 1
}

# 等连接真正注册（有地址不等于通了，QUIC 失败时地址会先出来）
for ($i = 1; $i -le 20; $i++) {
    $ok = Select-String -Path $log -Pattern 'Registered tunnel connection' -ErrorAction SilentlyContinue
    if ($ok) { break }
    Start-Sleep -Seconds 1
}

Write-Host ""
Write-Host "==============================================" -ForegroundColor Green
Write-Host "  公网地址：$url" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green
Write-Host ""
# 地址很长，手打不现实。有 segno（pip install segno）就直接在终端印二维码，
# 没有就退化成提示，不影主流程。
$qrShown = $false
$py = (Get-Command python -ErrorAction SilentlyContinue)
if ($py) {
    $code = "import sys
try:
    import segno
except ImportError:
    sys.exit(1)
segno.make(sys.argv[1], error='m').terminal(compact=True)"
    $tmp = Join-Path $env:TEMP 'g14-qr.py'
    [System.IO.File]::WriteAllText($tmp, $code, [System.Text.Encoding]::UTF8)
    & python $tmp $url
    if ($LASTEXITCODE -eq 0) { $qrShown = $true }
}

if ($qrShown) {
    Write-Host ""
    Write-Host "用手机相机扫上面这个二维码即可（不必手打地址）。" -ForegroundColor Green
} else {
    Write-Host "手机浏览器直接打开上面的地址即可（可以用 4G 流量）。" -ForegroundColor Gray
    Write-Host "想把地址变成二维码：python -m pip install segno，然后重跑本脚本。" -ForegroundColor DarkGray
}
Write-Host "注意：" -ForegroundColor Yellow
Write-Host "  · 这个地址是随机的，每次启动都会变" -ForegroundColor Gray
Write-Host "  · 关掉本窗口或重启电脑后地址立即失效" -ForegroundColor Gray
Write-Host "  · 隧道期间本机不能休眠，否则连接中断" -ForegroundColor Gray
Write-Host "  · 停止隧道：Get-Process cloudflared | Stop-Process -Force" -ForegroundColor Gray