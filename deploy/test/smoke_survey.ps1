# ============================================================
# 问卷模块（创建/编辑/题目/发布/分发）接口冒烟测试
# 用法：& "D:\G14(1)\deploy\test\smoke_survey.ps1"
# 前置：后端已在 http://localhost:8080 启动
# 说明：脚本自带幂等清理，可重复执行
# ============================================================
$ErrorActionPreference = 'Continue'
$BASE = 'http://localhost:8080'
$pass = 0; $fail = 0

function Api($method, $path, $body, $token) {
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    try {
        $p = @{ Uri = "$BASE$path"; Method = $method; Headers = $h; UseBasicParsing = $true }
        if ($null -ne $body) {
            $p['ContentType'] = 'application/json;charset=utf-8'
            if ($body -is [string]) {
                $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes($body)
            } else {
                $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress))
            }
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

function CheckTrue($name, $cond, $desc) {
    if ($cond) {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $name  $desc" -ForegroundColor Red
    }
}

function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

# ------------------------------------------------------------
Section '1. 登录准备'
$r = Api 'POST' '/api/auth/login' @{ username = 'teacher'; password = 'Teacher@123' } $null
Check 'teacher 登录' $r.code 200
$tokT = $r.body.data.token
CheckTrue 'teacher 有 survey:create' ($r.body.data.permissions -contains 'survey:create') '缺少 survey:create'

$r = Api 'POST' '/api/auth/login' @{ username = 'student'; password = 'Student@123' } $null
Check 'student 登录' $r.code 200
$tokS = $r.body.data.token
$studentPerms = $r.body.data.permissions
CheckTrue 'student 无 survey:create' (-not ($studentPerms -contains 'survey:create')) '学生不应有建卷权限'

$r = Api 'POST' '/api/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null
Check 'admin 登录' $r.code 200
$tokA = $r.body.data.token

if (-not $tokT -or -not $tokS -or -not $tokA) {
    Write-Host '令牌缺失，中止' -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------
Section '2. 权限边界'
$r = Api 'POST' '/api/surveys' @{ title = '学生越权建卷' } $tokS
Check '学生建卷 403' $r.code 403

$r = Api 'GET' '/api/surveys?scope=all' $null $tokT
Check 'teacher 查全量 403' $r.code 403

$r = Api 'GET' '/api/surveys?scope=all' $null $tokA
Check 'admin 查全量 200' $r.code 200

$r = Api 'GET' '/api/surveys' $null $null
Check '未登录查列表 401' $r.code 401

# ------------------------------------------------------------
Section '3. 新建问卷（草稿）'
$r = Api 'POST' '/api/surveys' @{
    title       = '冒烟测试问卷'
    description = '自动化脚本创建'
    category    = 'SATISFACTION'
    anonymous   = 1
    limitOnce   = 1
} $tokT
Check '新建 200' $r.code 200
$sid = $r.body.data.id
CheckTrue '初始状态为 DRAFT' ($r.body.data.status -eq 'DRAFT') "实际=$($r.body.data.status)"
Check '题目数为 0' $r.body.data.questionCount 0
Check '分类中文名' $r.body.data.categoryLabel '满意度'
Check '匿名=1' $r.body.data.anonymous 1
Check '新建时无分享码' ([string]::IsNullOrEmpty($r.body.data.shareCode)) $true
Check '草稿不可填写' $r.body.data.fillable $false

$r = Api 'POST' '/api/surveys' @{ title = '' } $tokT
Check '标题为空 400' $r.code 400

$r = Api 'POST' '/api/surveys' @{ title = '时间窗非法'; startTime = '2026-12-31 10:00:00'; endTime = '2026-12-01 10:00:00' } $tokT
Check '结束早于开始 400' $r.code 400
CheckTrue '时间窗提示' ($r.body.msg -like '*截止时间必须晚于开始时间*') "实际=$($r.body.msg)"

# ------------------------------------------------------------
Section '4. 题目保存（差量 + 校验）'
$qs = @{ questions = @(
        @{ questionType = 'SINGLE'; title = '整体满意度？'; isRequired = 1
            options   = @(@{ content = '非常满意' }, @{ content = '满意' }, @{ content = '不满意' }) },
        @{ questionType = 'MULTIPLE'; title = '希望增加哪些形式？'
            options   = @(@{ content = '案例讨论' }, @{ content = '实验实操' }) },
        @{ questionType = 'TEXT'; title = '学号后四位'; maxLength = 20 },
        @{ questionType = 'TEXTAREA'; title = '其他建议'; maxLength = 300 },
        @{ questionType = 'RATING'; title = '推荐程度'; ratingMax = 5 }
    )
}
$r = Api 'PUT' "/api/surveys/$sid/questions" $qs $tokT
Check '保存题目 200' $r.code 200
Check '题目数=5' $r.body.data.questionCount 5
Check '选项数=3' $r.body.data.questions[0].options.Count 3
Check '排序从 1 开始' $r.body.data.questions[0].sortNo 1
$qid1 = $r.body.data.questions[0].id

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(@{ questionType = 'SINGLE'; title = '只有一个选项'; options = @(@{ content = 'A' }) }) } $tokT
Check '选择题仅 1 项 400' $r.code 400

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(@{ questionType = 'SINGLE'; title = '重复选项'; options = @(@{ content = 'A' }, @{ content = 'A' }) }) } $tokT
Check '重复选项 400' $r.code 400

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(@{ questionType = 'TEXT'; title = '文本带选项'; options = @(@{ content = 'A' }, @{ content = 'B' }) }) } $tokT
Check '非选择题带选项 400' $r.code 400

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(@{ questionType = 'CHECKBOX'; title = '未知题型' }) } $tokT
Check '未知题型 400' $r.code 400

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(@{ id = 999999; questionType = 'TEXT'; title = '外部题目' }) } $tokT
Check '题目 ID 不属于本卷 400' $r.code 400

