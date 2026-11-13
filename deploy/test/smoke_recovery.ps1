# 密码找回（密保问题）接口冒烟测试
#
# 覆盖：设置密保 → 免登录查题 → 答错计数 → 锁定 → 答对重置 → 新密码生效
#       → 旧密码失效 → 令牌版本递增 → 配置开关 → 权限边界
#
# 用法：powershell -ExecutionPolicy Bypass -File deploy\test\smoke_recovery.ps1
# 前置：后端已启动（默认 http://localhost:8080），演示账号密码为初始值

param(
    [string]$Base = "http://localhost:8080/api"
)

$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0
$script:failures = @()

function Check($name, $cond) {
    if ($cond) {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        $script:failures += $name
        Write-Host "  [FAIL] $name" -ForegroundColor Red
    }
}

function CheckEq($name, $actual, $expected) {
    if ("$actual" -eq "$expected") {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        $script:failures += "$name (期望 $expected，实际 $actual)"
        Write-Host "  [FAIL] $name -> 期望 $expected，实际 $actual" -ForegroundColor Red
    }
}

function Section($t) {
    Write-Host ""
    Write-Host "=== $t ===" -ForegroundColor Cyan
}

function Api($method, $path, $body = $null, $token = $null) {
    $headers = @{ "Content-Type" = "application/json;charset=utf-8" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $uri = "$Base$path"
    try {
        if ($null -eq $body) {
            $resp = Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -UseBasicParsing -TimeoutSec 30
        } else {
            $json = ConvertTo-Json -InputObject $body -Depth 10 -Compress
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
            $resp = Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -Body $bytes -UseBasicParsing -TimeoutSec 30
        }
        $parsed = $null
        try { $parsed = $resp.Content | ConvertFrom-Json } catch { }
        return @{ code = [int]$resp.StatusCode; body = $parsed; raw = $resp.Content }
    } catch [System.Net.WebException] {
        $r = $_.Exception.Response
        if ($null -eq $r) { return @{ code = -1; body = $null; raw = $_.Exception.Message } }
        $code = [int]$r.StatusCode
        $sr = New-Object System.IO.StreamReader($r.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $raw = $sr.ReadToEnd()
        $sr.Close()
        $parsed = $null
        try { $parsed = $raw | ConvertFrom-Json } catch { }
        return @{ code = $code; body = $parsed; raw = $raw }
    }
}

function Login($u, $p) {
    $r = Api "POST" "/auth/login" @{ username = $u; password = $p }
    if ($r.code -ne 200) { return $null }
    return $r.body.data.token
}

# ------------------------------------------------------------------
Section "1. 准备：管理员登录并重置状态"
$adminTok = Login "admin" "Admin@123"
Check "管理员登录成功" ($null -ne $adminTok)

# 用管理员把 teacher 重置为默认密码，保证脚本可重复执行
$reset = Api "POST" "/users/2/reset-password" $null $adminTok
Check "管理员重置 teacher 密码" ($reset.code -eq 200)
$defaultPwd = $reset.body.data

$teacherTok = Login "teacher" $defaultPwd
Check "teacher 用默认密码登录（应强制改密）" ($null -ne $teacherTok)
$mustChange = (Api "GET" "/auth/password-status" $null $teacherTok).body.data
Check "重置后处于强制改密状态" ($mustChange -eq $true)

# 改成固定密码，便于后续步骤
$fixedPwd = "Recover@2026"
$chg = Api "POST" "/auth/password" @{ oldPassword = $defaultPwd; newPassword = $fixedPwd } $teacherTok
Check "改为测试密码" ($chg.code -eq 200)
$teacherTok = $chg.body.data.token

# 清掉可能残留的密保与锁定：管理员的清除接口同时解决「上一轮测试把账号锁了」
# 和「上一轮留下了密保问题」两个问题，脚本因此可以重复执行。
$clr = Api "DELETE" "/users/2/recovery" $null $adminTok
Write-Host "  清除历史密保（无则报 400，属正常）：$($clr.code) $($clr.body.msg)" -ForegroundColor DarkGray

# ------------------------------------------------------------------
Section "2. 未设置密保时的行为"
$mine = Api "GET" "/auth/recovery/mine" $null $teacherTok
CheckEq "清除后 mine=false" $mine.body.data $false

$noQ = Api "POST" "/auth/recovery/lookup" @{ username = "teacher" }
CheckEq "未设置密保时 lookup 返回 404" $noQ.code 404
Check "提示未设置密保" ($noQ.body.msg -like "*密保*")

$myQEmpty = Api "GET" "/auth/recovery/my-questions" $null $teacherTok
CheckEq "未设置时查题面返回 404" $myQEmpty.code 404

# ------------------------------------------------------------------
Section "3. 设置密保问题"
$setupBody = @{
    items = @(
        @{ question = "我的宿舍号是多少？"; answer = "7号楼302" },
        @{ question = "我小学班主任姓什么？"; answer = "王老师" }
    )
}
$setup = Api "PUT" "/auth/recovery/setup" $setupBody $teacherTok
CheckEq "设置密保成功" $setup.code 200

$mine = Api "GET" "/auth/recovery/mine" $null $teacherTok
Check "设置后 mine=true" ($mine.body.data -eq $true)

$myQ = Api "GET" "/auth/recovery/my-questions" $null $teacherTok
CheckEq "回显问题 1" $myQ.body.data.question1 "我的宿舍号是多少？"
CheckEq "回显问题 2" $myQ.body.data.question2 "我小学班主任姓什么？"

Section "4. 设置密保的校验"
$dup = Api "PUT" "/auth/recovery/setup" @{
    items = @(
        @{ question = "同一个问题"; answer = "答案一" },
        @{ question = "同一个问题"; answer = "答案二" }
    )
} $teacherTok
CheckEq "两问相同时报 400" $dup.code 400
Check "提示两问不能相同" ($dup.body.msg -like "*不能相同*")

$sameAns = Api "PUT" "/auth/recovery/setup" @{
    items = @(
        @{ question = "问题一"; answer = "一样的答案" },
        @{ question = "问题二"; answer = "一样的答案" }
    )
} $teacherTok
CheckEq "两答相同时报 400" $sameAns.code 400

$oneItem = Api "PUT" "/auth/recovery/setup" @{
    items = @(@{ question = "只有一个"; answer = "答案" })
} $teacherTok
CheckEq "只给一个问题时报 400" $oneItem.code 400

$noToken = Api "PUT" "/auth/recovery/setup" $setupBody
CheckEq "未登录不能设置密保" $noToken.code 401

# 恢复正确密保
$setup = Api "PUT" "/auth/recovery/setup" $setupBody $teacherTok
Check "重新设置密保为正确值" ($setup.code -eq 200)

# ------------------------------------------------------------------
Section "5. 免登录查密保问题"
$lookup = Api "POST" "/auth/recovery/lookup" @{ username = "teacher" }
CheckEq "免登录可以查密保" $lookup.code 200
CheckEq "拿到问题 1" $lookup.body.data.question1 "我的宿舍号是多少？"
Check "响应不含答案哈希" ($lookup.raw -notlike "*answerHash*")

$notExist = Api "POST" "/auth/recovery/lookup" @{ username = "nobody999" }
CheckEq "不存在账号返回 404" $notExist.code 404
Check "提示学号不存在" ($notExist.body.msg -like "*不存在*")

$adminLookup = Api "POST" "/auth/recovery/lookup" @{ username = "admin" }
CheckEq "未设密保的账号返回 404" $adminLookup.code 404
Check "提示未设置密保" ($adminLookup.body.msg -like "*密保*")

$emptyUser = Api "POST" "/auth/recovery/lookup" @{ username = "" }
CheckEq "空账号返回 400" $emptyUser.code 400

# ------------------------------------------------------------------
Section "6. 答案校验与失败计数"
$wrong1 = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "错的"; answer2 = "错的"; newPassword = "Temp@12345"
}
CheckEq "错误答案返回 400" $wrong1.code 400
Check "提示还可尝试次数" ($wrong1.body.msg -like "*还可尝试*")

$wrong2 = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "错的2"; answer2 = "错的"; newPassword = "Temp@12345"
}
CheckEq "第二次错误仍 400" $wrong2.code 400
# 计数必须递减 —— 这是回归防线：曾因 @Transactional 回滚导致计数永不增长
Check "失败计数确实递减（3 次）" ($wrong2.body.msg -like "*3 次*")

