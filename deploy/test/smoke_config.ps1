# =============================================================================
# 校园问卷系统 —— 系统配置模块冒烟测试（FR-CFG-01 / 02 / 03）
#
# 覆盖：
#   1  登录准备
#   2  公开配置：免登录可读、只含 is_public=1、私有项不泄露
#   3  权限边界：教师/学生读全量 403、无令牌 401
#   4  读取全量：按分组归拢、PASSWORD 打码、builtin 标记
#   5  保存生效：站点名立即对外可见、密码规则立即作用于注册/建号/改密
#   6  掩码回传视为未修改（changed=0）
#   7  内置项不可删除
#   8  自定义项：新增 → 重复 409 → 删除 → 同键复活
#   9  参数校验：坏键名、非法布尔、非法数字、不存在的键
#  10  复原与汇总
#
# 运行前需后端已启动（http://localhost:8080）：
#   powershell -File deploy/test/smoke_config.ps1
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

function CheckMsg($name, $r, $fragment) {
    $msg = if ($r.body) { [string]$r.body.msg } else { '' }
    CheckTrue $name ($msg -like "*$fragment*") "msg=[$msg] 应包含[$fragment]"
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
        # 必须用 -InputObject：$body 是数组时，管道会把单元素数组拆成裸对象，后端收到 {..} 而非 [{..}] 直接 500
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $body -Depth 10 -Compress))
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

# 从 listGrouped 的结果里按 key 找一项
function FindCfg($grouped, $key) {
    foreach ($g in $grouped.PSObject.Properties) {
        foreach ($it in $g.Value) {
            if ($it.configKey -eq $key) { return $it }
        }
    }
    return $null
}

# 先把配置归一化：本脚本会改站点名与密码规则，中途失败会留下脏状态，
# 不归一化的话第二次跑第 2 节就会读到上一轮的值。
$r = Api 'PUT' '/config' @(
    @{ configKey = 'site.name'; configValue = '校园问卷系统' },
    @{ configKey = 'security.password.min-length'; configValue = '8' },
    @{ configKey = 'security.password.max-length'; configValue = '32' },
    @{ configKey = 'security.password.require-letter'; configValue = '1' },
    @{ configKey = 'security.password.require-digit'; configValue = '1' },
    @{ configKey = 'security.password.require-special'; configValue = '0' }
) (Login 'admin' 'Admin@123')
Write-Host "  配置已归一化：changed=$($r.body.data)" -ForegroundColor DarkGray

$stamp = Get-Date -Format 'HHmmss'
$tempUser = "cfg$stamp"
# 固定键而不是带时间戳：每轮都是「在同一行上 创建→删除→复活→删除」，
# 这样逻辑删除留下的墓碑行永远只有一条，还能被下一轮直接复活复用。
$customKey = "smoke.temp.config"

# ---------------------------------------------------------------- 1
Section '1 登录准备'
$admin = Login 'admin' 'Admin@123'
CheckTrue '管理员登录成功' ($admin.Length -gt 20)
$teacher = Login 'teacher' 'Teacher@123'
CheckTrue '教师登录成功' ($teacher.Length -gt 20)

# ---------------------------------------------------------------- 2
Section '2 公开配置（未登录）'
$r = Api 'GET' '/config/public' $null $null
Check '公开配置 200' $r.code 200
CheckTrue '返回 code=200 包裹' ($r.body.code -eq 200) "实际 code=$($r.body.code)"
Check '含 site.name' $r.body.data.'site.name' '校园问卷系统'
CheckTrue '含密码最小长度' ($null -ne $r.body.data.'security.password.min-length')
CheckTrue '含密码最大长度' ($null -ne $r.body.data.'security.password.max-length')
CheckTrue '含 require-letter' ($null -ne $r.body.data.'security.password.require-letter')
CheckTrue '含 require-digit' ($null -ne $r.body.data.'security.password.require-digit')
CheckTrue '含 require-special' ($null -ne $r.body.data.'security.password.require-special')
CheckTrue '不含默认密码（私有项）' ($null -eq $r.body.data.'security.default-password') '默认密码绝不能出现在公开配置里'
$pubCount = @($r.body.data.PSObject.Properties).Count
CheckTrue "公开项数量合理（当前 $pubCount 项）" ($pubCount -ge 9 -and $pubCount -le 12)

# ---------------------------------------------------------------- 3
Section '3 权限边界'
$r = Api 'GET' '/config' $null $null
Check '无令牌读全量 401' $r.code 401
$r = Api 'GET' '/config' $null $teacher
Check '教师读全量 403' $r.code 403
$r = Api 'PUT' '/config' @(@{ configKey = 'site.name'; configValue = 'x' }) $teacher
Check '教师改配置 403' $r.code 403
$r = Api 'POST' '/config' @{ configKey = 'x.y'; configValue = 'x'; configType = 'STRING' } $teacher
Check '教师新增配置 403' $r.code 403
$studentTok = Login 'student' 'Student@123'
$r = Api 'GET' '/config' $null $studentTok
Check '学生读全量 403' $r.code 403