# 差量保存：带 id 的题目应被原地更新（不清空，保留 id 才能验证「更新」分支）
$qs2 = @{ questions = @(
        @{ id = $qid1; questionType = 'SINGLE'; title = '整体满意度（已编辑）？'; isRequired = 1
            options   = @(@{ content = '非常满意' }, @{ content = '满意' }, @{ content = '不满意' }, @{ content = '很不满意' }) },
        @{ questionType = 'TEXTAREA'; title = '其他建议'; maxLength = 300 }
    )
}
$r = Api 'PUT' "/api/surveys/$sid/questions" $qs2 $tokT
Check '差量保存 200' $r.code 200
Check '题目数=2' $r.body.data.questionCount 2
Check '题干已更新' $r.body.data.questions[0].title '整体满意度（已编辑）？'
Check '题目 ID 保持不变' $r.body.data.questions[0].id $qid1
Check '选项更新为 4 项' $r.body.data.questions[0].options.Count 4

# 清空题目：应允许（草稿态），并把 question_count 归零
$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @() } $tokT
Check '清空题目 200' $r.code 200
Check '清空后题目数=0' $r.body.data.questionCount 0

# 清空后旧题目 ID 不可复用（已被物理删除），必须报 400
$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(@{ id = $qid1; questionType = 'TEXT'; title = '复用已删题目' }) } $tokT
Check '已删除题目 ID 复用 400' $r.code 400

# 重新建题准备发布（旧题已被清空，这里全部不带 id，走「新增」分支）
$qs3 = @{ questions = @(
        @{ questionType = 'SINGLE'; title = '整体满意度？'; isRequired = 1
            options   = @(@{ content = '非常满意' }, @{ content = '满意' }, @{ content = '不满意' }, @{ content = '很不满意' }) },
        @{ questionType = 'TEXTAREA'; title = '其他建议'; maxLength = 300 }
    )
}
$r = Api 'PUT' "/api/surveys/$sid/questions" $qs3 $tokT
Check '重新保存题目 200' $r.code 200
Check '题目数恢复为 2' $r.body.data.questionCount 2
$qid1 = $r.body.data.questions[0].id

