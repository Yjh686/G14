# ============================================================
# 模板中心模块接口冒烟测试（FR-TPL）
# 用法：& "D:\G14(1)\deploy\test\smoke_template.ps1"
# 前置：后端已在 http://localhost:8080 启动
# 说明：脚本自带幂等清理，可重复执行；只依赖三个演示账号
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
        return @{ code = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
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

function CheckMsg($name, $actual, $keyword) {
    $hit = ($null -ne $actual) -and ("$actual" -like "*$keyword*")
    if ($hit) {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $name 期望含 '$keyword' 实际='$actual'" -ForegroundColor Red
    }
}

function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

# ============================================================
Section '1 登录准备'
$r = Api 'POST' '/api/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null
Check '管理员登录' $r.code 200
$adminTok = $r.body.data.token
$r = Api 'POST' '/api/auth/login' @{ username = 'teacher'; password = 'Teacher@123' } $null
Check '教师登录' $r.code 200
$teacherTok = $r.body.data.token
$r = Api 'POST' '/api/auth/login' @{ username = 'student'; password = 'Student@123' } $null
Check '学生登录' $r.code 200
$studentTok = $r.body.data.token

# 幂等清理：把上一轮可能残留的模板/问卷都删掉
$r = Api 'GET' '/api/templates?scope=mine&size=100' $null $teacherTok
if ($r.code -eq 200) {
    foreach ($t in @($r.body.data.records)) {
        $tid = [long]$t.id
        if ($t.status -eq 'PENDING' -or $t.status -eq 'APPROVED') {
            Api 'POST' "/api/templates/$tid/offline" $null $teacherTok | Out-Null
        }
        Api 'DELETE' "/api/templates/$tid" $null $teacherTok | Out-Null
    }
}
$r = Api 'GET' '/api/surveys?scope=mine&size=100' $null $teacherTok
if ($r.code -eq 200) {
    foreach ($s in @($r.body.data.records)) {
        if ("$($s.title)" -like '模板*' -or "$($s.title)" -like '*模板来源*') {
            Api 'DELETE' "/api/surveys/$([long]$s.id)?confirm=true" $null $teacherTok | Out-Null
        }
    }
}

# ============================================================
Section '2 准备来源问卷'
$r = Api 'POST' '/api/surveys' @{
    title = '模板来源问卷'; description = '用于共享模板的问卷'; category = 'TEACHING'; anonymous = 1; limitOnce = 1
} $teacherTok
Check '创建来源问卷' $r.code 200
$srcId = [long]$r.body.data.id

$r = Api 'PUT' "/api/surveys/$srcId/questions" @{
    questions = @(
        @{ questionType = 'SINGLE'; title = '你的年级？'; isRequired = 1; options = @(@{ content = '大一' }, @{ content = '大二' }, @{ content = '大三' }) },
        @{ questionType = 'MULTIPLE'; title = '喜欢哪些形式？'; isRequired = 1; options = @(@{ content = '讲授' }, @{ content = '讨论' }, @{ content = '实验' }) },
        @{ questionType = 'RATING'; title = '整体满意度'; isRequired = 1; ratingMax = 5 }
    )
} $teacherTok
Check '保存 3 道题' $r.code 200
Check '题量' $r.body.data.questionCount 3

$r = Api 'POST' "/api/surveys/$srcId/publish" $null $teacherTok
Check '发布来源问卷' $r.code 200

# 空题目问卷用于验证共享拦截
$r = Api 'POST' '/api/surveys' @{ title = '模板空问卷'; category = 'OTHER' } $teacherTok
$emptyId = [long]$r.body.data.id
$r = Api 'POST' '/api/templates' @{ sourceSurveyId = $emptyId; title = '空模板' } $teacherTok
Check '共享无题目的问卷 → 400' $r.code 400
CheckMsg '提示无题目' $r.body.msg '还没有题目'

# ============================================================
Section '3 共享为模板'
$r = Api 'POST' '/api/templates' @{
    sourceSurveyId = $srcId; title = '课程反馈模板'; description = '适合期末教学反馈'
} $teacherTok
Check '共享成功' $r.code 200
$tid = [long]$r.body.data.id
Check '状态为待审核' $r.body.data.status 'PENDING'
Check '状态标签' $r.body.data.statusLabel '待审核'
Check '题量快照' $r.body.data.questionCount 3
Check '无人评分时 ratingAvg 为 null' $r.body.data.ratingAvg $null
Check '无人评分时 ratingCount=0' $r.body.data.ratingCount 0
Check '使用次数初始 0' $r.body.data.useCount 0
Check '匿名默认继承来源问卷' $r.body.data.anonymousDefault 1

$r = Api 'POST' '/api/templates' @{ sourceSurveyId = $srcId } $teacherTok
Check '缺模板名称 → 400' $r.code 400
$r = Api 'POST' '/api/templates' @{ title = '缺来源' } $teacherTok
Check '缺来源问卷 → 400' $r.code 400

# 越权共享：admin 建卷，教师共享 → 403
$r = Api 'POST' '/api/surveys' @{ title = '管理员问卷-模板越权'; category = 'OTHER' } $adminTok
$adminSrcId = [long]$r.body.data.id
Api 'PUT' "/api/surveys/$adminSrcId/questions" @{
    questions = @(@{ questionType = 'TEXT'; title = '随便填'; isRequired = 0; maxLength = 50 })
} $adminTok | Out-Null
Api 'POST' "/api/surveys/$adminSrcId/publish" $null $adminTok | Out-Null
$r = Api 'POST' '/api/templates' @{ sourceSurveyId = $adminSrcId; title = '越权共享' } $teacherTok
Check '共享他人问卷 → 403' $r.code 403
CheckMsg '越权提示' $r.body.msg '无权操作他人的问卷'

# 学生无共享权限
$r = Api 'POST' '/api/templates' @{ sourceSurveyId = $srcId; title = '学生会共享？' } $studentTok
Check '学生共享 → 403' $r.code 403

# ============================================================
Section '4 待审核可见性'
$r = Api 'GET' '/api/templates' $null $studentTok
Check '待审核模板不在模板中心' $r.body.data.total 0
$r = Api 'GET' "/api/templates/$tid" $null $studentTok
Check '学生看未过审模板 → 404' $r.code 404
CheckMsg '不暴露存在性' $r.body.msg '模板不存在或未通过审核'
$r = Api 'GET' "/api/templates/$tid" $null $teacherTok
Check '共享者本人可见' $r.code 200
Check '本人可见状态' $r.body.data.status 'PENDING'
Check '详情含题目结构' (@($r.body.data.structure).Count) 3
Check '结构第 1 题题型' $r.body.data.structure[0].questionType 'SINGLE'
Check '结构选项为文本数组' ($r.body.data.structure[0].options -join '|') '大一|大二|大三'
Check '结构题号重编 1..n' $r.body.data.structure[2].sortNo 3

$r = Api 'GET' '/api/templates?scope=audit' $null $adminTok
Check '审核队列可访问' $r.code 200
CheckTrue '审核队列含待审模板' (@(@($r.body.data.records) | Where-Object { [long]$_.id -eq $tid }).Count -ge 1) '应含本轮模板'
$r = Api 'GET' '/api/templates?scope=audit' $null $teacherTok
Check '教师访问审核队列 → 403' $r.code 403
CheckMsg '审核权限提示' $r.body.msg '没有审核模板的权限'
$r = Api 'GET' '/api/templates?scope=mine' $null $studentTok
Check '学生访问我的模板 → 403' $r.code 403
$r = Api 'GET' '/api/templates?scope=xxx' $null $teacherTok
Check '未知视角 → 400' $r.code 400
$r = Api 'GET' '/api/templates?order=xxx' $null $teacherTok
Check '未知排序 → 400' $r.code 400
$r = Api 'GET' '/api/templates?status=XXX&scope=mine' $null $teacherTok
Check '未知状态 → 400' $r.code 400

# ============================================================
Section '5 审核'
$r = Api 'PUT' "/api/templates/$tid/audit" @{ auditStatus = 'REJECTED' } $adminTok
Check '驳回不填原因 → 400' $r.code 400
CheckMsg '驳回需原因' $r.body.msg '驳回时必须填写审核意见'
$r = Api 'PUT' "/api/templates/$tid/audit" @{ auditStatus = 'PENDING' } $adminTok
Check '审核为 PENDING → 400' $r.code 400
CheckMsg '只接受通过/驳回' $r.body.msg '审核结果只能是'
$r = Api 'PUT' "/api/templates/$tid/audit" @{ auditStatus = 'APPROVED' } $teacherTok
Check '教师审核 → 403' $r.code 403
$r = Api 'PUT' "/api/templates/$tid/audit" @{ auditStatus = 'APPROVED'; auditRemark = '内容规范' } $adminTok
Check '审核通过' $r.code 200
Check '状态变已通过' $r.body.data.status 'APPROVED'
Check '审核人姓名回填' $r.body.data.auditByName '系统管理员'
CheckTrue '审核时间已写' ($null -ne $r.body.data.auditTime) '应为非空'
$r = Api 'PUT' "/api/templates/$tid/audit" @{ auditStatus = 'APPROVED' } $adminTok
Check '重复审核 → 400' $r.code 400
CheckMsg '重复审核提示' $r.body.msg '只有待审核的模板可以审核'

# ============================================================
Section '6 模板中心浏览'
$r = Api 'GET' '/api/templates' $null $studentTok
Check '学生可见已通过模板' $r.code 200
Check '总数 1' $r.body.data.total 1
Check '标题正确' $r.body.data.records[0].title '课程反馈模板'
Check '列表页不带结构' $r.body.data.records[0].structure $null
Check '分类标签' $r.body.data.records[0].categoryLabel '教学反馈'
Check '创建人姓名' $r.body.data.records[0].createByName '张老师'
$r = Api 'GET' '/api/templates?category=TEACHING' $null $studentTok
Check '按分类筛选' $r.body.data.total 1
$r = Api 'GET' '/api/templates?category=SATISFACTION' $null $studentTok
Check '其他分类为空' $r.body.data.total 0
$r = Api 'GET' '/api/templates?keyword=课程' $null $studentTok
Check '关键词命中' $r.body.data.total 1
$r = Api 'GET' '/api/templates?keyword=不存在的词' $null $studentTok
Check '关键词不命中' $r.body.data.total 0
$r = Api 'GET' '/api/templates?status=PENDING' $null $studentTok
Check 'browse 视角强制 APPROVED（传 PENDING 无效）' $r.body.data.total 1
$r = Api 'GET' '/api/templates?order=latest' $null $studentTok
Check '按最新排序可用' $r.code 200
$r = Api 'GET' '/api/templates?order=used' $null $studentTok
Check '按使用次数排序可用' $r.code 200
$r = Api 'GET' "/api/templates/$tid" $null $studentTok
Check '学生看已通过详情' $r.code 200
Check '详情含结构' (@($r.body.data.structure).Count) 3

# ============================================================
Section '7 评分'
$r = Api 'PUT' "/api/templates/$tid/rating?score=5" $null $studentTok
Check '学生评分 5' $r.code 200
Check '平均分 5.0' $r.body.data.ratingAvg 5.0
Check '评分人数 1' $r.body.data.ratingCount 1
Check '我的评分回显' $r.body.data.myScore 5
$r = Api 'PUT' "/api/templates/$tid/rating?score=3" $null $studentTok
Check '改分为 3' $r.code 200
Check '平均分变 3.0' $r.body.data.ratingAvg 3.0
Check '评分人数仍为 1（一人一票）' $r.body.data.ratingCount 1
$r = Api 'PUT' "/api/templates/$tid/rating?score=3" $null $studentTok
Check '重复提交同分幂等' $r.code 200
Check '人数不变' $r.body.data.ratingCount 1
$r = Api 'PUT' "/api/templates/$tid/rating?score=0" $null $studentTok
Check '评分 0 → 400' $r.code 400
CheckMsg '范围提示' $r.body.msg '评分必须在 1~5'
$r = Api 'PUT' "/api/templates/$tid/rating?score=6" $null $studentTok
Check '评分 6 → 400' $r.code 400
$r = Api 'PUT' "/api/templates/$tid/rating?score=4" $null $teacherTok
Check '教师无评分权限 → 403' $r.code 403
# 权限判定只认权限码不认角色：ADMIN 在初始化时被授予全部 50 个权限码，
# 所以它也能评分。这条断言锁住「实现以权限码为准」这个事实。
$r = Api 'PUT' "/api/templates/$tid/rating?score=4" $null $adminTok
Check '管理员持有 template:rate，可评分' $r.code 200
$r = Api 'PUT' "/api/templates/$tid/rating?score=3" $null $adminTok
Check '管理员改回 3 分（不影响学生那票）' $r.body.data.ratingCount 2
$r = Api 'GET' "/api/templates/$tid" $null $studentTok
Check '详情回显我的评分' $r.body.data.myScore 3
$r = Api 'GET' '/api/templates' $null $studentTok
Check '列表页回显我的评分' $r.body.data.records[0].myScore 3

# ============================================================
Section '8 使用模板'
$r = Api 'POST' "/api/templates/$tid/use" $null $teacherTok
Check '使用模板建卷' $r.code 200
$newId = [long]$r.body.data.id
Check '新问卷为草稿' $r.body.data.status 'DRAFT'
Check '新问卷题数一致' (@($r.body.data.questions).Count) 3
Check '第 1 题题型保留' $r.body.data.questions[0].questionType 'SINGLE'
Check '第 1 题选项保留' ((@($r.body.data.questions[0].options) | ForEach-Object { $_.content }) -join '|') '大一|大二|大三'
Check '第 2 题多选选项保留' (@($r.body.data.questions[1].options).Count) 3
Check '第 3 题评分上限保留' $r.body.data.questions[2].ratingMax 5
Check '新问卷匿名继承模板默认' $r.body.data.anonymous 1
Check '新问卷限填继承模板默认' $r.body.data.limitOnce 1
CheckTrue '新问卷无分享码' ([string]::IsNullOrEmpty("$($r.body.data.shareCode)")) '草稿不应有 shareCode'
$r = Api 'GET' "/api/templates/$tid" $null $teacherTok
Check '使用次数 +1' $r.body.data.useCount 1
$r = Api 'POST' "/api/templates/$tid/use" $null $studentTok
Check '学生使用模板 → 403' $r.code 403

# 使用后的问卷可自由改题（与模板解耦）
$r = Api 'PUT' "/api/surveys/$newId/questions" @{
    questions = @(@{ questionType = 'TEXT'; title = '我加了一道题'; isRequired = 1; maxLength = 100 })
} $teacherTok
Check '新问卷可自由改题（模板是快照）' $r.code 200
$r = Api 'GET' "/api/templates/$tid" $null $teacherTok
Check '改新问卷不影响模板题数' $r.body.data.questionCount 3
Check '模板结构未被改动' (@($r.body.data.structure).Count) 3
Api 'DELETE' "/api/surveys/${newId}?confirm=true" $null $teacherTok | Out-Null

# ============================================================
Section '9 下架与再使用'
$r = Api 'POST' "/api/templates/$tid/offline" $null $teacherTok
Check '下架成功' $r.code 200
Check '状态为已下架' $r.body.data.status 'OFFLINE'
$r = Api 'GET' '/api/templates' $null $studentTok
Check '下架后模板中心不可见' $r.body.data.total 0
$r = Api 'POST' "/api/templates/$tid/use" $null $teacherTok
Check '下架模板不可使用' $r.code 400
CheckMsg '下架不可使用提示' $r.body.msg '未通过审核'
$r = Api 'PUT' "/api/templates/$tid/rating?score=4" $null $studentTok
Check '下架模板不可评分' $r.code 400
$r = Api 'POST' "/api/templates/$tid/offline" $null $teacherTok
Check '重复下架 → 409' $r.code 409
CheckMsg '重复下架提示' $r.body.msg '该模板已下架'

# ============================================================
Section '10 驳回与重新提交'
$r = Api 'POST' '/api/templates' @{ sourceSurveyId = $srcId; title = '待驳回模板'; description = '描述太简略' } $teacherTok
Check '共享第二个模板' $r.code 200
$tid2 = [long]$r.body.data.id
$r = Api 'PUT' "/api/templates/$tid2/audit" @{ auditStatus = 'REJECTED'; auditRemark = '描述太简略，请补充适用场景' } $adminTok
Check '驳回成功' $r.code 200
Check '状态已驳回' $r.body.data.status 'REJECTED'
Check '驳回原因入库' $r.body.data.auditRemark '描述太简略，请补充适用场景'
$r = Api 'GET' "/api/templates/$tid2" $null $teacherTok
Check '共享者能看到驳回原因' $r.body.data.auditRemark '描述太简略，请补充适用场景'
$r = Api 'GET' '/api/templates' $null $studentTok
Check '被驳回的模板不对外可见' $r.body.data.total 0
$r = Api 'POST' "/api/templates/$tid2/resubmit" $null $teacherTok
Check '重新提交成功' $r.code 200
Check '状态回到待审核' $r.body.data.status 'PENDING'
Check '重新提交清空驳回原因' $r.body.data.auditRemark $null
Check '重新提交清空审核人' $r.body.data.auditByName $null
$r = Api 'POST' "/api/templates/$tid2/resubmit" $null $teacherTok
Check '非驳回状态不能重复提交' $r.code 400
CheckMsg '重复提交提示' $r.body.msg '只有被驳回的模板可以重新提交'
$r = Api 'DELETE' "/api/templates/$tid2" $null $teacherTok
Check '待审核模板不可删除' $r.code 400
CheckMsg '待审不可删提示' $r.body.msg '只能删除已驳回或已下架的模板'

# ============================================================
Section '11 归属与数据范围'
$r = Api 'POST' "/api/templates/$tid2/offline" $null $adminTok
Check '管理员下架他人模板 → 403（模板无 *:all）' $r.code 403
CheckMsg '归属提示' $r.body.msg '无权操作他人的模板'
$r = Api 'DELETE' "/api/templates/$tid2" $null $adminTok
Check '管理员删除他人模板 → 403' $r.code 403
$r = Api 'POST' "/api/templates/$tid2/resubmit" $null $adminTok
Check '管理员重新提交他人模板 → 403' $r.code 403
$r = Api 'GET' '/api/templates?scope=mine' $null $teacherTok
Check '我的模板只含自己共享的' $r.body.data.total 2
$r = Api 'GET' '/api/templates?scope=mine&status=PENDING' $null $teacherTok
Check '我的模板可按状态筛选' $r.body.data.total 1
$r = Api 'GET' '/api/templates/999999' $null $teacherTok
Check '不存在的模板 → 404' $r.code 404
CheckMsg '不存在提示' $r.body.msg '模板不存在或已被删除'

# ============================================================
Section '12 已通过模板的删除约束'
$r = Api 'POST' "/api/templates/$tid2/offline" $null $teacherTok
Check '待审核模板可下架' $r.code 200
$r = Api 'DELETE' "/api/templates/$tid2" $null $teacherTok
Check '已下架模板可删除' $r.code 200
$r = Api 'GET' "/api/templates/$tid2" $null $teacherTok
Check '已删除模板 → 404' $r.code 404

# ============================================================
Section '13 清理'
$r = Api 'DELETE' "/api/templates/$tid" $null $teacherTok
Check '已下架模板可删除' $r.code 200
$r = Api 'DELETE' "/api/surveys/${srcId}?confirm=true" $null $teacherTok
Check '删除来源问卷' $r.code 200
$r = Api 'DELETE' "/api/surveys/${emptyId}?confirm=true" $null $teacherTok
Check '删除空问卷' $r.code 200
$r = Api 'DELETE' "/api/surveys/${adminSrcId}?confirm=true" $null $adminTok
Check '删除管理员问卷' $r.code 200
$r = Api 'GET' '/api/templates' $null $studentTok
Check '清理后模板中心为空' $r.body.data.total 0

Write-Host "`n----------------------------------------" -ForegroundColor Cyan
Write-Host ("结果：通过 {0} 项，失败 {1} 项" -f $pass, $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if ($fail -gt 0) { exit 1 }