# ---------------------------------------------------------------- 4
Section '4 读取全量（管理员）'
$r = Api 'GET' '/config' $null $admin
Check '全量配置 200' $r.code 200
$groups = $r.body.data
CheckTrue '含 SECURITY 分组' ($null -ne $groups.SECURITY)
CheckTrue '含 SITE 分组' ($null -ne $groups.SITE)
$dp = FindCfg $groups 'security.default-password'
CheckTrue '默认密码项存在' ($null -ne $dp)
Check '默认密码被打码' $dp.configValue '******'
Check '默认密码 masked=true' $dp.masked 'True'
Check '默认密码不公开' $dp.publicVisible 'False'
Check '默认密码是内置项' $dp.builtin 'True'
$sn = FindCfg $groups 'site.name'
Check '站点名不被打码' $sn.masked 'False'
Check '站点名公开' $sn.publicVisible 'True'
$ml = FindCfg $groups 'security.password.min-length'
Check '最小长度类型 NUMBER' $ml.configType 'NUMBER'
Check '最小长度分组 SECURITY' $ml.groupName 'SECURITY'

# ---------------------------------------------------------------- 5
Section '5 保存生效'
$r = Api 'PUT' '/config' @(@{ configKey = 'site.name'; configValue = 'G14 冒烟站点' }) $admin
Check '改站点名 200' $r.code 200
Check 'changed=1' $r.body.data 1
$r = Api 'GET' '/config/public' $null $null
Check '公开配置立即反映新站点名' $r.body.data.'site.name' 'G14 冒烟站点'

# 密码规则：把最小长度调到 12
$r = Api 'PUT' '/config' @(@{ configKey = 'security.password.min-length'; configValue = '12' }) $admin
Check '改最小长度 200' $r.code 200
$r = Api 'GET' '/config/public' $null $null
Check '公开配置立即反映 12' $r.body.data.'security.password.min-length' '12'

# 注册：8 位密码应被新规则拒绝
$r = Api 'POST' '/auth/register' @{ username = "${tempUser}a"; password = 'Abcd1234'; realName = '配置测试'; role = 'STUDENT' } $null
Check '注册 8 位密码 400' $r.code 400
CheckMsg '提示跟随配置（12~32）' $r '12~32'
# 注册：13 位应通过
$r = Api 'POST' '/auth/register' @{ username = "${tempUser}a"; password = 'Abcd123456789'; realName = '配置测试'; role = 'STUDENT' } $null
Check '注册 13 位密码 200' $r.code 200

# 管理员建用户：8 位密码应被拒
$r = Api 'POST' '/users' @{ username = "${tempUser}b"; realName = '配置测试B'; password = 'Abcd1234'; roleCode = 'ROLE_STUDENT' } $admin
Check '建用户 8 位密码 400' $r.code 400
CheckMsg '建用户提示跟随配置' $r '12~32'
# 管理员建用户：13 位应通过，并用来验证改密
$r = Api 'POST' '/users' @{ username = "${tempUser}b"; realName = '配置测试B'; password = 'Abcd123456789'; roleCode = 'ROLE_STUDENT' } $admin
Check '建用户 13 位密码 200' $r.code 200
$createdIds = New-Object System.Collections.ArrayList
[void]$createdIds.Add($r.body.data.id)

$tempTok = Login "${tempUser}b" 'Abcd123456789'
CheckTrue '新建用户可登录' ($tempTok.Length -gt 20)
# 改密：9 位应被拒（说明 DTO 的硬编码 8~32 已让位于配置）
$r = Api 'POST' '/auth/password' @{ oldPassword = 'Abcd123456789'; newPassword = 'Abcd12345' } $tempTok
Check '改密 9 位密码 400' $r.code 400
CheckMsg '改密提示跟随配置' $r '12~32'

# 上限调到 64：40 位密码应通过（验证 DTO 不再写死 32）
$r = Api 'PUT' '/config' @(
    @{ configKey = 'security.password.max-length'; configValue = '64' },
    @{ configKey = 'security.password.min-length'; configValue = '6' }
) $admin
Check '上限调到 64 + 下限 6 200' $r.code 200
Check 'changed=2' $r.body.data 2
$longPwd = 'A1' + ('x' * 38)
$r = Api 'POST' '/users' @{ username = "${tempUser}c"; realName = '配置测试C'; password = $longPwd; roleCode = 'ROLE_STUDENT' } $admin
Check '40 位密码建用户 200' $r.code 200
if ($r.code -eq 200) { [void]$createdIds.Add($r.body.data.id) }
# 注册的账号拿不到 id（接口只回 msg），最后按 keyword 查一次删掉
$regUsername = "${tempUser}a"

# ---------------------------------------------------------------- 6
Section '6 掩码回传视为未修改'
$r = Api 'PUT' '/config' @(@{ configKey = 'security.default-password'; configValue = '******' }) $admin
Check '掩码回传 200' $r.code 200
Check 'changed=0（没被当成新值）' $r.body.data 0
$r = Api 'GET' '/config' $null $admin
$dp2 = FindCfg $r.body.data 'security.default-password'
Check '默认密码仍是掩码' $dp2.configValue '******'

