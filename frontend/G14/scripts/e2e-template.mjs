/**
 * 前端 E2E 冒烟（模板中心）：
 * 共享为模板 → 待审核不可见 → 管理员审核通过 → 学生评分 → 教师使用模板 → 下架。
 *
 * 用法（需先启动后端 8080 与前端 5173）：
 *   node scripts/e2e-template.mjs
 *
 * 造问卷走接口（和 e2e-stat.mjs 同样的理由：建卷字段太多，点界面太慢），
 * 但「共享 / 审核 / 评分 / 使用 / 下架」全部点真实界面 —— 这几步正是本模块的价值所在。
 * 所有断言结束后清理本次创建的数据。
 */
import { chromium } from 'playwright-core'

const BASE = process.env.E2E_BASE ?? 'http://localhost:5173'
const CHROME =
  process.env.E2E_CHROME ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

let pass = 0
let fail = 0

function check(name, ok, extra = '') {
  if (ok) {
    pass += 1
    console.log(`  [PASS] ${name}${extra ? ` (${extra})` : ''}`)
  } else {
    fail += 1
    console.log(`  [FAIL] ${name}${extra ? ` (${extra})` : ''}`)
  }
}

function section(title) {
  console.log(`\n=== ${title} ===`)
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()

const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => consoleErrors.push(String(err)))

async function login(username, password) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button:has-text("登 录")')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
}

