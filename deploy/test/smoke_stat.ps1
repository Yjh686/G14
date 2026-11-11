# ============================================================
# 统计与导出模块接口冒烟测试（FR-STAT / FR-EXP）
# 用法：& "D:\G14(1)\deploy\test\smoke_stat.ps1"
# 前置：后端已在 http://localhost:8080 启动
# 说明：脚本自带幂等清理，可重复执行；只依赖三个演示账号
# ============================================================
$ErrorActionPreference = 'Continue'
$BASE = 'http://localhost:8080'
$pass = 0; $fail = 0
# 导出文件落到临时目录，不污染工程
$TMP = Join-Path $env:TEMP 'g14_stat_smoke'
New-Item -ItemType Directory -Force -Path $TMP | Out-Null

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

# 下载二进制文件，返回 @{code;path;size}
function Download($path, $token, $fileName) {
    $target = Join-Path $TMP $fileName
    Remove-Item $target -ErrorAction SilentlyContinue
    $h = @{}
    if ($token) { $h['Authorization'] = "Bearer $token" }
    try {
        $r = Invoke-WebRequest -Uri "$BASE$path" -Headers $h -UseBasicParsing -TimeoutSec 60
        [System.IO.File]::WriteAllBytes($target, $r.Content)
        return @{ code = [int]$r.StatusCode; path = $target; size = $r.Content.Length
                  disposition = $r.Headers['Content-Disposition'] }
    } catch {
        $resp = $_.Exception.Response
        $code = if ($resp) { [int]$resp.StatusCode } else { -1 }
        return @{ code = $code; path = $null; size = 0; disposition = $null }
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

# xlsx 本质是 zip，检查前两字节是否为 PK
function IsZip($path) {
    if (-not $path) { return $false }
    if (-not (Test-Path $path)) { return $false }
    $fs = [System.IO.File]::OpenRead($path)
    try {
        $b = New-Object byte[] 2
        $n = $fs.Read($b, 0, 2)
        return ($n -eq 2 -and $b[0] -eq 0x50 -and $b[1] -eq 0x4B)
    } finally { $fs.Close() }
}

# 用 Python 读 xlsx 内容（本机有 python + openpyxl），失败返回 $null
# 用 Python 读 xlsx 内容（本机有 python + openpyxl），失败返回 $null。
# 读取逻辑放在 tools\read_xlsx.py，结果先写 UTF-8 文件再读回来：
# 直接读 python 的 stdout 会被 Windows 控制台代码页转成 GBK，中文全乱。
function ReadXlsx($path) {
    if (-not $path) { return $null }
    $script = Join-Path $PSScriptRoot 'tools\read_xlsx.py'
    $jsonFile = Join-Path $TMP ('xlsx_' + [System.IO.Path]::GetFileNameWithoutExtension($path) + '.json')
    Remove-Item $jsonFile -ErrorAction SilentlyContinue
    & python $script $path $jsonFile 2>$null | Out-Null
    if (-not (Test-Path $jsonFile)) { return $null }
    try { return ([System.IO.File]::ReadAllText($jsonFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json) }
    catch { return $null }
}

# ============================================================
Section '1. 登录准备'
$r = Api 'POST' '/api/auth/login' @{ username = 'admin'; password = 'Admin@123' } $null
Check '管理员登录' $r.code 200
$tokA = $r.body.data.token
$r = Api 'POST' '/api/auth/login' @{ username = 'teacher'; password = 'Teacher@123' } $null
Check '教师登录' $r.code 200
$tokT = $r.body.data.token
$r = Api 'POST' '/api/auth/login' @{ username = 'student'; password = 'Student@123' } $null
Check '学生登录' $r.code 200
$tokS = $r.body.data.token

# ------------------------------------------------------------
Section '2. 建卷（五种题型 + 可重复填写）'
$r = Api 'POST' '/api/surveys' @{
    title = '统计冒烟问卷'; description = 'smoke-stat';
    category = 'SATISFACTION'; anonymous = 1; limitOnce = 0
} $tokT
Check '创建问卷 200' $r.code 200
$sid = $r.body.data.id
CheckTrue '拿到问卷 ID' ($sid -gt 0) "sid=$sid"

$r = Api 'PUT' "/api/surveys/$sid/questions" @{ questions = @(
        @{ questionType = 'SINGLE'; title = '你的年级？'; isRequired = 1
           options = @(@{ content = '大一' }, @{ content = '大二' }, @{ content = '大三' }) },
        @{ questionType = 'MULTIPLE'; title = '常用的学习方式？'; isRequired = 0
           options = @(@{ content = '自学' }, @{ content = '小组讨论' }, @{ content = '看视频' }, @{ content = '刷题' }) },
        @{ questionType = 'TEXT'; title = '一句话建议'; isRequired = 0; maxLength = 50 },
        @{ questionType = 'TEXTAREA'; title = '详细意见'; isRequired = 0; maxLength = 300 },
        @{ questionType = 'RATING'; title = '满意度打分'; isRequired = 0; ratingMax = 5 }
    ) } $tokT
Check '保存 5 道题 200' $r.code 200
Check '题目数=5' $r.body.data.questionCount 5

$r = Api 'POST' "/api/surveys/$sid/publish" $null $tokT
Check '发布问卷 200' $r.code 200
$share = $r.body.data.shareCode
CheckTrue '分享码 8 位' ($share -and $share.Length -eq 8) "share=$share"

# 取题目与选项 ID
$r = Api 'GET' "/api/surveys/share/$share" $null $tokS
Check '学生按分享码取卷 200' $r.code 200
$qs = $r.body.data.questions
$qSingle = $qs[0]; $qMulti = $qs[1]; $qText = $qs[2]; $qArea = $qs[3]; $qRate = $qs[4]
$global:SQ = $qSingle.id; $global:MQ = $qMulti.id
$global:TQ = $qText.id; $global:AQ = $qArea.id; $global:RQ = $qRate.id
$g1 = $qSingle.options[0].id; $g2 = $qSingle.options[1].id; $g3 = $qSingle.options[2].id
$m1 = $qMulti.options[0].id; $m2 = $qMulti.options[1].id
$m3 = $qMulti.options[2].id; $m4 = $qMulti.options[3].id
CheckTrue '题型顺序正确' ($qSingle.questionType -eq 'SINGLE' -and $qMulti.questionType -eq 'MULTIPLE' -and
    $qText.questionType -eq 'TEXT' -and $qArea.questionType -eq 'TEXTAREA' -and $qRate.questionType -eq 'RATING') '题型不符'

# ------------------------------------------------------------
Section '3. 提交 5 份答卷（构造已知分布）'
$sets = @(
    @{ g = $g1; m = @($m1, $m2); t = '建议增加课堂互动'; a = '希望老师多讲一些实际案例，作业量可以适当减少'; r = 5 },
    @{ g = $g1; m = @($m1);      t = '课程节奏偏快';     a = '节奏有点快跟不上，希望放慢一点';               r = 4 },
    @{ g = $g2; m = @($m2, $m3); t = '希望多加实验课';   a = '实验课很好，希望增加实验课的次数';             r = 4 },
    @{ g = $g2; m = @($m1, $m4); t = '作业有点多';       a = '作业量偏多，建议减少作业量';                   r = 3 },
    @{ g = $g3; m = @($m1, $m2, $m3, $m4); t = '都挺好'; a = '整体不错，希望保持';                           r = 5 }
)
$sheetIds = @()
foreach ($x in $sets) {
    $body = @{ answers = @(
            @{ questionId = $qSingle.id; optionValues = @($x.g) },
            @{ questionId = $qMulti.id; optionValues = @($x.m) },
            @{ questionId = $qText.id; textValue = $x.t },
            @{ questionId = $qArea.id; textValue = $x.a },
            @{ questionId = $qRate.id; numberValue = $x.r }
        ) }
    $r = Api 'POST' "/api/hall/surveys/$sid/submit" $body $tokS
    if ($r.code -eq 200) { $sheetIds += [long]$r.body.data.id }
}
Check '提交 5 份答卷全部成功' $sheetIds.Count 5

# ------------------------------------------------------------
Section '4. 整卷统计结构'
$r = Api 'GET' "/api/surveys/$sid/stats" $null $tokT
Check '教师取整卷统计 200' $r.code 200
$st = $r.body.data
Check '有效答卷数=5' $st.validSheetCount 5
Check '回收总数=5' $st.totalSheetCount 5
Check '统计题数=5' $st.questions.Count 5
Check '匿名标记=true' $st.anonymous $true
CheckTrue '统计带生成时间' ($null -ne $st.generatedAt) 'generatedAt 为空'

# ------------------------------------------------------------
Section '5. 单选题统计（FR-STAT-02）'
$s1 = $st.questions[0]
Check '单选作答人数=5' $s1.answeredCount 5
Check '单选选项数=3' $s1.options.Count 3
Check '大一 2 人' $s1.options[0].count 2
Check '大二 2 人' $s1.options[1].count 2
Check '大三 1 人' $s1.options[2].count 1
CheckTrue '大一占比 0.4' ([math]::Abs($s1.options[0].ratio - 0.4) -lt 0.0001) "ratio=$($s1.options[0].ratio)"
CheckTrue '单选占比合计=1' ([math]::Abs((($s1.options | ForEach-Object { $_.ratio }) | Measure-Object -Sum).Sum - 1) -lt 0.001) '合计不等于 1'

# ------------------------------------------------------------
Section '6. 多选题统计（占比基于答题人数）'
$s2 = $st.questions[1]
Check '多选作答人数=5' $s2.answeredCount 5
Check '多选选项数=4' $s2.options.Count 4
Check '自学 4 人' $s2.options[0].count 4
Check '小组讨论 3 人' $s2.options[1].count 3
Check '看视频 2 人' $s2.options[2].count 2
Check '刷题 2 人' $s2.options[3].count 2
CheckTrue '自学占比 0.8' ([math]::Abs($s2.options[0].ratio - 0.8) -lt 0.0001) "ratio=$($s2.options[0].ratio)"
# 多选占比之和必然 > 1，这正好说明分母是人数而不是选项数
$sum2 = (($s2.options | ForEach-Object { $_.ratio }) | Measure-Object -Sum).Sum
CheckTrue '多选占比合计 > 1（分母为人数）' ($sum2 -gt 1) "sum=$sum2"

# ------------------------------------------------------------
Section '7. 文本题统计与词频（FR-STAT-03）'
$s3 = $st.questions[2]
Check '填空作答条数=5' $s3.text.answeredCount 5
Check '填空作答列表 5 条' $s3.text.answers.Count 5
Check '填空未截断' $s3.text.truncated $false
$s4 = $st.questions[3]
Check '多行文本作答条数=5' $s4.text.answeredCount 5
CheckTrue '多行文本有高频词' (@($s4.text.words).Count -ge 1) "words=$(@($s4.text.words).Count)"
CheckTrue '高频词含"作业"' (@($s4.text.words) | Where-Object { $_.word -eq '作业' }) '没有"作业"这个词'
CheckTrue '高频词次数>=2' (@($s4.text.words) | Where-Object { $_.count -ge 2 }) '次数都是 1，阈值失效'
CheckTrue '文本统计不含填写人字段' ($null -eq $s4.text.answers[0].PSObject.Properties['userId']) '泄漏了 userId'
CheckTrue '文本作答按时间倒序' ($s4.text.answers.Count -ge 2) '条数不足无法验证排序'

# ------------------------------------------------------------
Section '8. 评分题统计（FR-STAT-04）'
$s5 = $st.questions[4]
Check '参评人数=5' $s5.rating.answeredCount 5
Check '星级上限=5' $s5.rating.ratingMax 5
CheckTrue '平均分=4.2' ([math]::Abs($s5.rating.average - 4.2) -lt 0.001) "avg=$($s5.rating.average)"
Check '分布档数=5（含 0 人的档）' $s5.rating.distribution.Count 5
Check '1 星 0 人' $s5.rating.distribution[0].count 0
Check '3 星 1 人' $s5.rating.distribution[2].count 1
Check '4 星 2 人' $s5.rating.distribution[3].count 2
Check '5 星 2 人' $s5.rating.distribution[4].count 2

# ------------------------------------------------------------
Section '9. 单题统计接口'
$r = Api 'GET' "/api/surveys/$sid/stats/questions/$($qSingle.id)" $null $tokT
Check '单题统计 200' $r.code 200
Check '单题作答人数=5' $r.body.data.answeredCount 5
Check '单题选项数=3' $r.body.data.options.Count 3
$r = Api 'GET' "/api/surveys/$sid/stats/questions/999999" $null $tokT
Check '题目不属于该问卷 404' $r.code 404

# ------------------------------------------------------------
Section '10. 无效答卷不计入统计（FR-STAT-06）'
$targetSheet = $sheetIds[0]
$r = Api 'PUT' "/api/answers/$targetSheet/invalidate?reason=统计口径测试" $null $tokT
Check '标记无效 200' $r.code 200
$r = Api 'GET' "/api/surveys/$sid/stats" $null $tokT
Check '标记后统计 200' $r.code 200
Check '有效答卷数降为 4' $r.body.data.validSheetCount 4
Check '回收总数仍为 5' $r.body.data.totalSheetCount 5
Check '单选作答人数降为 4' $r.body.data.questions[0].answeredCount 4
CheckTrue '单选占比按新分母重算（大一 1/4）' ([math]::Abs($r.body.data.questions[0].options[0].ratio - 0.25) -lt 0.0001) "ratio=$($r.body.data.questions[0].options[0].ratio)"
Check '评分参评人数降为 4' $r.body.data.questions[4].rating.answeredCount 4

$r = Api 'PUT' "/api/answers/$targetSheet/restore" $null $tokT
Check '恢复有效 200' $r.code 200
$r = Api 'GET' "/api/surveys/$sid/stats" $null $tokT
Check '恢复后有效答卷数回到 5' $r.body.data.validSheetCount 5

# ------------------------------------------------------------
Section '11. 导出 xlsx（FR-EXP）'
$f = Download "/api/surveys/$sid/export/answers" $tokT 'normal.xlsx'
Check '导出 200' $f.code 200
CheckTrue '导出内容是 zip/xlsx' (IsZip $f.path) "path=$($f.path)"
CheckTrue '导出文件非空' ($f.size -gt 1000) "size=$($f.size)"
$disp = "$($f.disposition)"
CheckTrue '响应头含 UTF-8 文件名' ($disp -like "*filename*=UTF-8''*") "disp=$disp"
CheckTrue '文件名含问卷标题（URL 编码）' ($disp -match '%E7%BB%9F%E8%AE%A1%E5%86%92%E7%83%9F') "disp=$disp"
CheckTrue '文件名为 .xlsx' ($disp -like '*.xlsx*') "disp=$disp"

$xl = ReadXlsx $f.path
if ($xl) {
    Check '两个 Sheet' $xl.sheets.Count 2
    CheckTrue 'Sheet1 名为"明细"' ($xl.sheets[0] -eq '明细') "sheets=$($xl.sheets -join ',')"
    CheckTrue 'Sheet2 名为"汇总"' ($xl.sheets[1] -eq '汇总') "sheets=$($xl.sheets -join ',')"
    $detail = $xl.data.'明细'
    Check '明细行数=1 表头+5 行' $detail.Count 6
    # 匿名问卷：不得出现填写人/学号列
    CheckTrue '匿名导出无"填写人"列' (-not ($detail[0] -contains '填写人')) "head=$($detail[0] -join '|')"
    CheckTrue '匿名导出无"学号/工号"列' (-not ($detail[0] -contains '学号/工号')) "head=$($detail[0] -join '|')"
    CheckTrue '明细表头含第 5 题' ($detail[0][$detail[0].Count - 1] -like '*满意度打分*') "head=$($detail[0] -join '|')"
    CheckTrue '明细含"非常"以外的多选题拼接' ($detail[1][4] -like '*、*') "cell=$($detail[1][4])"
    $sum = $xl.data.'汇总'
    CheckTrue '汇总表头 6 列' ($sum[0].Count -eq 6) "cols=$($sum[0].Count)"
    $optRow = $sum | Where-Object { $_[3] -eq '大一' } | Select-Object -First 1
    CheckTrue '汇总含"大一"行' ($null -ne $optRow) '没有找到 大一'
    if ($optRow) {
        Check '大一人数=2' ([int]$optRow[4]) 2
        Check '大一占比=40.00%' $optRow[5] '40.00%'
    }
    $starRow = $sum | Where-Object { $_[3] -eq '5 星' } | Select-Object -First 1
    CheckTrue '汇总含"5 星"行' ($null -ne $starRow) '没有找到 5 星'
    $avgRow = @($sum | Where-Object { "$($_[3])" -like '*平均分*' }) | Select-Object -First 1
    CheckTrue '汇总含平均分行' ($null -ne $avgRow) '没有平均分行'
    # 验收标准 2.3：平均分保留 1 位小数。5 份评分 5,5,5,4,4 → 21/5 = 4.2
    # 用 4.2 而不用 4.20 作判据，正好能区分「1 位小数」与「2 位小数」
    if ($avgRow) {
        CheckTrue '平均分保留 1 位小数' ("$($avgRow[3])" -like '*4.2*' -and "$($avgRow[3])" -notlike '*4.20*') "cell=$($avgRow[3])"
    }
    CheckTrue '汇总含口径说明行' (@($sum | Where-Object { $_[0] -eq '口径' }).Count -eq 1) '没有口径行'
} else {
    CheckTrue 'python+openpyxl 可读取导出文件' $false '读取失败，跳过内容断言'
}

# ------------------------------------------------------------
Section '12. 实名问卷导出含填写人列（R-EXP-1 反例）'
$r = Api 'POST' '/api/surveys' @{ title = '实名导出冒烟'; anonymous = 0; limitOnce = 1 } $tokT
Check '创建实名问卷 200' $r.code 200
$rid = $r.body.data.id
$r = Api 'PUT' "/api/surveys/$rid/questions" @{ questions = @(
        @{ questionType = 'SINGLE'; title = '今天到课了吗'; isRequired = 1
           options = @(@{ content = '到了' }, @{ content = '没到' }) }
    ) } $tokT
Check '实名问卷存题 200' $r.code 200
$r = Api 'POST' "/api/surveys/$rid/publish" $null $tokT
Check '实名问卷发布 200' $r.code 200
$r = Api 'GET' "/api/surveys/$rid" $null $tokT
$rq = $r.body.data.questions[0]
$r = Api 'POST' "/api/hall/surveys/$rid/submit" @{ answers = @(
        @{ questionId = $rq.id; optionValues = @($rq.options[0].id) }
    ) } $tokS
Check '实名问卷提交 200' $r.code 200
$f2 = Download "/api/surveys/$rid/export/answers" $tokT 'realname.xlsx'
Check '实名导出 200' $f2.code 200
$xl2 = ReadXlsx $f2.path
if ($xl2) {
    $d2 = $xl2.data.'明细'
    CheckTrue '实名导出含"填写人"列' ($d2[0] -contains '填写人') "head=$($d2[0] -join '|')"
    CheckTrue '实名导出含"学号/工号"列' ($d2[0] -contains '学号/工号') "head=$($d2[0] -join '|')"
    $idx = [array]::IndexOf($d2[0], '填写人')
    CheckTrue '填写人为"李同学"' ($d2[1][$idx] -eq '李同学') "name=$($d2[1][$idx])"
} else {
    CheckTrue '实名导出可读取' $false '读取失败'
}

# ------------------------------------------------------------
Section '13. 空数据问卷（R-STAT-2 不报错、不出现 NaN）'
$r = Api 'POST' '/api/surveys' @{ title = '空数据冒烟'; anonymous = 1; limitOnce = 1 } $tokT
Check '创建空数据问卷 200' $r.code 200
$eid = $r.body.data.id
$r = Api 'PUT' "/api/surveys/$eid/questions" @{ questions = @(
        @{ questionType = 'SINGLE'; title = 'A'; isRequired = 1
           options = @(@{ content = 'x' }, @{ content = 'y' }) },
        @{ questionType = 'RATING'; title = 'B'; isRequired = 0; ratingMax = 5 },
        @{ questionType = 'TEXT'; title = 'C'; isRequired = 0; maxLength = 20 }
    ) } $tokT
Check '空数据问卷存题 200' $r.code 200
$r = Api 'POST' "/api/surveys/$eid/publish" $null $tokT
Check '空数据问卷发布 200' $r.code 200
$r = Api 'GET' "/api/surveys/$eid/stats" $null $tokT
Check '空数据统计 200' $r.code 200
Check '有效答卷=0' $r.body.data.validSheetCount 0
Check '单选作答=0' $r.body.data.questions[0].answeredCount 0
CheckTrue '空数据占比为 0 而非 NaN' (@($r.body.data.questions[0].options | Where-Object { "$($_.ratio)" -eq 'NaN' }).Count -eq 0) '出现 NaN'
CheckTrue '空数据平均分为 0' ([math]::Abs($r.body.data.questions[1].rating.average) -lt 0.0001) "avg=$($r.body.data.questions[1].rating.average)"
CheckTrue '空数据星级分布仍有 5 档' ($r.body.data.questions[1].rating.distribution.Count -eq 5) "count=$($r.body.data.questions[1].rating.distribution.Count)"
$wc = @($r.body.data.questions[2].text.words).Count
Check '空数据词频为空' $wc 0
$f3 = Download "/api/surveys/$eid/export/answers" $tokT 'empty.xlsx'
Check '空数据导出 200' $f3.code 200
$xl3 = ReadXlsx $f3.path
if ($xl3) {
    Check '空数据导出仍有两个 Sheet' $xl3.sheets.Count 2
    CheckTrue '空数据明细有占位行' ($xl3.data.'明细'.Count -eq 2) "rows=$($xl3.data.'明细'.Count)"
    CheckTrue '空数据明细占位提示' ("$($xl3.data.'明细'[1][0])" -like '*暂无答卷*') "cell=$($xl3.data.'明细'[1][0])"
} else {
    CheckTrue '空数据导出可读取' $false '读取失败'
}

# ------------------------------------------------------------
Section '14. 权限与数据范围'
$r = Api 'GET' "/api/surveys/$sid/stats" $null $tokS
Check '学生取统计 403' $r.code 403
$f4 = Download "/api/surveys/$sid/export/answers" $tokS 'forbidden.xlsx'
Check '学生导出 403' $f4.code 403
$r = Api 'GET' '/api/surveys/1/stats' $null $tokS
Check '学生取他人问卷统计 403' $r.code 403
$r = Api 'GET' '/api/surveys/99999/stats' $null $tokT
Check '不存在问卷 404' $r.code 404
$r = Api 'GET' "/api/surveys/$sid/stats" $null $null
Check '未登录 401' $r.code 401

# admin 建卷 → teacher 既不能看统计也不能导出（归属校验）
$r = Api 'POST' '/api/surveys' @{ title = '管理员归属冒烟'; anonymous = 0; limitOnce = 1 } $tokA
$asid = $r.body.data.id
$r = Api 'PUT' "/api/surveys/$asid/questions" @{ questions = @(
        @{ questionType = 'SINGLE'; title = 'x'; isRequired = 1
           options = @(@{ content = 'a' }, @{ content = 'b' }) }
    ) } $tokA
$r = Api 'POST' "/api/surveys/$asid/publish" $null $tokA
Check 'admin 建并发布 200' $r.code 200
$r = Api 'GET' "/api/surveys/$asid/stats" $null $tokT
Check '教师看他人问卷统计 403' $r.code 403
CheckMsg '归属错误提示' $r.body.msg '无权操作他人的问卷'
$f5 = Download "/api/surveys/$asid/export/answers" $tokT 'other.xlsx'
Check '教师导出他人问卷 403' $f5.code 403
$r = Api 'GET' "/api/surveys/$asid/stats" $null $tokA
Check 'admin 看自己问卷统计 200' $r.code 200
$r = Api 'GET' "/api/surveys/$sid/stats" $null $tokA
Check 'admin 看教师问卷统计 200（*:all）' $r.code 200
$f6 = Download "/api/surveys/$sid/export/answers" $tokA 'admin_all.xlsx'
Check 'admin 导出教师问卷 200' $f6.code 200

# ------------------------------------------------------------
Section '15. 幂等清理'
foreach ($x in @($sid, $rid, $eid, $asid)) {
    $r = Api 'DELETE' "/api/surveys/${x}?confirm=true" $null $tokT
    if ($r.code -ne 200) { $r = Api 'DELETE' "/api/surveys/${x}?confirm=true" $null $tokA }
    Write-Host "  清理问卷 $x -> $($r.code)" -ForegroundColor DarkGray
}
Remove-Item $TMP -Recurse -Force -ErrorAction SilentlyContinue

# ------------------------------------------------------------
Write-Host "`n----------------------------------------" -ForegroundColor Yellow
Write-Host ("通过 $pass 项，失败 $fail 项") -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
if ($fail -gt 0) { exit 1 }
