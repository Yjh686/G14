# ============================================================
# 认证 + 用户管理 接口冒烟测试
# 用法：pwsh -File deploy\test\smoke_auth.ps1
# 前置：后端已在 http://localhost:8080 启动
# ============================================================
$ErrorActionPreference = 'Continue'
$BASE = 'http://localhost:8080'
$pass = 0; $fail = 0

function Req($name, $method, $path, $body, $token) {
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    try {
        $p = @{ Uri = "$BASE$path"; Method = $method; Headers = $h; UseBasicParsing = $true }
        if ($body) {
            $p['ContentType'] = 'application/json;charset=utf-8'
            $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6 -Compress))
        }
        $r = Invoke-WebRequest @p
        return @{ code = $r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $txt = $sr.ReadToEnd()
            try { $j = $txt | ConvertFrom-Json } catch { $j = @{ msg = $txt } }
            return @{ code = [int]$resp.StatusCode; body = $j }
        }
        return @{ code = -1; body = @{ msg = $_.Exception.Message } }
    }
}

function Check($name, $actual, $expected) {
    if ($actual -eq $expected) {
        $script:pass++
        Write-Host "  [PASS] $name ($actual)" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $name 期望=$expected 实际=$actual" -ForegroundColor Red
    }
}

function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

# ------------------------------------------------------------
Section '1. 健康检查'
$r = Req 'health' 'GET' '/api/health'
Check 'health 200' $r.code 200

# ------------------------------------------------------------
Section '2. 登录与令牌'
$r = Req 'login admin' 'POST' '/api/auth/login' @{ username = 'admin'; password = 'Admin@123' }
Check 'admin 登录 200' $r.code 200
$adminTok = $r.body.data.token
Check 'admin 角色' $r.body.data.roleCode 'ROLE_ADMIN'
if (-not $adminTok) { Write-Host '无法获取 admin 令牌，后续测试中止' -ForegroundColor Red; exit 1 }

$r = Req 'login wrong' 'POST' '/api/auth/login' @{ username = 'admin'; password = 'wrong-pass' }
Check '错误密码 401' $r.code 401
Check '错误密码不泄露账号存在' ($r.body.msg -like '*账号或密码错误*') $true

$r = Req 'login unknown' 'POST' '/api/auth/login' @{ username = 'no_such_user_9'; password = 'whatever1' }
Check '不存在账号 401' $r.code 401
Check '不存在账号提示一致' $r.body.msg '账号或密码错误'

$r = Req 'me no token' 'GET' '/api/auth/me'
Check '无令牌 401' $r.code 401

$r = Req 'me bad token' 'GET' '/api/auth/me' $null 'not.a.token'
Check '非法令牌 401' $r.code 401

$r = Req 'me ok' 'GET' '/api/auth/me' $null $adminTok
Check 'me 200' $r.code 200
Check 'me 不含 passwordHash' ($null -eq $r.body.data.passwordHash) $true
Check 'me 不含 tokenVersion' ($null -eq $r.body.data.tokenVersion) $true
Check 'me 权限数 50' ($r.body.data.permissions.Count) 50

# ------------------------------------------------------------
Section '3. 越权访问（学生不能进用户管理）'
$r = Req 'login student' 'POST' '/api/auth/login' @{ username = 'student'; password = 'Student@123' }
if ($r.code -ne 200) {
    $r = Req 'login student fallback' 'POST' '/api/auth/login' @{ username = 'student'; password = 'NewPass123' }
}
$stuTok = $r.body.data.token
Check 'student 登录 200' $r.code 200

$r = Req 'student list users' 'GET' '/api/users?page=1&size=10' $null $stuTok
Check '学生查用户列表 403' $r.code 403
Check '403 提示无权限' ($r.body.msg -like '*无权限*') $true

$r = Req 'teacher list users' 'GET' '/api/users?page=1&size=10' $null $null
Check '未登录查用户列表 401' $r.code 401

# ------------------------------------------------------------
Section '4. 用户列表与筛选'
$r = Req 'list all' 'GET' '/api/users?page=1&size=10' $null $adminTok
Check '列表 200' $r.code 200
Check '总数 >= 3' ($r.body.data.total -ge 3) $true

