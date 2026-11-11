/**
 * 前端 E2E 冒烟（问卷模块）：教师建卷发布 → 学生凭分享码填写提交 →
 * 学生看自己的记录（匿名不回显）→ 教师看答卷（填写人被裁剪）。
 *
 * 用法（需先启动后端 8080 与前端 5173）：
 *   node scripts/e2e-survey.mjs
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

/** 换账号：清掉本地令牌再登录，避免上一轮的权限干扰 */
async function login(username, password) {
  // 必须先进入同源页面才能访问 localStorage（about:blank 会抛 SecurityError）
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button:has-text("登 录")')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
}

/** 标题加时间戳，保证每次跑都是一个新问卷，避免撞到上次的数据 */
const TITLE = `E2E 问卷 ${Date.now().toString().slice(-6)}`
let surveyId = null
let shareCode = null

try {
  section('1. 教师登录，菜单按权限生成')
  await login('teacher', 'Teacher@123')
  await page.waitForSelector('.layout-menu', { timeout: 15000 })
  const teacherMenu = (await page.locator('.layout-menu').innerText()).replace(/\s+/g, ' ')
  check('教师可见「我的问卷」', teacherMenu.includes('我的问卷'), teacherMenu)
  check('教师可见「填写问卷」', teacherMenu.includes('填写问卷'))
  check('教师看不到「用户管理」', !teacherMenu.includes('用户管理'))

  section('2. 新建问卷并编辑题目')
  await page.goto(`${BASE}/surveys`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.page-title', { timeout: 15000 })
  check('问卷列表页标题正确', (await page.locator('.page-title').innerText()).includes('我的问卷'))

  await page.click('button:has-text("新建问卷")')
  await page.waitForURL('**/surveys/new', { timeout: 15000 })
  check('进入新建问卷页', page.url().includes('/surveys/new'))
  // 新建页会同步补一道单选题，等它渲染出来再断言
  await page.waitForSelector('.question-no', { timeout: 15000 })
  check('默认带一道单选题', (await page.locator('.question-no').count()) === 1)

  await page.fill(
    'input[placeholder="例如：2024 秋季教学满意度调查"]',
    TITLE,
  )

  // 第一题：单选题（新建时默认已加）
  await page.locator('input[placeholder="请输入题干"]').nth(0).fill('你对本课程的满意度？')
  const optInputs = page.locator('input[placeholder="选项内容"]')
  await optInputs.nth(0).fill('非常满意')
  await optInputs.nth(1).fill('一般')

  // 第二题：填空题
  await page.click('button:has-text("添加题目")')
  await page.click('.el-dropdown-menu__item:has-text("填空题")')
  await page.locator('input[placeholder="请输入题干"]').nth(1).fill('还有什么建议')
  check('已添加两道题', (await page.locator('.question-no').count()) === 2)

  // 设为匿名，方便后面验证匿名裁剪
  await page.click('.el-form-item:has-text("匿名填写") .el-switch')

  section('3. 保存并发布')
  await page.click('.page-header button:has-text("保存")')
  await page.waitForSelector('.el-message--success', { timeout: 15000 })
  const savedMsg = await page.locator('.el-message--success').innerText()
  check('保存成功提示', savedMsg.includes('已保存'), savedMsg)

  // 保存后路由会替换成 /surveys/{id}/edit，从 URL 里取出 id
  await page.waitForURL(/\/surveys\/\d+\/edit/, { timeout: 15000 })
  surveyId = Number(page.url().match(/\/surveys\/(\d+)\/edit/)[1])
  check('拿到问卷 id', Number.isFinite(surveyId), `id=${surveyId}`)

  await page.click('.page-header button:has-text("发布")')
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  await page.click('.el-message-box button:has-text("发布")')
  await page.waitForSelector('.share-code', { timeout: 15000 })
  shareCode = (await page.locator('.share-code').innerText()).trim()
  check('发布后生成 8 位分享码', shareCode.length === 8, shareCode)
  check(
    '状态变为收集中',
    (await page.locator('.page-title').innerText()).includes('收集中'),
  )

  section('4. 发布后题目结构锁定')
  const lockedHint = await page.locator('.page-desc').first().innerText()
  check('页面提示结构已锁定', lockedHint.includes('只能修改标题'), lockedHint)
  check('「添加题目」按钮已隐藏', (await page.locator('button:has-text("添加题目")').count()) === 0)
  check('题干输入框只读', await page.locator('input[placeholder="请输入题干"]').first().isDisabled())

  section('5. 学生登录并用分享码进入填写')
  await login('student', 'Student@123')
  await page.waitForSelector('.layout-menu', { timeout: 15000 })
  const studentMenu = (await page.locator('.layout-menu').innerText()).replace(/\s+/g, ' ')
  check('学生可见「填写问卷」', studentMenu.includes('填写问卷'), studentMenu)
  check('学生看不到「我的问卷」', !studentMenu.includes('我的问卷'))
  check('学生可见「我的填写记录」', studentMenu.includes('我的填写记录'))

  await page.goto(`${BASE}/fill`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.survey-card, .el-empty', { timeout: 15000 })
  await page.fill('input[placeholder="输入分享码"]', shareCode)
  await page.click('button:has-text("进入")')
  await page.waitForURL(new RegExp(`/fill/${shareCode}`), { timeout: 15000 })
  await page.waitForSelector('.fill-title', { timeout: 15000 })
  check('分享码进入成功', (await page.locator('.fill-title').innerText()) === TITLE)
  check('渲染出两道题', (await page.locator('.question').count()) === 2)

  section('6. 必答校验')
  await page.click('button:has-text("提交答卷")')
  await page.waitForSelector('.el-message--warning', { timeout: 10000 })
  const warnText = await page.locator('.el-message--warning').innerText()
  check('未作答时拦下提交', warnText.includes('请完成第 1 题'), warnText)
  check('第一题被高亮', (await page.locator('.question.is-invalid').count()) >= 1)

  section('7. 作答并提交')
  await page.click('.question:has-text("你对本课程的满意度") .el-radio')
  // 填空题（TEXT）渲染成单行 input，多行文本（TEXTAREA）才是 textarea
  await page
    .locator('.question:has-text("还有什么建议") input[placeholder="请输入"]')
    .fill('希望多些实验课')
  await page.click('button:has-text("提交答卷")')
  await page.waitForSelector('.el-message-box', { timeout: 10000 })
  await page.click('.el-message-box button:has-text("提交")')
  await page.waitForSelector('.el-result__title', { timeout: 20000 })
  const receiptTitle = await page.locator('.el-result__title').innerText()
  check('显示提交成功回执', receiptTitle.includes('提交成功'), receiptTitle)
  check('回执里有编号', (await page.locator('.receipt-value').first().innerText()).startsWith('#'))

  section('8. 限填一次：再进同一问卷')
  await page.goto(`${BASE}/fill/${shareCode}`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-result__title', { timeout: 15000 })
  const againTitle = await page.locator('.el-result__title').innerText()
  check('提示已提交过', againTitle.includes('已提交过'), againTitle)
  check('不再渲染答题表单', (await page.locator('.question').count()) === 0)

  section('9. 学生的填写记录（匿名不回显）')
  await page.goto(`${BASE}/my-answers`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  const myTable = await page.locator('.el-table').innerText()
  check('记录里有这份问卷', myTable.includes(TITLE))
  // 断言按行定位：库里可能残留别的非匿名记录（本地演示提交的），
  // 那些行本来就该有「查看作答」。只有这份匿名卷所在的行必须显示「匿名，不回显」。
  const myRow = page.locator('.el-table__row', { hasText: TITLE })
  check('这份卷只占一行', (await myRow.count()) === 1)
  check('匿名卷提示不回显', (await myRow.innerText()).includes('匿名，不回显'))
  check('这一行没有「查看作答」入口', (await myRow.locator('button:has-text("查看作答")').count()) === 0)

  section('10. 学生访问问卷管理被拦下')
  await page.goto(`${BASE}/surveys`, { waitUntil: 'networkidle' })
  await page.waitForURL('**/forbidden', { timeout: 15000 })
  check('跳转到 403 页面', page.url().includes('/forbidden'), page.url())

  section('11. 教师查看答卷：填写人被裁剪')
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/surveys/${surveyId}/answers`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.stat-value', { timeout: 15000 })

  const statLabels = await page.locator('.stat-label').allInnerTexts()
  const statValues = await page.locator('.stat-value').allInnerTexts()
  check('展示四项回收概览', statLabels.length === 4, statLabels.join('/'))
  check('已回收 1 份', statValues[0] === '1', statValues.join('/'))
  check('有效答卷 1 份', statValues[1] === '1')

  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  const answerTable = await page.locator('.el-table').innerText()
  check('答卷列表显示匿名', answerTable.includes('匿名'), answerTable.replace(/\s+/g, ' '))
  check('答卷列表不含学生姓名', !answerTable.includes('李同学'))
  check('答卷列表不含学号', !answerTable.includes('student'))

  await page.click('.el-table__row button:has-text("查看")')
  await page.waitForURL('**/surveys/answers/**', { timeout: 15000 })
  await page.waitForSelector('.answer-item', { timeout: 15000 })
  const detailText = await page.locator('.meta-bar').innerText()
  check('详情页填写人显示匿名', detailText.includes('匿名'), detailText.replace(/\s+/g, ' '))
  check('详情页有 2 条作答', (await page.locator('.answer-item').count()) === 2)
  const answerBody = await page.locator('.answer-value').first().innerText()
  check('详情页选项文本正确', answerBody.includes('非常满意'), answerBody)

  section('12. 教师问卷列表里能看到这份问卷')
  await page.goto(`${BASE}/surveys`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  const listText = await page.locator('.el-table').innerText()
  check('列表包含新建的问卷', listText.includes(TITLE), listText.slice(0, 120).replace(/\s+/g, ' '))
  check('列表显示分享码', listText.includes(shareCode))

  section('13. 浏览器控制台')
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
  // 清掉本次跑出来的问卷，避免每跑一次就往库里留一份测试数据。
  // 用页面里的 fetch 直接打后端，借当前登录态（教师有 survey:delete:self）。
  if (surveyId) {
    try {
      await page.goto(`${BASE}/surveys`, { waitUntil: 'networkidle' })
      const res = await page.evaluate(async (id) => {
        const token = localStorage.getItem('g14_token')
        const r = await fetch(`/api/surveys/${id}?confirm=true`, {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` },
        })
        return r.status
      }, surveyId)
      console.log(`\n清理：删除问卷 ${surveyId} → HTTP ${res}`)
    } catch (e) {
      console.log(`\n清理失败（可忽略）：${e}`)
    }
  }
  await browser.close()
}

console.log('\n============================================')
console.log(`结果：通过 ${pass} 项，失败 ${fail} 项`)
if (surveyId) console.log(`本次问卷：id=${surveyId} shareCode=${shareCode} 标题=${TITLE}`)
console.log('============================================')
process.exit(fail === 0 ? 0 : 1)
