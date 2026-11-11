/**
 * 前端 E2E 冒烟（统计与导出）：
 * 统计页图表渲染、多选占比提示、评分平均分、文本高频词、导出 xlsx、学生越权。
 *
 * 用法（需先启动后端 8080 与前端 5173）：
 *   node scripts/e2e-stat.mjs
 *
 * 造数据走接口而不是走界面：建卷要 5 种题型共 10 多个字段，
 * 用界面点一遍既慢又和 e2e-survey.mjs 重复。这里只验证「统计页本身」，
 * 数据准备用 fetch 直连后端，最后统一清理。
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
const context = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  acceptDownloads: true,
})
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

/**
 * 在浏览器里直接调后端接口（带当前登录态）。
 * 走 5173 的 /api 代理，和页面用的是同一条链路。
 */
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

const TITLE = `E2E 统计 ${Date.now().toString().slice(-6)}`
let surveyId = null
const straySurveys = []

try {
  section('1. 教师登录并造数据（5 种题型 + 5 份答卷）')
  await login('teacher', 'Teacher@123')
  check('教师登录成功', !page.url().includes('/login'))

  const created = await api('/surveys', 'POST', {
    title: TITLE,
    description: 'E2E 统计用卷',
    category: 'TEACHING',
    anonymous: 1,
    limitOnce: 0,
  })
  surveyId = created?.id ?? null
  check('创建问卷', Number.isFinite(surveyId), `id=${surveyId}`)
  if (!surveyId) throw new Error('创建问卷失败，无法继续')

  await api(`/surveys/${surveyId}/questions`, 'PUT', {
    questions: [
      {
        questionType: 'SINGLE',
        title: '你所在的年级？',
        isRequired: 1,
        options: [{ content: '大一' }, { content: '大二' }, { content: '大三' }],
      },
      {
        questionType: 'MULTIPLE',
        title: '你用过哪些学习方式？',
        isRequired: 1,
        options: [
          { content: '自学' },
          { content: '小组讨论' },
          { content: '看视频' },
          { content: '刷题' },
        ],
      },
      { questionType: 'TEXT', title: '你的学号后四位', maxLength: 20 },
      {
        questionType: 'TEXTAREA',
        title: '对本课程的建议',
        maxLength: 300,
      },
      { questionType: 'RATING', title: '推荐学弟学妹选这门课吗？', ratingMax: 5 },
    ],
  })
  const published = await api(`/surveys/${surveyId}/publish`, 'POST')
  check('发布问卷', published?.status === 'PUBLISHED', published?.shareCode ?? '')

  // 5 份答卷：单选 2/2/1（下标 0,0,1,1,2），多选 4/3/2/2，评分 4.2，文本含重复词
  const plans = [
    { opt: [0], multi: [0, 1], rate: 5, note: '作业量有点大，希望减少作业量' },
    { opt: [0], multi: [0, 1, 2], rate: 4, note: '作业量合适，实验课很好' },
    { opt: [1], multi: [0, 2], rate: 5, note: '希望减少实验报告' },
    { opt: [1], multi: [0, 3], rate: 4, note: '实验课安排合理' },
    { opt: [2], multi: [1, 3], rate: 3, note: '作业有点多' },
  ]
  // 选项 id 要按后端返回的真实 id 取，不能凭下标猜
  const detail = await api(`/surveys/${surveyId}`)
  const [q1, q2, q3, q4, q5] = detail.questions
  const submitted = []
  for (const p of plans) {
    const one = await api(`/hall/surveys/${surveyId}/submit`, 'POST', {
      answers: [
        { questionId: q1.id, optionValues: [q1.options[p.opt[0]].id] },
        { questionId: q2.id, optionValues: p.multi.map((i) => q2.options[i].id) },
        { questionId: q3.id, textValue: `20${p.rate}4` },
        { questionId: q4.id, textValue: p.note },
        { questionId: q5.id, numberValue: p.rate },
      ],
    })
    submitted.push(one?.id)
  }
  check('提交 5 份答卷', submitted.filter(Boolean).length === 5, submitted.join(','))

  section('2. 统计页概览')
  await page.goto(`${BASE}/surveys/${surveyId}/stats`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.q-nav-item', { timeout: 20000 })
  check('页面标题含统计分析', (await page.locator('.page-title').innerText()).includes('统计分析'))
  check('显示匿名标记', (await page.locator('.page-title').innerText()).includes('匿名'))

  const statValues = await page.locator('.stat-value').allInnerTexts()
  check('有效答卷数=5', statValues[0] === '5', statValues.join('/'))
  check('回收总数=5', statValues[1] === '5')

  const navCount = await page.locator('.q-nav-item').count()
  check('题目导航 5 项', navCount === 5, String(navCount))

  section('3. 单选题：柱状图 + 饼图 + 明细表')
  check('题干显示正确', (await page.locator('.q-head-title').innerText()).includes('你所在的年级'))
  check('单选渲染两张图（柱 + 饼）', (await page.locator('.chart-canvas canvas').count()) === 2)
  const rows = await page.locator('.detail-table .el-table__body tr').count()
  check('选项明细 3 行', rows === 3, String(rows))
  const ratioTexts = await page.locator('.ratio-text').allInnerTexts()
  check('占比显示 40.0%', ratioTexts.includes('40.0%'), ratioTexts.join('/'))
  check(
    '页脚注明分母',
    (await page.locator('.foot-note').first().innerText()).includes('分母 = 该题有效作答人数 5'),
  )

  section('4. 多选题：只给柱状图 + 分母提示')
  await page.locator('.q-nav-item').nth(1).click()
  await page.waitForTimeout(600)
  check('切换到多选题', (await page.locator('.q-head-title').innerText()).includes('学习方式'))
  check('多选只有一张图（不画饼图）', (await page.locator('.chart-canvas canvas').count()) === 1)
  check(
    '出现「占比之和会超过 100%」提示',
    (await page.locator('.el-alert__title').allInnerTexts()).some((t) =>
      t.includes('多选题占比之和会超过 100%'),
    ),
  )
  const multiRatios = await page.locator('.ratio-text').allInnerTexts()
  check('多选占比 80.0% 存在', multiRatios.includes('80.0%'), multiRatios.join('/'))

  section('5. 文本题：高频词与作答列表')
  await page.locator('.q-nav-item').nth(3).click()
  await page.waitForTimeout(600)
  check('切换到多行文本', (await page.locator('.q-head-title').innerText()).includes('建议'))
  const wordTags = await page.locator('.word-tag').allInnerTexts()
  check('有高频词标签', wordTags.length > 0, wordTags.slice(0, 5).join('/'))
  check(
    '高频词含「作业」',
    wordTags.some((t) => t.includes('作业')),
    wordTags.slice(0, 3).join('/'),
  )
  const textItems = await page.locator('.text-item').count()
  check('作答列表 5 条', textItems === 5, String(textItems))
  // 5 份答卷提交时间可能落在同一秒，倒序排序不稳定，
  // 所以断言「全部作答里含作业」而不是「第一条含作业」
  const allTexts = await page.locator('.text-value').allInnerTexts()
  check('作答内容可见', allTexts.some((t) => t.includes('作业')), allTexts.join(' | ').slice(0, 80))

  section('6. 评分题：平均分与星级分布')
  await page.locator('.q-nav-item').nth(4).click()
  await page.waitForTimeout(600)
  check('切换到评分题', (await page.locator('.q-head-title').innerText()).includes('推荐'))
  // 5 份评分 5,4,5,4,3 之和 21，平均 4.2；验收标准要求展示保留 1 位小数。
  // 注意不能断言 4.20：那正是 2 位小数的写法，这个断言本身就是回归防线。
  check('平均分 4.2（保留 1 位小数）', (await page.locator('.rating-num').innerText()) === '4.2')
  check('参评人数显示', (await page.locator('.rating-meta').innerText()).includes('参评 5 人'))
  check('评分图渲染', (await page.locator('.chart-canvas canvas').count()) >= 1)

  section('7. 导出 Excel')
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    page.click('.header-actions button:has-text("导出 Excel")'),
  ])
  const name = download.suggestedFilename()
  check('触发下载', true, name)
  check('文件名是 xlsx', name.endsWith('.xlsx'), name)
  check('文件名含问卷标题', name.includes('E2E 统计'), name)
  const path = await download.path()
  check('下载文件非空', Boolean(path))

  section('8. 学生无权访问统计页')
  await login('student', 'Student@123')
  await page.goto(`${BASE}/surveys/${surveyId}/stats`, { waitUntil: 'networkidle' })
  await page.waitForURL(/\/forbidden/, { timeout: 15000 })
  check('学生被拦截到 403 页', page.url().includes('/forbidden'))
  check('403 页有提示', (await page.locator('body').innerText()).includes('403'))

  section('9. 浏览器控制台')
  const realErrors = consoleErrors.filter(
    (t) =>
      !t.includes('Failed to load resource') &&
      !t.includes('401') &&
      !t.includes('403') &&
      !t.includes('favicon'),
  )
  check('无未预期的前端报错', realErrors.length === 0, realErrors.slice(0, 3).join(' | '))
} catch (e) {
  fail += 1
  console.log(`\n[FAIL] 脚本异常：${e}`)
} finally {
  // 清理：删掉本次造的问卷（硬删子表由后端软删除处理，统计数据不参与断言后即可丢弃）
  try {
    await login('teacher', 'Teacher@123')
    if (surveyId) {
      const r = await api(`/surveys/${surveyId}?confirm=true`, 'DELETE')
      console.log(`  清理问卷 ${surveyId} -> ${r?.code ?? JSON.stringify(r).slice(0, 40)}`)
    }
    for (const id of straySurveys) {
      await api(`/surveys/${id}?confirm=true`, 'DELETE')
    }
  } catch (e) {
    console.log(`  清理失败：${e}`)
  }
  await browser.close()
  console.log('\n' + '-'.repeat(40))
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail === 0 ? 0 : 1)
}