# ------------------------------------------------------------
Section '5. 发布与结构锁定'
$r = Api 'POST' "/api/surveys/$sid/publish" $null $tokT
Check '发布 200' $r.code 200
Check '状态 PUBLISHED' $r.body.data.status 'PUBLISHED'
CheckTrue '分享码 8 位' ($r.body.data.shareCode.Length -eq 8) "实际=$($r.body.data.shareCode)"
$share = $r.body.data.shareCode
CheckTrue '发布时间已写入' (-not [string]::IsNullOrEmpty($r.body.data.publishedTime)) 'publishedTime 为空'

$r = Api 'POST' "/api/surveys/$sid/publish" $null $tokT
Check '重复发布 400' $r.code 400

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @() } $tokT
Check '发布后改题 400' $r.code 400
CheckTrue '锁题提示' ($r.body.msg -like '*已锁定*') "实际=$($r.body.msg)"

$r = Api 'PUT' "/api/surveys/$sid" @{ title = '冒烟测试问卷（改名）'; description = '发布后只改标题简介'; anonymous = 0 } $tokT
Check '发布后改匿名设置 400' $r.code 400

$r = Api 'PUT' "/api/surveys/$sid" @{ title = '冒烟测试问卷（改名）'; description = '发布后只改标题简介'; anonymous = 1 } $tokT
Check '发布后改标题 200' $r.code 200
Check '标题已变' $r.body.data.title '冒烟测试问卷（改名）'

# ------------------------------------------------------------
Section '6. 分享码访问'
$r = Api 'GET' "/api/surveys/share/$share" $null $tokS
Check '学生凭分享码取卷 200' $r.code 200
Check '返回题目数=2' $r.body.data.questions.Count 2
CheckTrue '不返回创建人姓名' ([string]::IsNullOrEmpty($r.body.data.createByName)) "实际=$($r.body.data.createByName)"
CheckTrue '可填写' ($r.body.data.fillable -eq $true) 'fillable 应为 true'

$r = Api 'GET' "/api/surveys/share/ZZZZZZZZ" $null $tokS
Check '无效分享码 404' $r.code 404

if ($share) {
    $r = Api 'GET' "/api/surveys/share/$($share.ToLower())" $null $tokS
    Check '分享码大小写不敏感 200' $r.code 200
} else {
    Check '分享码大小写不敏感 200' 'no-share-code' 200
}

# ------------------------------------------------------------
Section '7. 归属校验'
$r = Api 'POST' '/api/surveys' @{ title = 'admin 的问卷' } $tokA
$adminSid = $r.body.data.id
Check 'admin 建卷 200' $r.code 200

$r = Api 'GET' "/api/surveys/$adminSid" $null $tokT
Check 'teacher 读他人问卷 403' $r.code 403

$r = Api 'PUT' "/api/surveys/$adminSid" @{ title = '越权改名' } $tokT
Check 'teacher 改他人问卷 403' $r.code 403

$r = Api 'DELETE' "/api/surveys/$adminSid" $null $tokT
Check 'teacher 删他人问卷 403' $r.code 403

$r = Api 'GET' "/api/surveys/$adminSid" $null $tokA
Check 'admin 读自己问卷 200' $r.code 200

$r = Api 'GET' '/api/surveys/999999' $null $tokT
Check '不存在问卷 404' $r.code 404

# teacher 只能看到自己的问卷
$r = Api 'GET' '/api/surveys?size=100' $null $tokT
$ownOnly = $true
foreach ($s in $r.body.data.records) {
    if ($s.createBy -ne 2) { $ownOnly = $false }
}
CheckTrue 'teacher 列表仅本人问卷' $ownOnly '出现了他人问卷'

# ------------------------------------------------------------
Section '8. 暂停 / 恢复 / 结束'
$r = Api 'POST' "/api/surveys/$sid/pause" $null $tokT
Check '暂停 200' $r.code 200
Check '状态 PAUSED' $r.body.data.status 'PAUSED'

