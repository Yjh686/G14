# ============================================================
# 答题模块（问卷大厅 / 草稿 / 提交 / 答卷管理）接口冒烟测试
# 用法：& "D:\G14(1)\deploy\test\smoke_answer.ps1"
# 前置：后端已在 http://localhost:8080 启动
# 说明：脚本每次运行都自建问卷、结束时清理，可重复执行
# ============================================================
$ErrorActionPreference = 'Continue'
$BASE = 'http://localhost:8080'
$pass = 0; $fail = 0
$stamp = Get-Date -Format 'HHmmss'

function Api($method, $path, $body, $token) {
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    try {
        $p = @{ Uri = "$BASE$path"; Method = $method; Headers = $h; UseBasicParsing = $true }
        if ($null -ne $body) {
            $p['ContentType'] = 'application/json;charset=utf-8'
            if ($body -is [string]) { $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes($body) }
            else { $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10 -Compress)) }
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
    if ($actual -eq $expected) { $script:pass++; Write-Host "  [PASS] $name ($actual)" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  [FAIL] $name 期望=$expected 实际=$actual" -ForegroundColor Red }
}

function CheckTrue($name, $cond, $desc) {
    if ($cond) { $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  [FAIL] $name  $desc" -ForegroundColor Red }
}

function CheckMsg($name, $actual, $keyword) {
    $ok = $false
    if ($null -ne $actual) { $ok = ([string]$actual).Contains($keyword) }
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name  <<$keyword>>" -ForegroundColor Green }
    else { $script:fail++; Write-Host "  [FAIL] $name  期望含<$keyword> 实际=<$actual>" -ForegroundColor Red }
}

function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

# ------------------------------------------------------------
Section '1. 登录准备'
$r = Api 'POST' '/api/auth/login' @{ username = 'teacher'; password = 'Teacher@123' } $null
Check 'teacher 登录' $r.code 200
$tokT = $r.body.data.token
$tidT = $r.body.data.userId

$r = Api 'POST' '/api/auth/login' @{ username = 'student'; password = 'Student@123' } $null
Check 'student 登录' $r.code 200
$tokS = $r.body.data.token
$permsS = $r.body.data.permissions
CheckTrue 'student 有 survey:fill' ($permsS -contains 'survey:fill') '缺少 survey:fill'
CheckTrue 'student 有 draft:save:self' ($permsS -contains 'draft:save:self') '缺少 draft:save:self'
CheckTrue 'student 无 answer:read:survey:self' (-not ($permsS -contains 'answer:read:survey:self')) '学生不该能看他人答卷'

$r = Api 'POST' '/api/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null
Check 'admin 登录' $r.code 200
$tokA = $r.body.data.token

if (-not $tokT -or -not $tokS -or -not $tokA) { Write-Host '令牌缺失，中止' -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------
Section '2. 准备测试问卷（teacher 建卷 + 5 题 + 发布）'
$r = Api 'POST' '/api/surveys' @{
    title = "答题冒烟-$stamp"; description = '自动生成，脚本结束会删除'
    category = 'TEACHING'; anonymous = 1; limitOnce = 1
} $tokT
Check '创建问卷A（匿名+限填一次）' $r.code 200
$sidA = [long]$r.body.data.id
Check '初始状态 DRAFT' $r.body.data.status 'DRAFT'

$qs = @{ questions = @(
    @{ questionType = 'SINGLE';   title = '你对本课程的整体满意度？'; isRequired = 1; options = @(@{content='非常满意'},@{content='满意'},@{content='一般'},@{content='不满意'}) },
    @{ questionType = 'MULTIPLE'; title = '你最喜欢哪种教学方式？';   isRequired = 1; options = @(@{content='讲授'},@{content='案例讨论'},@{content='实验实操'}) },
    @{ questionType = 'TEXT';     title = '你的学号后四位（选填）';   isRequired = 0; maxLength = 20 },
    @{ questionType = 'TEXTAREA'; title = '还有什么建议？';           isRequired = 1; maxLength = 300 },
    @{ questionType = 'RATING';   title = '推荐学弟学妹选这门课吗？'; isRequired = 1; ratingMax = 5 }
) }
$r = Api 'PUT' "/api/surveys/$sidA/questions" $qs $tokT
Check '保存 5 道题' $r.code 200
Check '题目数为 5' ([int]$r.body.data.questionCount) 5

$r = Api 'GET' "/api/surveys/$sidA" $null $tokT
$qs0 = $r.body.data.questions
Check '取回答题 5 道' (@($qs0).Count) 5
$q1 = [long]$qs0[0].id; $q2 = [long]$qs0[1].id; $q3 = [long]$qs0[2].id; $q4 = [long]$qs0[3].id; $q5 = [long]$qs0[4].id
$o1 = [long]$qs0[0].options[0].id
$o2 = [long]$qs0[1].options[0].id
$o2b = [long]$qs0[1].options[1].id
Check '第1题是单选' $qs0[0].questionType 'SINGLE'
Check '第5题是评分' $qs0[4].questionType 'RATING'

$r = Api 'POST' "/api/surveys/$sidA/publish" $null $tokT
Check '发布问卷A' $r.code 200
$shareA = $r.body.data.shareCode
Check '分享码长度 8' ($shareA.Length) 8

# ------------------------------------------------------------
Section '3. 问卷大厅与填写状态'
$r = Api 'GET' '/api/hall?size=50' $null $tokS
Check 'student 可访问大厅' $r.code 200
$hall = @($r.body.data.records)
$mine = @($hall | Where-Object { [long]$_.id -eq $sidA })
Check '大厅包含问卷A' $mine.Count 1
Check '我的状态 NOT_STARTED' $mine[0].myStatus 'NOT_STARTED'
Check '卡片 fillable=true' $mine[0].fillable $true
Check '卡片 anonymous=true' $mine[0].anonymous $true

$r = Api 'GET' "/api/hall/surveys/$sidA/fill-state" $null $tokS
Check '取填写状态' $r.code 200
Check 'fillable=true' $r.body.data.fillable $true
Check 'submitted=false' $r.body.data.submitted $false
Check 'reason 为空' ([string]$r.body.data.reason) ''

$r = Api 'GET' '/api/hall?size=50' $null $null
Check '未登录取大厅 -> 401' $r.code 401

$r = Api 'GET' '/api/hall/surveys/999999/fill-state' $null $tokS
Check '不存在的问卷 -> 404' $r.code 404
CheckMsg '404 文案' $r.body.msg '问卷不存在'

# ------------------------------------------------------------
Section '4. 草稿保存与断点续答'
$r = Api 'GET' "/api/hall/surveys/$sidA/draft" $null $tokS
Check '初始无草稿' $r.code 200
CheckTrue '草稿为空' ($null -eq $r.body.data -or @($r.body.data).Count -eq 0) '初始应为空'

$draft = @{ answers = @(
    @{ questionId = $q1; optionValues = @($o1) },
    @{ questionId = $q2; optionValues = @($o2, $o2b) }
) }
$r = Api 'PUT' "/api/hall/surveys/$sidA/draft" $draft $tokS
Check '保存草稿' $r.code 200
CheckMsg '草稿保存提示' $r.body.msg '草稿已保存'
CheckTrue '返回 savedAt' ($null -ne $r.body.data.savedAt) '应返回保存时间'

$r = Api 'GET' "/api/hall/surveys/$sidA/draft" $null $tokS
Check '读回草稿 2 条' (@($r.body.data).Count) 2

$r = Api 'GET' '/api/hall?size=50' $null $tokS
$mine = @(@($r.body.data.records) | Where-Object { [long]$_.id -eq $sidA })
Check '大厅变为 DRAFT' $mine[0].myStatus 'DRAFT'
CheckTrue '大厅带 draftTime' ($null -ne $mine[0].draftTime) '应有草稿时间'

$r = Api 'PUT' "/api/hall/surveys/$sidA/draft" @{ answers = @(@{ questionId = 999999; textValue = 'x' }) } $tokS
Check '草稿中的非法题目被忽略（不报错）' $r.code 200

# ------------------------------------------------------------
Section '5. 提交校验（必答 / 题型 / 选项 / 字数 / 评分 / 越界题目）'
# 全部是选填题的问卷：空作答才应命中「至少回答一道题」
$r = Api 'POST' '/api/surveys' @{ title = "全选填-$stamp"; category = 'OTHER'; anonymous = 1; limitOnce = 1 } $tokT
Check '创建问卷E（全选填）' $r.code 200
$sidE = [long]$r.body.data.id
$r = Api 'PUT' "/api/surveys/$sidE/questions" @{ questions = @(@{ questionType = 'TEXT'; title = '选填意见'; isRequired = 0; maxLength = 100 }) } $tokT
$r = Api 'POST' "/api/surveys/$sidE/publish" $null $tokT
Check '发布问卷E' $r.code 200
$r = Api 'POST' "/api/hall/surveys/$sidE/submit" @{ answers = @() } $tokS
Check '全选填卷空作答 -> 400' $r.code 400
CheckMsg '空作答文案' $r.body.msg '至少回答一道题'

$r = Api 'POST' "/api/hall/surveys/$sidA/submit" @{ answers = @(@{ questionId = $q1; optionValues = @($o1) }) } $tokS
Check '缺必答 -> 400' $r.code 400
CheckMsg '缺必答文案' $r.body.msg '还有必答题未作答'
CheckMsg '缺必答定位到第 2 题' $r.body.msg '第 2 题'

$badSingle = @{ answers = @(
    @{ questionId = $q1; optionValues = @($o1, $o2) },
    @{ questionId = $q2; optionValues = @($o2) },
    @{ questionId = $q4; textValue = '无' },
    @{ questionId = $q5; numberValue = 4 }
) }
$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $badSingle $tokS
Check '单选传 2 项 -> 400' $r.code 400
CheckMsg '单选文案' $r.body.msg '必须且只能选择 1 项'

$badOpt = @{ answers = @(
    @{ questionId = $q1; optionValues = @(999999) },
    @{ questionId = $q2; optionValues = @($o2) },
    @{ questionId = $q4; textValue = '无' },
    @{ questionId = $q5; numberValue = 4 }
) }
$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $badOpt $tokS
Check '选项不属于该题 -> 400' $r.code 400
CheckMsg '选项越界文案' $r.body.msg '不属于该题'

$badRate = @{ answers = @(
    @{ questionId = $q1; optionValues = @($o1) },
    @{ questionId = $q2; optionValues = @($o2) },
    @{ questionId = $q4; textValue = '无' },
    @{ questionId = $q5; numberValue = 9 }
) }
$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $badRate $tokS
Check '评分超范围 -> 400' $r.code 400
CheckMsg '评分文案' $r.body.msg '评分必须在 1~5 之间'

$badText = @{ answers = @(
    @{ questionId = $q1; optionValues = @($o1) },
    @{ questionId = $q2; optionValues = @($o2) },
    @{ questionId = $q3; textValue = '这是一个超过二十个字符上限的作答内容用于验证字数限制' },
    @{ questionId = $q4; textValue = '无' },
    @{ questionId = $q5; numberValue = 4 }
) }
$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $badText $tokS
Check '文本超长 -> 400' $r.code 400
CheckMsg '超长文案' $r.body.msg '字上限'

$badQ = @{ answers = @(
    @{ questionId = 999999; textValue = 'x' },
    @{ questionId = $q1; optionValues = @($o1) },
    @{ questionId = $q2; optionValues = @($o2) },
    @{ questionId = $q4; textValue = '无' },
    @{ questionId = $q5; numberValue = 4 }
) }
$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $badQ $tokS
Check '题目不属于该问卷 -> 400' $r.code 400
CheckMsg '越界题目文案' $r.body.msg '不属于该问卷'

# ------------------------------------------------------------
Section '6. 正常提交与限填一次'
$full = @{ answers = @(
    @{ questionId = $q1; optionValues = @($o1) },
    @{ questionId = $q2; optionValues = @($o2, $o2b) },
    @{ questionId = $q3; textValue = '2023' },
    @{ questionId = $q4; textValue = '课程节奏偏快，希望多些练习。' },
    @{ questionId = $q5; numberValue = 5 }
) }
$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $full $tokS
Check '提交成功' $r.code 200
CheckMsg '提交提示' $r.body.msg '提交成功'
$sheetA = [long]$r.body.data.id
Check '回执带 sheetId' ($sheetA -gt 0) $true
Check '回执 anonymous=true' $r.body.data.anonymous $true

$r = Api 'POST' "/api/hall/surveys/$sidA/submit" $full $tokS
Check '重复提交 -> 409' $r.code 409
CheckMsg '重复提交文案' $r.body.msg '不能重复填写'

$r = Api 'GET' "/api/hall/surveys/$sidA/fill-state" $null $tokS
Check '提交后 fillable=false' $r.body.data.fillable $false
Check '提交后 reason=ALREADY_SUBMITTED' $r.body.data.reason 'ALREADY_SUBMITTED'
Check '提交后 submitted=true' $r.body.data.submitted $true

$r = Api 'PUT' "/api/hall/surveys/$sidA/draft" $draft $tokS
Check '提交后存草稿 -> 409' $r.code 409
CheckMsg '提交后草稿文案' $r.body.msg '不能再保存草稿'

$r = Api 'GET' '/api/hall?size=50' $null $tokS
$mine = @(@($r.body.data.records) | Where-Object { [long]$_.id -eq $sidA })
Check '大厅变为 SUBMITTED' $mine[0].myStatus 'SUBMITTED'
CheckTrue '大厅带 submitTime' ($null -ne $mine[0].submitTime) '应有提交时间'

# ------------------------------------------------------------
Section '7. 我的填写记录（匿名不回显作答内容）'
$r = Api 'GET' '/api/hall/my-answers?size=50' $null $tokS
Check '我的记录可访问' $r.code 200
$rec = @(@($r.body.data.records) | Where-Object { [long]$_.sheetId -eq $sheetA })
Check '记录包含本次提交' $rec.Count 1
Check '匿名卷 contentVisible=false' $rec[0].contentVisible $false

$r = Api 'GET' "/api/hall/my-answers/$sheetA" $null $tokS
Check '我的回执可访问' $r.code 200
Check 'anonymous=true' $r.body.data.anonymous $true
CheckTrue '不回显 items' ($null -eq $r.body.data.items) '匿名卷不应返回作答内容'
CheckTrue '不回显 questions' ($null -eq $r.body.data.questions) '匿名卷不应返回题目'

$r = Api 'GET' '/api/hall/my-answers/999999' $null $tokS
Check '他人/不存在的回执 -> 404' $r.code 404

$r = Api 'GET' "/api/hall/my-answers/$sheetA" $null $tokT
Check '教师访问他人回执 -> 404' $r.code 404

# ------------------------------------------------------------
Section '8. 发布者视角：匿名裁剪'
$r = Api 'GET' "/api/answers?surveyId=$sidA" $null $tokT
Check '教师查答卷列表' $r.code 200
$list = @($r.body.data.records)
Check '列表 1 份' $list.Count 1
CheckTrue '匿名卷 userId 为 null' ($null -eq $list[0].userId) '匿名卷不得返回 user_id'
CheckTrue '匿名卷 fillerName 为 null' ($null -eq $list[0].fillerName) '匿名卷不得返回姓名'
CheckTrue '匿名卷 fillerUsername 为 null' ($null -eq $list[0].fillerUsername) '匿名卷不得返回学号'

$r = Api 'GET' "/api/answers/$sheetA" $null $tokT
Check '教师查答卷详情' $r.code 200
Check '详情含 5 条作答' (@($r.body.data.items).Count) 5
CheckTrue '详情 userId 为 null' ($null -eq $r.body.data.userId) '匿名卷不得返回 user_id'
$it1 = @($r.body.data.items | Where-Object { [long]$_.questionId -eq $q1 })[0]
Check '单选题选项文本正确' $it1.optionTexts[0] '非常满意'
$it2 = @($r.body.data.items | Where-Object { [long]$_.questionId -eq $q2 })[0]
Check '多选题 2 个选项' (@($it2.optionTexts).Count) 2
$it5 = @($r.body.data.items | Where-Object { [long]$_.questionId -eq $q5 })[0]
Check '评分回显为 5' ([int]$it5.numberValue) 5

$r = Api 'GET' "/api/answers?surveyId=$sidA" $null $tokS
Check '学生查答卷列表 -> 403' $r.code 403

$r = Api 'GET' "/api/answers?surveyId=$sidA" $null $tokA
Check 'admin 查答卷列表' $r.code 200
Check 'admin 看到同一份' (@($r.body.data.records).Count) 1

$r = Api 'GET' '/api/answers' $null $tokT
Check '缺少 surveyId -> 400' $r.code 400

# ------------------------------------------------------------
Section '9. 回收概览'
$r = Api 'GET' "/api/surveys/$sidA/answer-summary" $null $tokT
Check '概览可访问' $r.code 200
Check '已回收 1' ([int]$r.body.data.total) 1
Check '有效 1' ([int]$r.body.data.validCount) 1
Check '无效 0' ([int]$r.body.data.invalidCount) 0
CheckTrue '完成率为正数' ([double]$r.body.data.completionRate -gt 0) '应大于 0'
CheckTrue '返回应填人数分母' ([long]$r.body.data.expectedCount -gt 0) '应有分母'
Check '概览标记匿名' $r.body.data.anonymous $true

$r = Api 'GET' "/api/surveys/$sidA/answer-summary" $null $tokS
Check '学生看概览 -> 403' $r.code 403

$r = Api 'GET' "/api/surveys/999999/answer-summary" $null $tokT
Check '不存在问卷的概览 -> 404' $r.code 404

# ------------------------------------------------------------
Section '10. 标记无效 / 恢复 / answer_count 联动'
$r = Api 'GET' "/api/surveys/$sidA" $null $tokT
Check '提交后 answerCount=1' ([int]$r.body.data.answerCount) 1

$r = Api 'PUT' "/api/answers/$sheetA/invalidate?reason=测试刷单" $null $tokT
Check '教师标记无效' $r.code 200
Check 'isValid=0' ([int]$r.body.data.isValid) 0
Check '无效原因已记录' $r.body.data.invalidReason '测试刷单'

$r = Api 'GET' "/api/surveys/$sidA" $null $tokT
Check '无效后 answerCount=0' ([int]$r.body.data.answerCount) 0

$r = Api 'GET' "/api/surveys/$sidA/answer-summary" $null $tokT
Check '概览有效数 0' ([int]$r.body.data.validCount) 0
Check '概览无效数 1' ([int]$r.body.data.invalidCount) 1
Check '概览已回收仍为 1' ([int]$r.body.data.total) 1

$r = Api 'PUT' "/api/answers/$sheetA/invalidate?reason=再来一次" $null $tokT
Check '重复标记 -> 409' $r.code 409

$r = Api 'PUT' "/api/answers/$sheetA/restore" $null $tokT
Check '恢复有效' $r.code 200
Check 'isValid=1' ([int]$r.body.data.isValid) 1

$r = Api 'GET' "/api/surveys/$sidA" $null $tokT
Check '恢复后 answerCount=1' ([int]$r.body.data.answerCount) 1

$r = Api 'PUT' "/api/answers/$sheetA/restore" $null $tokT
Check '重复恢复 -> 409' $r.code 409

$r = Api 'GET' "/api/answers?surveyId=$sidA&isValid=0" $null $tokT
Check '按无效筛选 -> 0 条' (@($r.body.data.records).Count) 0
$r = Api 'GET' "/api/answers?surveyId=$sidA&isValid=1" $null $tokT
Check '按有效筛选 -> 1 条' (@($r.body.data.records).Count) 1

# ------------------------------------------------------------
Section '11. 权限与数据范围'
$r = Api 'GET' "/api/answers?surveyId=$sidA" $null $tokT
Check '教师可看自己问卷的答卷' $r.code 200

$r = Api 'POST' '/api/surveys' @{ title = "他人问卷-$stamp"; category = 'OTHER'; anonymous = 0; limitOnce = 1 } $tokA
Check 'admin 建问卷B' $r.code 200
$sidB = [long]$r.body.data.id
$r = Api 'PUT' "/api/surveys/$sidB/questions" @{ questions = @(@{ questionType = 'TEXT'; title = '随便说'; isRequired = 0; maxLength = 100 }) } $tokA
$r = Api 'POST' "/api/surveys/$sidB/publish" $null $tokA
Check '发布问卷B（admin 的）' $r.code 200

$r = Api 'GET' "/api/answers?surveyId=$sidB" $null $tokT
Check '教师查他人问卷答卷 -> 403' $r.code 403
CheckMsg '归属失败文案' $r.body.msg '无权操作他人的问卷'

$r = Api 'GET' '/api/answers?surveyId=999999' $null $tokT
Check '不存在的问卷 -> 404' $r.code 404

$r = Api 'GET' '/api/answers' $null $null
Check '未登录查答卷 -> 401' $r.code 401

$r = Api 'DELETE' "/api/answers/$sheetA" $null $tokT
Check '教师删除答卷 -> 403' $r.code 403

$r = Api 'PUT' "/api/answers/999999/invalidate" $null $tokT
Check '标记不存在答卷 -> 404' $r.code 404

# ------------------------------------------------------------
Section '12. 实名 + 可重复填写问卷'
$r = Api 'POST' '/api/surveys' @{
    title = "实名可重复-$stamp"; category = 'ACTIVITY'; anonymous = 0; limitOnce = 0
} $tokT
Check '创建问卷C（实名+可重复）' $r.code 200
$sidC = [long]$r.body.data.id

$r = Api 'PUT' "/api/surveys/$sidC/questions" @{ questions = @(
    @{ questionType = 'TEXT'; title = '你的名字'; isRequired = 1; maxLength = 50 }
) } $tokT
$r = Api 'POST' "/api/surveys/$sidC/publish" $null $tokT
Check '发布问卷C' $r.code 200
$r = Api 'GET' "/api/surveys/$sidC" $null $tokT
$qc = [long]$r.body.data.questions[0].id

$r = Api 'POST' "/api/hall/surveys/$sidC/submit" @{ answers = @(@{ questionId = $qc; textValue = '第一次' }) } $tokS
Check '可重复问卷第 1 次提交' $r.code 200
$sheetC1 = [long]$r.body.data.id
$r = Api 'POST' "/api/hall/surveys/$sidC/submit" @{ answers = @(@{ questionId = $qc; textValue = '第二次' }) } $tokS
Check '可重复问卷第 2 次提交' $r.code 200
Check '两份是不同的答卷' ([long]$r.body.data.id -ne $sheetC1) $true

$r = Api 'GET' "/api/answers?surveyId=$sidC" $null $tokT
Check '列表 2 份' (@($r.body.data.records).Count) 2
$l0 = @($r.body.data.records)[0]
CheckTrue '实名卷返回 userId' ($null -ne $l0.userId) '实名卷应返回 user_id'
Check '实名卷返回姓名' $l0.fillerName '李同学'
Check '实名卷返回学号' $l0.fillerUsername 'student'

$r = Api 'GET' "/api/answers/$sheetC1" $null $tokT
Check '实名卷详情含作答' (@($r.body.data.items).Count) 1
Check '实名卷详情回显文本' (@($r.body.data.items)[0].textValue) '第一次'

$r = Api 'GET' "/api/hall/my-answers/$sheetC1" $null $tokS
Check '我的实名回执' $r.code 200
Check '实名卷 contentVisible 可回显' ($null -ne $r.body.data.items) $true

$r = Api 'GET' "/api/hall/my-answers/$sheetC1" $null $tokT
Check '教师看他人回执 -> 404' $r.code 404

$r = Api 'DELETE' "/api/answers/$sheetC1" $null $tokA
Check 'admin 删除答卷' $r.code 200
$r = Api 'GET' "/api/answers/$sheetC1" $null $tokA
Check '删除后详情 -> 404' $r.code 404
$r = Api 'GET' "/api/answers?surveyId=$sidC" $null $tokT
Check '删除后列表 1 份' (@($r.body.data.records).Count) 1
$r = Api 'GET' "/api/surveys/$sidC" $null $tokT
Check '删除后 answerCount=1' ([int]$r.body.data.answerCount) 1

# ------------------------------------------------------------
Section '13. 暂停 / 恢复 / 结束 / 未开始对填写的影响'
$r = Api 'POST' "/api/surveys/$sidC/pause" $null $tokT
Check '暂停问卷C' $r.code 200

$r = Api 'GET' "/api/hall/surveys/$sidC/fill-state" $null $tokS
Check '暂停后 fillable=false' $r.body.data.fillable $false
Check '暂停后 reason=PAUSED' $r.body.data.reason 'PAUSED'

$r = Api 'POST' "/api/hall/surveys/$sidC/submit" @{ answers = @(@{ questionId = $qc; textValue = '暂停中' }) } $tokS
Check '暂停后提交 -> 400' $r.code 400
CheckMsg '暂停提交文案' $r.body.msg '已暂停回收'

$r = Api 'PUT' "/api/hall/surveys/$sidC/draft" @{ answers = @(@{ questionId = $qc; textValue = '草稿' }) } $tokS
Check '暂停后存草稿 -> 400' $r.code 400

$r = Api 'GET' '/api/hall?size=50' $null $tokS
Check '大厅不显示暂停问卷' (@(@($r.body.data.records) | Where-Object { [long]$_.id -eq $sidC }).Count) 0

$r = Api 'POST' "/api/surveys/$sidC/resume" $null $tokT
Check '恢复回收' $r.code 200
$r = Api 'GET' "/api/hall/surveys/$sidC/fill-state" $null $tokS
Check '恢复后 fillable=true' $r.body.data.fillable $true

$r = Api 'POST' "/api/surveys/$sidC/close" $null $tokT
Check '结束问卷' $r.code 200
$r = Api 'GET' "/api/hall/surveys/$sidC/fill-state" $null $tokS
Check '结束后 reason=CLOSED' $r.body.data.reason 'CLOSED'
$r = Api 'POST' "/api/hall/surveys/$sidC/submit" @{ answers = @(@{ questionId = $qc; textValue = '结束后' }) } $tokS
Check '结束后提交 -> 400' $r.code 400
CheckMsg '结束提交文案' $r.body.msg '已结束'
$r = Api 'POST' "/api/surveys/$sidC/resume" $null $tokT
Check '结束后不能恢复回收 -> 400' $r.code 400

$r = Api 'POST' '/api/surveys' @{
    title = "未开始-$stamp"; category = 'OTHER'; anonymous = 1; limitOnce = 1
    startTime = '2027-01-01 00:00:00'
} $tokT
Check '创建未开始问卷D' $r.code 200
$sidD = [long]$r.body.data.id
$r = Api 'PUT' "/api/surveys/$sidD/questions" @{ questions = @(@{ questionType = 'TEXT'; title = '随便说'; isRequired = 0; maxLength = 100 }) } $tokT
$r = Api 'POST' "/api/surveys/$sidD/publish" $null $tokT
Check '发布问卷D' $r.code 200
$r = Api 'GET' "/api/hall/surveys/$sidD/fill-state" $null $tokS
Check '未开始时 reason=NOT_STARTED' $r.body.data.reason 'NOT_STARTED'
Check '未开始时 fillable=false' $r.body.data.fillable $false
$r = Api 'GET' '/api/hall?size=50' $null $tokS
Check '大厅不显示未开始问卷' (@(@($r.body.data.records) | Where-Object { [long]$_.id -eq $sidD }).Count) 0

# ------------------------------------------------------------
Section '14. 清理测试数据'
$r = Api 'DELETE' "/api/surveys/${sidA}?confirm=true" $null $tokA
Check '删除问卷A' $r.code 200
$r = Api 'DELETE' "/api/surveys/${sidB}?confirm=true" $null $tokA
Check '删除问卷B' $r.code 200
$r = Api 'DELETE' "/api/surveys/${sidC}?confirm=true" $null $tokA
Check '删除问卷C' $r.code 200
$r = Api 'DELETE' "/api/surveys/${sidD}?confirm=true" $null $tokA
$r = Api 'DELETE' "/api/surveys/${sidE}?confirm=true" $null $tokA
Check '删除问卷E' $r.code 200
Check '删除问卷D' $r.code 200

$r = Api 'GET' "/api/surveys/$sidA" $null $tokT
Check '删除后问卷A -> 404' $r.code 404

# ------------------------------------------------------------
Write-Host "`n================ 结果 ================" -ForegroundColor Cyan
Write-Host ("  PASS={0}  FAIL={1}" -f $pass, $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if ($fail -gt 0) { exit 1 } else { exit 0 }