# 先造一个待审核账号，保证筛选有数据（不依赖库里残留）
$pendName = '2026' + (Get-Random -Minimum 1000 -Maximum 9999)
$null = Req 'seed pending' 'POST' '/api/auth/register' @{ username = $pendName; password = 'Pend2026abc'; realName = '待审测试'; role = 'STUDENT' }

$r = Req 'filter pending' 'GET' '/api/users?auditStatus=0&page=1&size=10' $null $adminTok
Check '筛选待审核 200' $r.code 200
Check '存在待审核用户' ($r.body.data.total -ge 1) $true
Check '待审列表只含待审状态' (@(@($r.body.data.records) | Where-Object { $_.auditStatus -ne 0 }).Count -eq 0) $true

$r = Req 'filter role teacher' 'GET' '/api/users?roleCode=ROLE_TEACHER&page=1&size=10' $null $adminTok
Check '筛选教师 200' $r.code 200
Check '教师含 teacher' (@(@($r.body.data.records) | Where-Object { $_.username -eq 'teacher' }).Count -ge 1) $true

$r = Req 'search keyword' 'GET' '/api/users?keyword=张&page=1&size=10' $null $adminTok
Check '关键词搜索 200' $r.code 200
Check '搜到张老师' (@(@($r.body.data.records) | Where-Object { $_.realName -eq '张老师' }).Count -ge 1) $true

# ------------------------------------------------------------
Section '5. 新建用户'
$newName = '2024' + (Get-Random -Minimum 1000 -Maximum 9999)
$r = Req 'create user' 'POST' '/api/users' @{ username = $newName; realName = '测试学生'; roleCode = 'ROLE_STUDENT'; department = '计算机学院2024级' } $adminTok
Check '新建用户 200' $r.code 200
$newId = $r.body.data.id
Check '新建用户已审核' $r.body.data.auditStatus 1
Check '新建用户需改密' $r.body.data.mustChangePassword 1

$r = Req 'create dup' 'POST' '/api/users' @{ username = $newName; realName = '重复'; roleCode = 'ROLE_STUDENT' } $adminTok
Check '重复账号 409' $r.code 409

$r = Req 'create bad role' 'POST' '/api/users' @{ username = ('x' + (Get-Random)); realName = '角色错'; roleCode = 'ROLE_ROOT' } $adminTok
Check '非法角色 400' $r.code 400

$r = Req 'create weak pwd' 'POST' '/api/users' @{ username = ('y' + (Get-Random)); realName = '弱密码'; roleCode = 'ROLE_STUDENT'; password = '12345678' } $adminTok
Check '弱密码 400' $r.code 400

# ------------------------------------------------------------
Section '6. 默认密码 + 强制改密'
$r = Req 'login new user' 'POST' '/api/auth/login' @{ username = $newName; password = 'G14@init2025' }
Check '默认密码可登录 200' $r.code 200
Check 'mustChangePassword=true' $r.body.data.mustChangePassword $true
$newTok = $r.body.data.token

# 强制改密时访问其它接口应被拦截
$r = Req 'blocked while must change' 'GET' '/api/auth/me' $null $newTok
Check '改密状态下访问 me 允许' $r.code 200
$r = Req 'list blocked' 'GET' '/api/users?page=1&size=5' $null $newTok
Check '改密状态下越权接口 403' $r.code 403

$r = Req 'change pwd' 'POST' '/api/auth/password' @{ oldPassword = 'G14@init2025'; newPassword = 'Test2024abc' } $newTok
Check '改密 200' $r.code 200
Check '改密后 mustChangePassword=false' $r.body.data.mustChangePassword $false
$newTok2 = $r.body.data.token

$r = Req 'old token invalid' 'GET' '/api/auth/me' $null $newTok
Check '改密后旧令牌 401' $r.code 401
$r = Req 'new token valid' 'GET' '/api/auth/me' $null $newTok2
Check '改密后新令牌 200' $r.code 200

# ------------------------------------------------------------
Section '7. 审核流程'
$regName = '2025' + (Get-Random -Minimum 1000 -Maximum 9999)
$r = Req 'register' 'POST' '/api/auth/register' @{ username = $regName; password = 'Reg2025abc'; realName = '新同学'; role = 'STUDENT'; department = '计算机学院2025级' }
Check '注册 200' $r.code 200

$r = Req 'login before audit' 'POST' '/api/auth/login' @{ username = $regName; password = 'Reg2025abc' }
Check '未审核不能登录 403' $r.code 403