$r = Api 'GET' "/api/surveys/share/$share" $null $tokS
Check '暂停后仍可取结构 200' $r.code 200
Check '暂停后不可填写' $r.body.data.fillable $false

$r = Api 'POST' "/api/surveys/$sid/resume" $null $tokT
Check '恢复 200' $r.code 200
Check '状态 PUBLISHED' $r.body.data.status 'PUBLISHED'

$r = Api 'POST' "/api/surveys/$sid/resume" $null $tokT
Check '非暂停态恢复 400' $r.code 400

$r = Api 'POST' "/api/surveys/$sid/close" $null $tokT
Check '结束 200' $r.code 200
Check '状态 CLOSED' $r.body.data.status 'CLOSED'
CheckTrue '结束时间已写入' (-not [string]::IsNullOrEmpty($r.body.data.closedTime)) 'closedTime 为空'

$r = Api 'POST' "/api/surveys/$sid/resume" $null $tokT
Check '结束后不能恢复 400' $r.code 400

$r = Api 'GET' "/api/surveys/share/$share" $null $tokS
Check '结束后不可填写' $r.body.data.fillable $false

# ------------------------------------------------------------
Section '9. 复制问卷'
$r = Api 'POST' "/api/surveys/$sid/copy" $null $tokT
Check '复制 200' $r.code 200
$copyId = $r.body.data.id
CheckTrue '副本标题带（副本）' ($r.body.data.title -like '*（副本）') "实际=$($r.body.data.title)"
Check '副本状态 DRAFT' $r.body.data.status 'DRAFT'
Check '副本答卷数 0' $r.body.data.answerCount 0
CheckTrue '副本无分享码' ([string]::IsNullOrEmpty($r.body.data.shareCode)) '不该复制分享码'
Check '副本保留题目' $r.body.data.questions.Count 2
CheckTrue '副本题目 ID 与源不同' ($r.body.data.questions[0].id -ne $qid1) '题目 ID 未重建'

$r = Api 'PUT' "/api/surveys/$copyId/questions" @{ questions = @(@{ questionType = 'TEXT'; title = '副本新增题' }) } $tokT
Check '副本可改题 200' $r.code 200

# ------------------------------------------------------------
Section '10. 列表筛选'
$r = Api 'GET' '/api/surveys?keyword=冒烟' $null $tokT
Check '关键词筛选 200' $r.code 200
CheckTrue '筛选到结果' ($r.body.data.total -ge 1) "total=$($r.body.data.total)"

$r = Api 'GET' '/api/surveys?status=DRAFT' $null $tokT
Check '状态筛选 200' $r.code 200
$allDraft = $true
foreach ($s in $r.body.data.records) { if ($s.status -ne 'DRAFT') { $allDraft = $false } }
CheckTrue '状态筛选结果全为 DRAFT' $allDraft '混入了非草稿'

$r = Api 'GET' '/api/surveys?status=UNKNOWN' $null $tokT
Check '非法状态 400' $r.code 400

$r = Api 'GET' '/api/surveys?category=SATISFACTION' $null $tokT
Check '分类筛选 200' $r.code 200

# ------------------------------------------------------------
Section '11. 删除与确认'
$r = Api 'DELETE' "/api/surveys/$copyId" $null $tokT
Check '删除草稿 200' $r.code 200

$r = Api 'GET' "/api/surveys/$copyId" $null $tokT
Check '删除后 404' $r.code 404

$r = Api 'DELETE' "/api/surveys/$adminSid" $null $tokA
Check 'admin 删自己问卷 200' $r.code 200

# ------------------------------------------------------------
Section '12. 幂等清理'
$r = Api 'DELETE' "/api/surveys/$sid" $null $tokT
Check '清理测试问卷 200' $r.code 200

# ------------------------------------------------------------
Write-Host "`n----------------------------------------" -ForegroundColor Yellow
Write-Host ("通过 $pass 项，失败 $fail 项") -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if ($fail -gt 0) { exit 1 }