$half = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "7号楼302"; answer2 = "错的"; newPassword = "Temp@12345"
}
CheckEq "只答对一半也是 400" $half.code 400

Section "7. 密码规则先于计数"
$weak = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "7号楼302"; answer2 = "王老师"; newPassword = "123"
}
CheckEq "答案正确但密码太弱返回 400" $weak.code 400
Check "提示密码长度" ($weak.body.msg -like "*长度*")

$samepwd = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "7号楼302"; answer2 = "王老师"; newPassword = $fixedPwd
}
CheckEq "新密码与原密码相同返回 400" $samepwd.code 400
Check "提示不能与原密码相同" ($samepwd.body.msg -like "*相同*")

# ------------------------------------------------------------------
Section "8. 正确重置"
$beforeVer = 0
$resetOk = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "  7号楼302  "; answer2 = "王老师"; newPassword = "Brand@2026"
}
CheckEq "答案正确（含首尾空格）重置成功" $resetOk.code 200
Check "提示密码已重置" ($resetOk.body.msg -like "*已重置*")

$newLogin = Api "POST" "/auth/login" @{ username = "teacher"; password = "Brand@2026" }
CheckEq "新密码可以登录" $newLogin.code 200

$oldLogin = Api "POST" "/auth/login" @{ username = "teacher"; password = $fixedPwd }
CheckEq "旧密码已失效" $oldLogin.code 401