$r = Req 'find pending' 'GET' "/api/users?keyword=$regName" $null $adminTok
$pendingId = $r.body.data.records[0].id
Check '找到待审核用户' ($pendingId -gt 0) $true
Check '待审核状态 0' $r.body.data.records[0].auditStatus 0

$r = Req 'reject without reason' 'PUT' "/api/users/$pendingId/audit" @{ auditStatus = 2 } $adminTok
Check '驳回无理由 400' $r.code 400

$r = Req 'reject with reason' 'PUT' "/api/users/$pendingId/audit" @{ auditStatus = 2; auditRemark = '学号不在名册内' } $adminTok
Check '驳回 200' $r.code 200

$r = Req 'login rejected' 'POST' '/api/auth/login' @{ username = $regName; password = 'Reg2025abc' }
Check '驳回后登录 403' $r.code 403
Check '驳回原因可见' ($r.body.msg -like '*学号不在名册内*') $true

$r = Req 'approve' 'PUT' "/api/users/$pendingId/audit" @{ auditStatus = 1 } $adminTok
Check '通过审核 200' $r.code 200

$r = Req 'login after approve' 'POST' '/api/auth/login' @{ username = $regName; password = 'Reg2025abc' }
Check '审核后登录 200' $r.code 200

# ------------------------------------------------------------
Section '8. 禁用与踢下线'
$r = Req 'disable' 'PUT' "/api/users/$pendingId/status?status=0" $null $adminTok
Check '禁用 200' $r.code 200

$r = Req 'login disabled' 'POST' '/api/auth/login' @{ username = $regName; password = 'Reg2025abc' }
Check '禁用后登录 403' $r.code 403

$r = Req 'enable' 'PUT' "/api/users/$pendingId/status?status=1" $null $adminTok
Check '启用 200' $r.code 200

# ------------------------------------------------------------
Section '9. 自我保护约束'
$r = Req 'admin self disable' 'PUT' '/api/users/1/status?status=0' $null $adminTok
Check '管理员不能禁用自己 400' $r.code 400

$r = Req 'admin self delete' 'DELETE' '/api/users/1' $null $adminTok
Check '管理员不能删除自己 400' $r.code 400

# ------------------------------------------------------------
Section '10. 权限追加（学生干部发卷）'
Req 'cleanup leftover' 'DELETE' '/api/rbac/users/3/permissions?permCode=survey:create' $null $adminTok | Out-Null
$r = Req 'student perms before' 'GET' "/api/rbac/users/3/permissions" $null $adminTok
Check '查学生权限 200' $r.code 200
Check '学生默认无 survey:create' ($r.body.data -contains 'survey:create') $false

$r = Req 'grant survey:create' 'POST' '/api/rbac/users/3/permissions?permCode=survey:create' $null $adminTok
Check '追加权限 200' $r.code 200

$r = Req 'student perms after' 'GET' '/api/rbac/users/3/permissions' $null $adminTok
Check '追加后含 survey:create' ($r.body.data -contains 'survey:create') $true

$r = Req 'grant dup' 'POST' '/api/rbac/users/3/permissions?permCode=survey:create' $null $adminTok
Check '重复追加 409' $r.code 409

$r = Req 'revoke' 'DELETE' '/api/rbac/users/3/permissions?permCode=survey:create' $null $adminTok
Check '撤销权限 200' $r.code 200

$r = Req 'student perms final' 'GET' '/api/rbac/users/3/permissions' $null $adminTok
Check '撤销后无 survey:create' ($r.body.data -contains 'survey:create') $false

# ------------------------------------------------------------
Section '11. 参数校验'
$r = Req 'bad login body' 'POST' '/api/auth/login' @{ username = ''; password = '' }
Check '空账号密码 400' $r.code 400

$r = Req 'bad page type' 'GET' '/api/users?page=abc' $null $adminTok
Check '分页参数类型错 400' $r.code 400

$r = Req 'not found api' 'GET' '/api/not-exist' $null $adminTok
Check '不存在的接口 404' $r.code 404

$r = Req 'wrong method' 'GET' '/api/auth/login'
Check '不支持的请求方法 405' $r.code 405

# ------------------------------------------------------------
Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host ("结果：通过 {0} 项，失败 {1} 项" -f $pass, $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "============================================" -ForegroundColor Cyan
if ($fail -gt 0) { exit 1 }