/** 带当前登录态直连后端（走 5173 代理） */
async function api(path, method = 'GET', body = undefined) {
  const res = await page.evaluate(
    async ({ path, method, body }) => {
      const token = localStorage.getItem('g14_token')
      const r = await fetch(`/api${path}`, {
        method,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      const text = await r.text()
      let json = null
      try {
        json = JSON.parse(text)
      } catch {
        /* 非 JSON 保持 null */
      }
      return { status: r.status, json }
    },
    { path, method, body },
  )
  return res.json?.data ?? res.json ?? res
}

const STAMP = Date.now().toString().slice(-6)
const SURVEY_TITLE = `E2E 模板来源 ${STAMP}`
const TEMPLATE_TITLE = `E2E 教学反馈模板 ${STAMP}`
const REJECT_REASON = 'E2E 审核意见：题干说明过于笼统'

let surveyId = null
let templateId = null
let usedSurveyId = null

try {
  /* ---------------------------------------------------------------- */
  section('1. 教师建卷（共享模板的来源）')
  await login('teacher', 'Teacher@123')
  check('教师登录成功', !page.url().includes('/login'))

  const created = await api('/surveys', 'POST', {
    title: SURVEY_TITLE,
    description: '用于验证模板共享的问卷',
    category: 'TEACHING',
    anonymous: 1,
    limitOnce: 1,
  })
  surveyId = created?.id ?? null
  check('创建来源问卷', Number.isFinite(surveyId), `id=${surveyId}`)
  if (!surveyId) throw new Error('创建问卷失败，无法继续')

  await api(`/surveys/${surveyId}/questions`, 'PUT', {
    questions: [
      {
        questionType: 'SINGLE',
        title: '你对本课程的总体满意度？',
        isRequired: 1,
        options: [{ content: '非常满意' }, { content: '满意' }, { content: '一般' }],
      },
      {
        questionType: 'MULTIPLE',
        title: '你最喜欢哪种授课方式？',
        options: [{ content: '案例讨论' }, { content: '实验实操' }, { content: '分组汇报' }],
      },
      { questionType: 'TEXT', title: '还有其他建议吗？', maxLength: 100 },
    ],
  })
  const published = await api(`/surveys/${surveyId}/publish`, 'POST')
  check('来源问卷已发布', published?.status === 'PUBLISHED', published?.shareCode ?? '')

  /* ---------------------------------------------------------------- */
  section('2. 共享为模板（界面）')
  await page.goto(`${BASE}/my-templates`, { waitUntil: 'networkidle' })
  check('进入「我共享的模板」', (await page.locator('.page-title').innerText()).includes('我共享的模板'))

  await page.click('button:has-text("共享为模板")')
  const dialog = page.locator('.el-dialog:visible')
  await dialog.waitFor({ timeout: 10000 })
  check('打开共享对话框', await dialog.isVisible())
  check(
    '对话框提示快照语义',
    (await dialog.locator('.el-alert__title').innerText()).includes('快照'),
  )

  // 选来源问卷
  await dialog.locator('.el-select').first().click()
  await page.waitForTimeout(400)
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: SURVEY_TITLE }).first().click()
  await page.waitForTimeout(400)
  check(
    '选中问卷后自动带出模板名',
    (await dialog.locator('input[placeholder*="课程满意度"]').inputValue()).includes(SURVEY_TITLE),
  )

  // 改成自己的模板名
  const titleInput = dialog.locator('input[placeholder*="课程满意度"]')
  await titleInput.fill(TEMPLATE_TITLE)
  await dialog.locator('textarea').first().fill('E2E 用的模板说明')
  await dialog.locator('button:has-text("提交审核")').click()

  await page.waitForSelector('.el-message--success', { timeout: 15000 })
  check(
    '提交后提示已提交审核',
    (await page.locator('.el-message--success').first().innerText()).includes('已提交审核'),
  )

  await page.waitForTimeout(1200)
  const myRows = await page.locator('.el-table__body tr').count()
  check('我的模板列表出现 1 条', myRows === 1, String(myRows))
  const myStatus = await page.locator('.el-table__body .el-tag').first().innerText()
  check('状态为待审核', myStatus.includes('待审核'), myStatus)

  const mine = await api('/templates?scope=mine')
  templateId = mine?.records?.[0]?.id ?? null
  check('取到模板 id', Number.isFinite(templateId), `id=${templateId}`)
  check('后端 status=PENDING', mine?.records?.[0]?.status === 'PENDING')
  check(
    '快照题目数=3',
    mine?.records?.[0]?.questionCount === 3,
    String(mine?.records?.[0]?.questionCount),
  )

  /* ---------------------------------------------------------------- */
  section('3. 待审核的模板不出现在模板中心')
  await page.goto(`${BASE}/templates`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  const pendingVisible = await page.locator('.tpl-card').count()
  check('模板中心看不到待审模板', pendingVisible === 0, String(pendingVisible))

  await login('student', 'Student@123')
  await page.goto(`${BASE}/templates`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(900)
  check('学生看到的模板中心也是空的', (await page.locator('.tpl-card').count()) === 0)
  check(
    '空态文案正确',
    (await page.locator('.el-empty__description').innerText()).includes('还没有已通过审核'),
  )

  /* ---------------------------------------------------------------- */
  section('4. 管理员审核：先驳回，再通过')
  await login('admin', 'Admin@123')
  await page.goto(`${BASE}/admin/template-audit`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__body tr', { timeout: 15000 })
  check('审核队列有 1 条待审', (await page.locator('.el-table__body tr').count()) === 1)
  check(
    '审核队列显示共享者姓名',
    (await page.locator('.el-table__body').innerText()).includes('张老师'),
  )

  // 查看内容
  await page.click('.el-table__body button:has-text("查看内容")')
  await page.waitForSelector('.el-drawer__body .q-item', { timeout: 10000 })
  const drawerText = await page.locator('.el-drawer__body').innerText()
  check('抽屉显示题目结构', drawerText.includes('总体满意度'))
  check('抽屉显示选项文本', drawerText.includes('非常满意'))
  check('抽屉显示题量 3 题', drawerText.includes('3 题'))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(600)

  // 驳回必须填原因
  await page.click('.el-table__body button:has-text("驳回")')
  const rejectDialog = page.locator('.el-dialog:visible')
  await rejectDialog.waitFor({ timeout: 10000 })
  await rejectDialog.locator('button:has-text("确认驳回")').click()
  await page.waitForSelector('.el-message--warning', { timeout: 10000 })
  check(
    '驳回不填原因被拦下',
    (await page.locator('.el-message--warning').first().innerText()).includes('必须填写审核意见'),
  )

  await rejectDialog.locator('textarea').fill(REJECT_REASON)
  await rejectDialog.locator('button:has-text("确认驳回")').click()
  await page.waitForTimeout(1500)
  check('驳回后队列清空', (await page.locator('.el-table__body tr').count()) === 0)

  const rejected = await api(`/templates/${templateId}`)
  check('模板状态变为 REJECTED', rejected?.status === 'REJECTED', rejected?.status ?? '')
  check('驳回意见已保存', (rejected?.auditRemark ?? '').includes('E2E 审核意见'))

  // 共享者能看到驳回原因并重新提交
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/my-templates`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__body tr', { timeout: 15000 })
  check(
    '共享者看到驳回意见',
    (await page.locator('.el-table__body').innerText()).includes('E2E 审核意见'),
  )
  await page.click('.el-table__body button:has-text("重新提交")')
  await page.locator('.el-message-box__btns button:has-text("重新提交")').click()
  await page.waitForTimeout(1500)
  const resubmitted = await api(`/templates/${templateId}`)
  check(
    '重新提交后回到 PENDING',
    resubmitted?.status === 'PENDING',
    resubmitted?.status ?? '',
  )
  check('重新提交后清空审核意见', !resubmitted?.auditRemark, resubmitted?.auditRemark ?? 'null')

  // 管理员通过
  await login('admin', 'Admin@123')
  await page.goto(`${BASE}/admin/template-audit`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__body tr', { timeout: 15000 })
  await page.click('.el-table__body button:has-text("通过")')
  await page.locator('.el-message-box__btns button:has-text("通过")').click()
  await page.waitForTimeout(1500)
  const approved = await api(`/templates/${templateId}`)
  check('审核通过后 status=APPROVED', approved?.status === 'APPROVED', approved?.status ?? '')
  check(
    '审核人姓名已回填',
    (approved?.auditByName ?? '').includes('系统管理员'),
    approved?.auditByName ?? '',
  )

  /* ---------------------------------------------------------------- */
  section('5. 学生浏览模板中心并评分')
  await login('student', 'Student@123')
  await page.goto(`${BASE}/templates`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.tpl-card', { timeout: 15000 })
  check('学生看到 1 个已通过模板', (await page.locator('.tpl-card').count()) === 1)
  check('卡片显示模板名', (await page.locator('.card-title').first().innerText()).includes('E2E 教学反馈模板'))
  check('未评分时显示「暂无评分」', (await page.locator('.rating-text').first().innerText()).includes('暂无评分'))
  check(
    '学生没有「使用模板」按钮',
    (await page.locator('.tpl-card button:has-text("使用模板")').count()) === 0,
  )

  // 点第 4 颗星
  await page.locator('.tpl-card .el-rate__item').nth(3).click()
  await page.waitForSelector('.el-message--success', { timeout: 10000 })
  check('评分提交成功', (await page.locator('.el-message--success').first().innerText()).includes('评分已提交'))
  await page.waitForTimeout(1500)
  check(
    '评分后显示 4.0 分（1 人）',
    (await page.locator('.rating-text').first().innerText()).includes('4.0 分'),
    await page.locator('.rating-text').first().innerText(),
  )

  // 改分：点第 5 颗星
  await page.locator('.tpl-card .el-rate__item').nth(4).click()
  await page.waitForTimeout(1500)
  check(
    '改分后显示 5.0 分（仍 1 人）',
    (await page.locator('.rating-text').first().innerText()).includes('5.0 分'),
    await page.locator('.rating-text').first().innerText(),
  )

  await page.goto(`${BASE}/templates/${templateId}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.q-item', { timeout: 15000 })
  check('学生可查看模板详情', (await page.locator('.page-title').innerText()).includes('E2E 教学反馈模板'))
  check('详情显示题目结构 3 题', (await page.locator('.q-item').count()) === 3)
  check(
    '详情选项以文本展示',
    (await page.locator('.q-options').first().innerText()).includes('非常满意'),
  )

  /* ---------------------------------------------------------------- */
  section('6. 教师使用模板建卷')
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/templates`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.tpl-card', { timeout: 15000 })
  await page.click('.tpl-card button:has-text("使用模板")')
  await page.locator('.el-message-box__btns button:has-text("创建草稿")').click()
  await page.waitForURL(/\/surveys\/\d+\/edit/, { timeout: 20000 })
  usedSurveyId = Number(page.url().match(/\/surveys\/(\d+)\/edit/)?.[1])
  check('跳转到新问卷编辑器', Number.isFinite(usedSurveyId), `id=${usedSurveyId}`)

  await page.waitForSelector('.question-item', { timeout: 15000 })
  check('新问卷带出 3 道题', (await page.locator('.question-item').count()) === 3)
  // 题干与选项在编辑器里是 input，innerText 取不到，必须读 inputValue
  const titleValue = await page
    .locator('.question-item')
    .first()
    .locator('input[placeholder="请输入题干"]')
    .inputValue()
  check('新问卷题干正确', titleValue.includes('总体满意度'), titleValue)
  const optionValues = await page
    .locator('.question-item')
    .first()
    .locator('input[placeholder="选项内容"]')
    .evaluateAll((els) => els.map((e) => e.value))
  check('新问卷选项正确', optionValues.includes('非常满意'), optionValues.join('/'))
  check(
    '页面提示可继续修改再发布',
    (await page.locator('.page-desc').innerText()).includes('草稿') ||
      (await page.locator('.page-desc').innerText()).includes('发布'),
  )

  const used = await api(`/surveys/${usedSurveyId}`)
  check('新问卷是草稿', used?.status === 'DRAFT', used?.status ?? '')
  check('新问卷没有分享码', !used?.shareCode, used?.shareCode ?? 'null')
  check('新问卷题目数=3', used?.questions?.length === 3, String(used?.questions?.length))

  const afterUse = await api(`/templates/${templateId}`)
  check('模板被使用次数 +1', afterUse?.useCount === 1, String(afterUse?.useCount))
  check(
    '模板快照内容不变（与问卷解耦）',
    afterUse?.structure?.length === 3 && afterUse?.structure?.[0]?.title === '你对本课程的总体满意度？',
  )

  /* ---------------------------------------------------------------- */
  section('7. 改动新问卷不影响模板')
  await api(`/surveys/${usedSurveyId}/questions`, 'PUT', {
    questions: [
      { questionType: 'TEXT', title: '这是一道完全不同的题', maxLength: 50 },
    ],
  })
  const afterEdit = await api(`/templates/${templateId}`)
  check('改新问卷后模板仍是 3 题', afterEdit?.structure?.length === 3, String(afterEdit?.structure?.length))
  check(
    '改新问卷后模板题干未被污染',
    afterEdit?.structure?.[0]?.title === '你对本课程的总体满意度？',
  )

  /* ---------------------------------------------------------------- */
  section('8. 共享者下架模板')
  await page.goto(`${BASE}/my-templates`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__body tr', { timeout: 15000 })
  await page.click('.el-table__body button:has-text("下架")')
  await page.locator('.el-message-box__btns button:has-text("下架")').click()
  await page.waitForTimeout(1500)
  const offlined = await api(`/templates/${templateId}`)
  check('下架后 status=OFFLINE', offlined?.status === 'OFFLINE', offlined?.status ?? '')

  await page.goto(`${BASE}/templates`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  check('下架后模板中心看不到该模板', (await page.locator('.tpl-card').count()) === 0)

  /* ---------------------------------------------------------------- */
  section('9. 学生无权进入审核与共享页面')
  await login('student', 'Student@123')
  await page.goto(`${BASE}/admin/template-audit`, { waitUntil: 'networkidle' })
  await page.waitForURL(/\/forbidden/, { timeout: 15000 })
  check('学生进审核页被拦截', page.url().includes('/forbidden'))

  await page.goto(`${BASE}/my-templates`, { waitUntil: 'networkidle' })
  await page.waitForURL(/\/forbidden/, { timeout: 15000 })
  check('学生进「我共享的模板」被拦截', page.url().includes('/forbidden'))

  /* ---------------------------------------------------------------- */
  section('10. 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (t) =>
      !t.includes('Failed to load resource') &&
      !t.includes('401') &&
      !t.includes('403') &&
      !t.includes('404') &&
      !t.includes('favicon'),
  )
  check('无未预期的前端报错', realErrors.length === 0, realErrors.slice(0, 3).join(' | '))
} catch (e) {
  fail += 1
  console.log(`\n[FAIL] 脚本异常：${e}`)
} finally {
  try {
    await login('teacher', 'Teacher@123')
    // 模板：先下架再删（已下架才允许删）
    if (templateId) {
      await api(`/templates/${templateId}/offline`, 'POST')
      const r = await api(`/templates/${templateId}`, 'DELETE')
      console.log(`  清理模板 ${templateId} -> ${r?.code ?? 'done'}`)
    }
    if (usedSurveyId) {
      const r = await api(`/surveys/${usedSurveyId}?confirm=true`, 'DELETE')
      console.log(`  清理使用模板生成的问卷 ${usedSurveyId} -> ${r?.code ?? 'done'}`)
    }
    if (surveyId) {
      const r = await api(`/surveys/${surveyId}?confirm=true`, 'DELETE')
      console.log(`  清理来源问卷 ${surveyId} -> ${r?.code ?? 'done'}`)
    }
  } catch (e) {
    console.log(`  清理失败：${e}`)
  }
  await browser.close()
  console.log('\n' + '-'.repeat(40))
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail === 0 ? 0 : 1)
}
