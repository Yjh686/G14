/**
 * 前端 E2E 冒烟（问卷编辑细节）：预览、复制为新草稿、草稿自动保存。
 *
 * 用法（需先启动后端 8080 与前端 5173）：
 *   node scripts/e2e-survey-edit.mjs
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
const context = await browser.newContext({ viewport: { width: 1440, height: 960 } })
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

const TITLE = `E2E 编辑 ${Date.now().toString().slice(-6)}`
let draftId = null
let copyId = null

try {
  section('1. 教师登录并新建草稿')
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/surveys/new`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.question-no', { timeout: 15000 })
  await page.fill('input[placeholder="例如：2024 秋季教学满意度调查"]', TITLE)
  await page.locator('input[placeholder="请输入题干"]').nth(0).fill('你最喜欢哪门课？')
  await page.locator('input[placeholder="选项内容"]').nth(0).fill('高数')
  await page.locator('input[placeholder="选项内容"]').nth(1).fill('线代')

  await page.click('.page-header button:has-text("保存")')
  await page.waitForSelector('.el-message--success', { timeout: 15000 })
  await page.waitForURL(/\/surveys\/\d+\/edit/, { timeout: 15000 })
  draftId = Number(page.url().match(/\/surveys\/(\d+)\/edit/)[1])
  check('草稿创建成功', Number.isFinite(draftId), `id=${draftId}`)
  check('状态为草稿', (await page.locator('.page-title').innerText()).includes('草稿'))

  section('2. 预览页')
  await page.click('.page-header button:has-text("预览")')
  await page.waitForURL(/\/surveys\/\d+\/preview/, { timeout: 15000 })
  await page.waitForSelector('.preview-title', { timeout: 15000 })
  check('预览标题与问卷一致', (await page.locator('.preview-title').innerText()) === TITLE)
  check('预览渲染一道题', (await page.locator('.question').count()) === 1)
  check('预览选项可见', (await page.locator('.question').innerText()).includes('高数'))
  check(
    '预览中的单选框禁用',
    await page.locator('.question .el-radio.is-disabled').first().isVisible(),
  )
  await page.click('button:has-text("返回编辑")')

  section('3. 复制为新草稿')
  await page.goto(`${BASE}/surveys`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  const row = page.locator('.el-table__row', { hasText: TITLE }).first()
  await row.locator('button:has-text("更多")').click()
  await page.click('.el-dropdown-menu__item:has-text("复制为新草稿")')
  await page.waitForURL(/\/surveys\/\d+\/edit/, { timeout: 20000 })
  copyId = Number(page.url().match(/\/surveys\/(\d+)\/edit/)[1])
  check('生成了新草稿', copyId !== draftId, `copy=${copyId}`)
  // 路由变化先于详情接口返回，等输入框被详情回填后再断言
  const titleInput = page.locator('input[placeholder="例如：2024 秋季教学满意度调查"]')
  await page.waitForFunction(
    () => {
      const el = document.querySelector('input[placeholder="例如：2024 秋季教学满意度调查"]')
      return el && el.value.length > 0
    },
    { timeout: 15000 },
  )
  const copyTitle = await titleInput.inputValue()
  check('副本标题带标记', copyTitle.includes('（副本）'), copyTitle)
  await page.waitForSelector('.question-no', { timeout: 15000 })
  check('副本保留了题目', (await page.locator('.question-no').count()) === 1)

  section('4. 学生填写后草稿自动保存（5s 防抖）')
  await login('student', 'Student@123')
  // 先发布副本，才能被学生看到
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/surveys/${copyId}/edit`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.page-header', { timeout: 15000 })
  await page.click('.page-header button:has-text("发布")')
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  await page.click('.el-message-box button:has-text("发布")')
  await page.waitForSelector('.share-code', { timeout: 15000 })
  const code = (await page.locator('.share-code').innerText()).trim()
  check('副本发布成功', code.length === 8, code)

  await login('student', 'Student@123')
  await page.goto(`${BASE}/fill/${code}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.question', { timeout: 15000 })
  await page.click('.question .el-radio')
  // 防抖是 5s，等 7s 让草稿落地
  await page.waitForTimeout(7000)
  const metaText = await page.locator('.fill-meta').innerText()
  check('提示草稿已保存', metaText.includes('草稿已保存于'), metaText.replace(/\s+/g, ' '))

  section('5. 刷新后断点续答')
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('.question', { timeout: 15000 })
  check(
    '选项被恢复',
    await page.locator('.question .el-radio.is-checked').first().isVisible(),
  )

  section('6. 浏览器控制台')
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
  // 清掉本次产生的草稿与副本，避免测试数据堆积在库里
  const ids = [draftId, copyId].filter(Boolean)
  if (ids.length > 0) {
    try {
      // 最后一次登录的是学生，删问卷需要教师身份，这里重新登一次
      await login('teacher', 'Teacher@123')
      await page.goto(`${BASE}/surveys`, { waitUntil: 'networkidle' })
      const statuses = await page.evaluate(async (list) => {
        const token = localStorage.getItem('g14_token')
        const out = []
        for (const id of list) {
          const r = await fetch(`/api/surveys/${id}?confirm=true`, {
            method: 'DELETE',
            headers: { Authorization: `Bearer ${token}` },
          })
          out.push(`${id}:${r.status}`)
        }
        return out
      }, ids)
      console.log(`\n清理：${statuses.join(' ')}`)
    } catch (e) {
      console.log(`\n清理失败（可忽略）：${e}`)
    }
  }
  await browser.close()
}

console.log('\n============================================')
console.log(`结果：通过 ${pass} 项，失败 ${fail} 项`)
if (draftId) console.log(`草稿 id=${draftId} 副本 id=${copyId} 标题=${TITLE}`)
console.log('============================================')
process.exit(fail === 0 ? 0 : 1)
