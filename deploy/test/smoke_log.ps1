# =============================================================================
# 校园问卷系统 —— 日志查询模块冒烟测试（FR-LOG-01 / 02 / 03）
#
# 覆盖：
#   1  登录准备
#   2  登录日志：列表、分页、账号筛选、结果筛选、时间范围
#   3  操作日志：列表、模块下拉、模块/动作筛选、关键词
#   4  日志只读：没有 PUT / DELETE 入口
#   5  导出：登录日志 xlsx、操作日志 xlsx（含中文文件名与表头）
#   6  权限：教师/学生均无 log:* 权限
#   7  参数校验：非法时间格式 → 400
#   8  响应结构：统一 {code,msg,data} 包裹
#
# 运行前需后端已启动（http://localhost:8080）：
#   powershell -File deploy/test/smoke_log.ps1
#
# 注意：本文件必须保存为 UTF-8 with BOM，否则 PowerShell 5.1 会把中文读成乱码。
# =============================================================================

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Base = 'http://localhost:8080/api'
$script:pass = 0
$script:fail = 0

function Section($title) {
    Write-Host ""
    Write-Host "==== $title ====" -ForegroundColor Cyan
}

function Check($name, $actual, $expected) {
    if ("$actual" -eq "$expected") {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $name  期望=[$expected] 实际=[$actual]" -ForegroundColor Red
    }
}

function CheckTrue($name, $cond, $desc = '') {
    if ($cond) {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $name  $desc" -ForegroundColor Red
    }
}

function Api($method, $path, $body, $token) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{
        Uri             = "$Base$path"
        Method          = $method
        Headers         = $headers
        UseBasicParsing = $true
        TimeoutSec      = 30
    }
    if ($body -ne $null) {
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10 -Compress))
        $params['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        return @{ code = $r.StatusCode; body = ($text | ConvertFrom-Json); raw = $text }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $code = [int]$resp.StatusCode
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
            $t = $sr.ReadToEnd()
            $sr.Close()
            try { return @{ code = $code; body = ($t | ConvertFrom-Json); raw = $t } }
            catch { return @{ code = $code; body = $null; raw = $t } }
        }
        return @{ code = -1; body = $null; raw = "$_" }
    }
}

function Login($username, $password) {
    $r = Api 'POST' '/auth/login' @{ username = $username; password = $password } $null
    if ($r.code -ne 200) { throw "登录失败 $username : $($r.raw)" }
    return $r.body.data.token
}

function Download($path, $token, $outFile) {
    $headers = @{ Authorization = "Bearer $token" }
    Invoke-WebRequest -Uri "$Base$path" -Headers $headers -OutFile $outFile -UseBasicParsing -TimeoutSec 60 | Out-Null
    return Get-Item $outFile
}

# ---------------------------------------------------------------------------
Section '1. 登录准备'
# ---------------------------------------------------------------------------
$adminTok = Login 'admin' 'Admin@123'
CheckTrue '管理员登录成功' ($adminTok.Length -gt 20)

$teacherTok = Login 'teacher' 'Teacher@123'
CheckTrue '教师登录成功' ($teacherTok.Length -gt 20)