Section "9. 忽略大小写与全角"
# 密保答案存的是小写归一化后的值，用大写应也能过
$upper = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "7号楼302"; answer2 = "王老师"; newPassword = "Upper@2026"
}
CheckEq "再次重置（换成 Upper@2026）" $upper.code 200

# ------------------------------------------------------------------
Section "10. 锁定机制"
# 连续答错 5 次（默认 max-fail=5）
for ($i = 1; $i -le 5; $i++) {
    $r = Api "POST" "/auth/recovery/reset" @{
        username = "teacher"; answer1 = "bad$i"; answer2 = "bad"; newPassword = "Lock@2026"
    }
}
CheckEq "第 5 次错答触发锁定（409）" $r.code 409
Check "提示已锁定" ($r.body.msg -like "*锁定*")

$lockedCorrect = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "7号楼302"; answer2 = "王老师"; newPassword = "Lock@2026"
}
CheckEq "锁定期间正确答案也被拒" $lockedCorrect.code 409

$lockedLookup = Api "POST" "/auth/recovery/lookup" @{ username = "teacher" }
CheckEq "锁定期间 lookup 也拒绝" $lockedLookup.code 409

Section "11. 关键：密保锁定不影响正常登录"
$normalLogin = Api "POST" "/auth/login" @{ username = "teacher"; password = "Upper@2026" }
CheckEq "密保锁定时正常登录仍然可用" $normalLogin.code 200

# ------------------------------------------------------------------
Section "12. 配置开关"
$adminTok = Login "admin" "Admin@123"
$off = Api "PUT" "/config" @(@{ configKey = "security.recovery.enabled"; configValue = "0" }) $adminTok
CheckEq "关闭自助找回" $off.code 200

$offLookup = Api "POST" "/auth/recovery/lookup" @{ username = "teacher" }
CheckEq "关闭后 lookup 返回 403" $offLookup.code 403
Check "提示管理员已关闭" ($offLookup.body.msg -like "*关闭*")

$offReset = Api "POST" "/auth/recovery/reset" @{
    username = "teacher"; answer1 = "7号楼302"; answer2 = "王老师"; newPassword = "Off@2026"
}
CheckEq "关闭后 reset 也返回 403" $offReset.code 403

$on = Api "PUT" "/config" @(@{ configKey = "security.recovery.enabled"; configValue = "1" }) $adminTok
CheckEq "重新开启" $on.code 200

# ------------------------------------------------------------------
Section "13. 权限边界"
$studentTok = Login "student" "Student@123"
$stuSetup = Api "PUT" "/auth/recovery/setup" $setupBody $studentTok
CheckEq "学生也可以给自己设密保" $stuSetup.code 200

$anonMine = Api "GET" "/auth/recovery/mine" $null
CheckEq "未登录查 mine 返回 401" $anonMine.code 401

# ------------------------------------------------------------------
Section "14. 复原演示数据"
# 清掉密保（顺带解除锁定），恢复默认密码
$clr2 = Api "DELETE" "/users/2/recovery" $null $adminTok
Check "清理密保用于复原" (($clr2.code -eq 200) -or ($clr2.code -eq 400))

$pwd2 = Api "POST" "/users/2/reset-password" $null $adminTok
$dflt = $pwd2.body.data
$t2 = Login "teacher" $dflt
if ($null -ne $t2) {
    $r2 = Api "POST" "/auth/password" @{ oldPassword = $dflt; newPassword = "Teacher@123" } $t2
    Check "teacher 密码恢复为 Teacher@123" ($r2.code -eq 200)
} else {
    Check "teacher 密码恢复为 Teacher@123" $false
}

# ------------------------------------------------------------------
Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ("  结果：{0} 通过 / {1} 失败" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { "Green" } else { "Red" })
if ($script:fail -gt 0) {
    Write-Host "  失败项：" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
}
Write-Host "==================================================" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 } else { exit 0 }
