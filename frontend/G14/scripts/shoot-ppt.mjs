/**
 * 为答辩 PPT 采集真实界面截图。
 * 造一份「有 8 份答卷」的演示问卷 + 一份已审核通过的模板，逐个页面截图到 docs/答辩素材/。
 * 跑完把演示数据留在库里（PPT 截图用），需要清理时执行 deploy/sql/90_cleanup_test_data.sql。
 *
 * 用法（dev server 必须已在 5173 运行）：
 *   node scripts/shoot-ppt.mjs
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.E2E_BASE ?? 'http://localhost:5173'
const CHROME = process.env.E2E_CHROME ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const OUT = path.resolve('..', '..', 'docs', '答辩素材')

const stamp = Date.now().toString().slice(-6)
const SURVEY_TITLE = `课程满意度调查（演示 ${stamp}）`

fs.mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
const page = await ctx.newPage()
const errors = []
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push(String(e)))

async function login(username, password) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button:has-text("登 录")')
  await page.waitForFunction(() => !!localStorage.getItem('g14_token'), { timeout: 15000 })
  await page.waitForTimeout(1200)
}

async function api(p, method = 'GET', body) {
  return page.evaluate(async ({ p, method, body }) => {
    const res = await fetch('/api' + p, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + (localStorage.getItem('g14_token') ?? ''),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await res.text()
    let data = null
    try { data = JSON.parse(text) } catch { data = text }
    return { status: res.status, body: data }
  }, { p, method, body })
}

async function shot(name) {
  await page.waitForTimeout(700)
  await page.screenshot({ path: path.join(OUT, name), fullPage: false })
  console.log('  截图', name)
}

try {
  // ---------- 1. 教师建卷 ----------
  console.log('教师登录并建卷 ...')
  await login('teacher', 'Teacher@123')

  const created = await api('/surveys', 'POST', {
    title: SURVEY_TITLE,
    description: '本学期课程教学情况调查，共 5 题，约 2 分钟完成。你的反馈将用于改进下学期教学安排。',
    category: 'TEACHING',
    anonymous: 1,
    limitOnce: 0,
  })
  if (created.status !== 200) throw new Error('建卷失败 ' + JSON.stringify(created))
  const surveyId = created.body.data.id

  const q = await api(`/surveys/${surveyId}/questions`, 'PUT', {
    questions: [
      { questionType: 'SINGLE', title: '你所在的年级', isRequired: 1, sortNo: 1,
        options: [{ content: '大一' }, { content: '大二' }, { content: '大三' }, { content: '大四' }] },
      { questionType: 'MULTIPLE', title: '你希望增加哪种教学形式（可多选）', isRequired: 1, sortNo: 2,
        options: [{ content: '课堂讨论' }, { content: '小组作业' }, { content: '实验实操' }, { content: '案例分析' }] },
      { questionType: 'TEXT', title: '用一句话评价这门课', isRequired: 0, maxLength: 20, sortNo: 3 },
      { questionType: 'TEXTAREA', title: '对课程有什么建议', isRequired: 0, maxLength: 300, sortNo: 4 },
      { questionType: 'RATING', title: '你会向学弟学妹推荐这门课吗', isRequired: 1, ratingMax: 5, sortNo: 5 },
    ],
  })
  if (q.status !== 200) throw new Error('加题失败 ' + JSON.stringify(q))

  const detail = await api(`/surveys/${surveyId}`)
  const opts = detail.body.data.questions.map((x) => (x.options ?? []).map((o) => o.id))

  const pub = await api(`/surveys/${surveyId}/publish`, 'POST', {})
  if (pub.status !== 200) throw new Error('发布失败 ' + JSON.stringify(pub))
  const shareCode = pub.body.data.shareCode
  console.log('  问卷', surveyId, shareCode)

  // ---------- 2. 造 8 份答卷 ----------
  console.log('学生提交 8 份答卷 ...')
  await login('student', 'Student@123')

  // 单选 0:3 1:2 2:2 3:1 / 多选 4,3,3,2 / 评分 5,5,4,4,4,3,5,4
  const plans = [
    { s: 0, m: [0, 1], t: '讲得很清楚', tl: '希望多些课堂练习，作业量能少一点。', r: 5 },
    { s: 0, m: [0, 1, 2], t: '老师很负责', tl: '实验课很有帮助，希望增加实验课时。', r: 5 },
    { s: 0, m: [0, 2], t: '内容充实', tl: '作业有点多，希望减少一些重复练习。', r: 4 },
    { s: 1, m: [1, 2], t: '节奏偏快', tl: '希望老师讲慢一点，多留些思考时间。', r: 4 },
    { s: 1, m: [0, 1, 2], t: '互动很好', tl: '小组讨论的形式很好，建议继续保留。', r: 3 },
    { s: 2, m: [2, 3], t: '案例有意思', tl: '案例分析很有启发，希望再多讲几个。', r: 5 },
    { s: 2, m: [0, 3], t: '收获很大', tl: '整体很满意，希望增加实验课的比例。', r: 4 },
    { s: 3, m: [1], t: '中规中矩', tl: '希望课程内容能更贴近实际应用一些。', r: 4 },
  ]

  for (const p of plans) {
    const res = await api(`/hall/surveys/${surveyId}/submit`, 'POST', {
      answers: [
        { questionId: detail.body.data.questions[0].id, optionValues: [opts[0][p.s]] },
        { questionId: detail.body.data.questions[1].id, optionValues: p.m.map((i) => opts[1][i]) },
        { questionId: detail.body.data.questions[2].id, textValue: p.t },
        { questionId: detail.body.data.questions[3].id, textValue: p.tl },
        { questionId: detail.body.data.questions[4].id, numberValue: p.r },
      ],
    })
    if (res.status !== 200) throw new Error('提交失败 ' + JSON.stringify(res))
  }
  console.log('  8 份答卷已提交')

  // ---------- 3. 模板（教师共享 + 管理员审核 + 学生评分）----------
  console.log('造模板数据 ...')
  await login('teacher', 'Teacher@123')
  const tpl = await api('/templates', 'POST', {
    sourceSurveyId: surveyId,
    title: '课程满意度调查（标准模板）',
    description: '适用于学期末的课程教学反馈，含年级、教学形式偏好、主观评价与推荐度 5 道题。',
    category: 'TEACHING',
  })
  if (tpl.status !== 200) throw new Error('共享模板失败 ' + JSON.stringify(tpl))
  const tplId = tpl.body.data.id

  await login('admin', 'Admin@123')
  const audit = await api(`/templates/${tplId}/audit`, 'PUT', { auditStatus: 'APPROVED', auditRemark: '' })
  if (audit.status !== 200) throw new Error('审核失败 ' + JSON.stringify(audit))

  await login('student', 'Student@123')
  await api(`/templates/${tplId}/rating?score=5`, 'PUT')

  // ---------- 4. 截图 ----------
  console.log('开始截图 ...')

  // 4.1 教师工作台
  await login('teacher', 'Teacher@123')
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
  await shot('01-教师工作台.png')

  // 4.2 我的问卷列表
  await page.goto(BASE + '/surveys', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.el-table, .survey-card', { timeout: 10000 }).catch(() => {})
  await shot('02-我的问卷列表.png')

  // 4.3 编辑器
  await page.goto(`${BASE}/surveys/${surveyId}/edit`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.question-item', { timeout: 10000 }).catch(() => {})
  await shot('03-问卷编辑器.png')

  // 4.4 统计页
  await page.goto(`${BASE}/surveys/${surveyId}/stats`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('canvas', { timeout: 15000 }).catch(() => {})
  await page.waitForTimeout(1500)
  await shot('04-统计分析-单选.png')

  // 4.5 统计页切到多选题（验证「多选不画饼图」的提示）
  const navItems = page.locator('.q-nav .q-nav-item, .q-nav li, .q-nav > div')
  const n = await navItems.count()
  if (n > 1) {
    await navItems.nth(1).click()
    await page.waitForTimeout(1200)
    await shot('05-统计分析-多选.png')
  }
  // 4.6 切到评分题
  if (n > 4) {
    await navItems.nth(4).click()
    await page.waitForTimeout(1200)
    await shot('06-统计分析-评分.png')
  }

  // 4.7 答卷管理
  await page.goto(`${BASE}/surveys/${surveyId}/answers`, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  await shot('07-答卷管理.png')

  // 4.8 模板中心（学生视角，能评分）
  await login('student', 'Student@123')
  await page.goto(BASE + '/templates', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1800)
  await shot('08-模板中心.png')

  // 4.9 问卷大厅
  await page.goto(BASE + '/hall', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  await shot('09-问卷大厅.png')

  // 4.10 管理员日志页
  await login('admin', 'Admin@123')
  await page.goto(BASE + '/admin/logs', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1800)
  await shot('10-日志查询.png')

  // 4.11 系统配置
  await page.goto(BASE + '/admin/config', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  await shot('11-系统配置.png')

  // 4.12 登录页（未登录）
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1200)
  await shot('12-登录页.png')

  console.log('\n完成。截图目录：' + OUT)
  console.log('演示问卷 id=' + surveyId + ' 分享码=' + shareCode + ' 模板 id=' + tplId)
  if (errors.length) console.log('控制台错误 ' + errors.length + ' 条：', errors.slice(0, 3))
} finally {
  await browser.close()
}