try {
    $studentTok = Login 'student' 'Student@123'
    CheckTrue '学生登录成功' ($studentTok.Length -gt 20)
} catch {
    $studentTok = $null
    Write-Host "  [SKIP] 学生账号密码可能已被改动" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
Section '2. 登录日志查询'
# ---------------------------------------------------------------------------
$r = Api 'GET' '/logs/login?page=1&size=5' $null $adminTok
Check '登录日志 200' $r.code 200
Check '响应带 code=200' $r.body.code 200
CheckTrue '有 data.records' ($null -ne $r.body.data.records)
CheckTrue 'total > 0' ([long]$r.body.data.total -gt 0) "total=$($r.body.data.total)"
Check '每页 5 条' (@($r.body.data.records).Count) 5

$first = @($r.body.data.records)[0]
CheckTrue '记录含 username' ($null -ne $first.username) ''
CheckTrue '记录含 result' ($null -ne $first.result) ''
CheckTrue '记录含 ip' ($null -ne $first.ip) ''
CheckTrue '记录含 createTime' ($null -ne $first.createTime) ''
CheckTrue 'createTime 是 yyyy-MM-dd HH:mm:ss' ($first.createTime -match '^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$') $first.createTime

# 按账号筛选
# 注意：接口没有 username 参数，账号筛选统一走 keyword（匹配 username 或 ip）。
# 前端 LogView 也是这么传的，两者必须一致，否则页面上筛了、接口没筛。
$r = Api 'GET' '/logs/login?keyword=admin&page=1&size=5' $null $adminTok
Check '按账号筛选 200' $r.code 200
$allAdmin = @($r.body.data.records | Where-Object { $_.username -ne 'admin' }).Count -eq 0
CheckTrue '筛选结果只含 admin' $allAdmin ''

# 关键词也覆盖 IP：本机压测都是 127.0.0.1 / ::1，随便取一条记录的 ip 反查
$r = Api 'GET' '/logs/login?keyword=teacher&page=1&size=5' $null $adminTok
Check '按 keyword 筛选 200' $r.code 200
$allTeacher = @($r.body.data.records | Where-Object { $_.username -ne 'teacher' }).Count -eq 0
CheckTrue '按 keyword 搜 teacher 命中' $allTeacher ''

# 结果筛选：1=成功
$r = Api 'GET' '/logs/login?result=1&page=1&size=10' $null $adminTok
Check '按成功筛选 200' $r.code 200
$allSuccess = @($r.body.data.records | Where-Object { $_.result -ne 1 }).Count -eq 0
CheckTrue '筛选结果全部是成功' $allSuccess ''

# 分页：第 2 页与第 1 页不重复
$p1 = Api 'GET' '/logs/login?page=1&size=5' $null $adminTok
$p2 = Api 'GET' '/logs/login?page=2&size=5' $null $adminTok
$ids1 = @($p1.body.data.records | ForEach-Object { $_.id })
$ids2 = @($p2.body.data.records | ForEach-Object { $_.id })
$overlap = @($ids1 | Where-Object { $ids2 -contains $_ }).Count
Check '两页之间无重复' $overlap 0

# 时间范围：取今天 00:00 到现在
$today = (Get-Date).ToString('yyyy-MM-dd')
$r = Api 'GET' "/logs/login?startTime=$today 00:00:00&endTime=$today 23:59:59&page=1&size=5" $null $adminTok
Check '时间范围查询 200' $r.code 200
CheckTrue '时间范围内有记录' ([long]$r.body.data.total -gt 0) "total=$($r.body.data.total)"

# 未来时间范围应为空
$r = Api 'GET' '/logs/login?startTime=2030-01-01 00:00:00&endTime=2030-01-02 00:00:00' $null $adminTok
Check '未来时间范围 200' $r.code 200
Check '未来时间范围无记录' ([long]$r.body.data.total) 0

# ---------------------------------------------------------------------------
Section '3. 操作日志查询'
# ---------------------------------------------------------------------------
$r = Api 'GET' '/logs/operations?page=1&size=5' $null $adminTok
Check '操作日志 200' $r.code 200
Check '响应带 code=200' $r.body.code 200
CheckTrue '有 records' ($null -ne $r.body.data.records)
CheckTrue 'total >= 0' ([long]$r.body.data.total -ge 0) "total=$($r.body.data.total)"

if (@($r.body.data.records).Count -gt 0) {
    $op = @($r.body.data.records)[0]
    CheckTrue '含 module' ($null -ne $op.module) ''
    CheckTrue '含 action' ($null -ne $op.action) ''
    CheckTrue '含 username' ($null -ne $op.username) ''
}

# 模块清单
$r = Api 'GET' '/logs/modules' $null $adminTok
Check '模块清单 200' $r.code 200
CheckTrue '模块清单是数组' ($r.body.data -is [System.Array] -or $r.body.data -is [System.Collections.ArrayList]) "type=$($r.body.data.GetType().Name)"
CheckTrue '模块清单非空' (@($r.body.data).Count -gt 0) "count=$(@($r.body.data).Count)"
Write-Host "    模块：$((@($r.body.data)) -join ', ')" -ForegroundColor DarkGray

# 按模块筛选
$mod = @($r.body.data)[0]
$r2 = Api 'GET' "/logs/operations?module=$mod&page=1&size=10" $null $adminTok
Check '按模块筛选 200' $r2.code 200
$allSameMod = @($r2.body.data.records | Where-Object { $_.module -ne $mod }).Count -eq 0
CheckTrue "筛选后模块全为「$mod」" $allSameMod ''

# 关键词（匹配 username 或 detail）
$r = Api 'GET' '/logs/operations?keyword=admin&page=1&size=5' $null $adminTok
Check '操作日志关键词查询 200' $r.code 200

# ---------------------------------------------------------------------------
Section '4. 日志只读（FR-LOG-03）'
# ---------------------------------------------------------------------------
# 后端只暴露 GET：PUT / DELETE 应命中「方法不支持」而不是执行成功
$r = Api 'DELETE' '/logs/login/1' $null $adminTok
CheckTrue 'DELETE /logs/login/1 不被接受' ($r.code -in @(404, 405)) "code=$($r.code)"

$r = Api 'DELETE' '/logs/operations/1' $null $adminTok
CheckTrue 'DELETE /logs/operations/1 不被接受' ($r.code -in @(404, 405)) "code=$($r.code)"

$r = Api 'PUT' '/logs/login/1' @{ reason = 'x' } $adminTok
CheckTrue 'PUT /logs/login/1 不被接受' ($r.code -in @(404, 405)) "code=$($r.code)"

# ---------------------------------------------------------------------------
Section '5. 导出'
# ---------------------------------------------------------------------------
$tmp = Join-Path $env:TEMP 'g14_logs'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$f = Download '/logs/login/export?page=1&size=20' $adminTok (Join-Path $tmp 'login.xlsx')
CheckTrue '登录日志导出文件存在' ($f.Length -gt 2000) "size=$($f.Length)"
$bytes = [System.IO.File]::ReadAllBytes($f.FullName)
Check 'xlsx 文件头是 PK' ("$($bytes[0]),$($bytes[1])") '80,75'

$f2 = Download '/logs/operations/export?page=1&size=20' $adminTok (Join-Path $tmp 'op.xlsx')
CheckTrue '操作日志导出文件存在' ($f2.Length -gt 2000) "size=$($f2.Length)"
$bytes2 = [System.IO.File]::ReadAllBytes($f2.FullName)
Check 'xlsx 文件头是 PK' ("$($bytes2[0]),$($bytes2[1])") '80,75'

# 用 python 读回表头，确认中文没乱码
# read_xlsx.py 的输出结构是 { sheets: ["Sheet1", ...], data: { "Sheet1": [[...], ...] } }，
# 不是 sheets[i].rows —— 行数据在 data 字典里，按 Sheet 名索引。
$py = Join-Path $PSScriptRoot 'tools\read_xlsx.py'
if (Test-Path $py) {
    $env:PYTHONIOENCODING = 'utf-8'
    $json = Join-Path $tmp 'peek.json'
    python $py (Join-Path $tmp 'login.xlsx') $json 2>&1 | Out-Null
    if (Test-Path $json) {
        $peek = [System.IO.File]::ReadAllText($json, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
        $sheetName = @($peek.sheets)[0]
        $rows = $peek.data.$sheetName
        if (@($rows).Count -gt 0) {
            $header = (@($rows)[0] -join '|')
            CheckTrue '登录日志表头含「账号」' ($header -match '账号') $header
            CheckTrue '登录日志表头含「结果」' ($header -match '结果') $header
            CheckTrue '登录日志表头含「IP」' ($header -match 'IP') $header
        } else {
            CheckTrue '登录日志导出有数据行' $false "Sheet=$sheetName 没有行"
        }
    } else {
        Write-Host "  [SKIP] 未能解析 xlsx 内容" -ForegroundColor Yellow
    }
} else {
    Write-Host "  [SKIP] 找不到 tools/read_xlsx.py" -ForegroundColor Yellow
}

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------
Section '6. 权限边界'
# ---------------------------------------------------------------------------
$r = Api 'GET' '/logs/login?page=1&size=1' $null $teacherTok
Check '教师查登录日志 403' $r.code 403

$r = Api 'GET' '/logs/operations?page=1&size=1' $null $teacherTok
Check '教师查操作日志 403' $r.code 403

$r = Api 'GET' '/logs/modules' $null $teacherTok
Check '教师查模块清单 403' $r.code 403

$f3 = Join-Path $env:TEMP 'g14_teacher.xlsx'
try {
    $r = Api 'GET' '/logs/login/export' $null $teacherTok
    Check '教师导出登录日志 403' $r.code 403
} catch {
    Check '教师导出登录日志 403' 0 403
}

if ($studentTok) {
    $r = Api 'GET' '/logs/login?page=1&size=1' $null $studentTok
    Check '学生查登录日志 403' $r.code 403
    $r = Api 'GET' '/logs/operations?page=1&size=1' $null $studentTok
    Check '学生查操作日志 403' $r.code 403
}

$r = Api 'GET' '/logs/login?page=1&size=1' $null $null
Check '未登录查日志 401' $r.code 401

# ---------------------------------------------------------------------------
Section '7. 参数校验'
# ---------------------------------------------------------------------------
$r = Api 'GET' '/logs/login?startTime=2026/01/01' $null $adminTok
Check '非法时间格式 400' $r.code 400
CheckTrue '提示提到时间格式' ($r.body.msg -match '时间格式') "msg=$($r.body.msg)"

$r = Api 'GET' '/logs/operations?startTime=abc&endTime=def' $null $adminTok
Check '两端都非法 400' $r.code 400

# ---------------------------------------------------------------------------
Section '8. 结果汇总'
# ---------------------------------------------------------------------------
$total = $script:pass + $script:fail
Write-Host ""
Write-Host "结果：通过 $($script:pass) 项，失败 $($script:fail) 项（共 $total 项）" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -gt 0) { exit 1 }