# ---------------------------------------------------------------- 7
Section '7 内置项不可删除'
$r = Api 'DELETE' "/config/$($dp2.id)" $null $admin
Check '删除内置项 400' $r.code 400
CheckMsg '提示内置项不可删' $r '内置配置项不能删除'
$r = Api 'GET' '/config' $null $admin
CheckTrue '内置项依然存在' ($null -ne (FindCfg $r.body.data 'security.default-password'))

# ---------------------------------------------------------------- 8
Section '8 自定义项：新增 / 重复 / 删除 / 复活'
$r = Api 'POST' '/config' @{ configKey = $customKey; configValue = 'v1'; configType = 'STRING'; groupName = 'SITE'; description = '冒烟测试项'; publicVisible = $true } $admin
Check '新增自定义项 200' $r.code 200
Check '新增项 builtin=false' $r.body.data.builtin 'False'
Check '新增项值 v1' $r.body.data.configValue 'v1'
$customId = $r.body.data.id
$r = Api 'POST' '/config' @{ configKey = $customKey; configValue = 'v2'; configType = 'STRING' } $admin
Check '同键再建 409' $r.code 409
CheckMsg '提示键已存在' $r '配置键已存在'
$r = Api 'GET' '/config/public' $null $null
Check '公开项出现在公开接口' $r.body.data.$customKey 'v1'
$r = Api 'DELETE' "/config/$customId" $null $admin
Check '删除自定义项 200' $r.code 200
$r = Api 'GET' '/config/public' $null $null
CheckTrue '删除后不再出现在公开接口' ($null -eq $r.body.data.$customKey)
$r = Api 'POST' '/config' @{ configKey = $customKey; configValue = 'v2'; configType = 'STRING'; groupName = 'SITE'; publicVisible = $true } $admin
Check '同键重建（复活墓碑行）200' $r.code 200
Check '复活后值是新值 v2' $r.body.data.configValue 'v2'
$revivedId = $r.body.data.id
$r = Api 'DELETE' "/config/$revivedId" $null $admin
Check '清理复活项 200' $r.code 200

# ---------------------------------------------------------------- 9
Section '9 参数校验'
$r = Api 'POST' '/config' @{ configKey = 'bad key!'; configValue = 'x'; configType = 'STRING' } $admin
Check '坏键名 400' $r.code 400
CheckMsg '提示键名规则' $r '配置键只能由'
$r = Api 'POST' '/config' @{ configKey = 'smoke.x'; configValue = 'x'; configType = 'WRONG' } $admin
Check '非法类型 400' $r.code 400
CheckMsg '提示类型不合法' $r '类型'
$r = Api 'PUT' '/config' @(@{ configKey = 'security.password.require-letter'; configValue = 'maybe' }) $admin
Check 'BOOLEAN 非法值 400' $r.code 400
CheckMsg '提示布尔取值' $r '0/1/true/false'
$r = Api 'PUT' '/config' @(@{ configKey = 'security.password.min-length'; configValue = 'abc' }) $admin
Check 'NUMBER 非法值 400' $r.code 400
CheckMsg '提示需为数字' $r '数字'
$r = Api 'PUT' '/config' @(@{ configKey = 'no.such.key'; configValue = 'x' }) $admin
Check '不存在的键 404' $r.code 404
CheckMsg '提示配置项不存在' $r '配置项不存在'

# ---------------------------------------------------------------- 10
Section '10 复原与清理'
$r = Api 'PUT' '/config' @(
    @{ configKey = 'site.name'; configValue = '校园问卷系统' },
    @{ configKey = 'security.password.min-length'; configValue = '8' },
    @{ configKey = 'security.password.max-length'; configValue = '32' },
    @{ configKey = 'security.password.require-letter'; configValue = '1' }
) $admin
Check '复原配置 200' $r.code 200
$r = Api 'GET' '/config/public' $null $null
Check '站点名已复原' $r.body.data.'site.name' '校园问卷系统'
Check '最小长度已复原' $r.body.data.'security.password.min-length' '8'
Check '最大长度已复原' $r.body.data.'security.password.max-length' '32'

# 清掉测试用户：建号时记下的 id 直接删，注册的那个再按 keyword 找 id
foreach ($id in $createdIds) {
    Api 'DELETE' "/users/$id" $null $admin | Out-Null
}
$r = Api 'GET' "/users?keyword=$regUsername" $null $admin
if ($r.code -eq 200 -and $r.body.data.records) {
    foreach ($rec in @($r.body.data.records)) {
        if ($rec.username -eq $regUsername) { Api 'DELETE' "/users/$($rec.id)" $null $admin | Out-Null }
    }
}
Write-Host "  测试用户已清理" -ForegroundColor DarkGray

Write-Host ""
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ("结果：通过 {0} 项，失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "=============================================" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
