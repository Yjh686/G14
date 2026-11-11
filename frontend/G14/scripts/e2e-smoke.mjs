/**
 * 前端 E2E 冒烟：用已安装的 Chrome 走一遍登录 → 工作台 → 用户管理，
 * 并单独验证「无权限账号看不到后台菜单」。
 *
 * 用法（需先启动后端 8080 与前端 5173）：
 *   node scripts/e2e-smoke.mjs
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
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

/** 收集浏览器控制台报错，最后统一判定 */
const consoleErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => consoleErrors.push(String(err)))

try {
  section('1. 未登录访问受保护页面')
  await page.goto(`${BASE}/admin/users`, { waitUntil: 'networkidle' })
  check('重定向到登录页', page.url().includes('/login'), page.url())
  check('登录页标题正确', (await page.title()).includes('登录'), await page.title())
  check('表单渲染完成', await page.locator('.auth-brand').isVisible())

  section('2. 错误密码')
  await page.fill('input[autocomplete="username"]', 'admin')
  await page.fill('input[autocomplete="current-password"]', 'wrongpass123')
  await page.click('button:has-text("登 录")')
  const errAlert = page.locator('.el-alert__title')
  await errAlert.waitFor({ timeout: 8000 })
  const errText = await errAlert.innerText()
  check('显示后端错误文案', errText.includes('账号或密码错误'), errText)

  section('3. 管理员登录')
  await page.fill('input[autocomplete="current-password"]', 'Admin@123')
  await page.click('button:has-text("登 录")')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 })
  // 登录页带 redirect=/admin/users，登录后应回到用户管理页
  check('登录后按 redirect 回到原页面', page.url().includes('/admin/users'), page.url())

  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.welcome-title', { timeout: 15000 })
  const welcome = await page.locator('.welcome-title').innerText()
  check('工作台展示当前用户名', welcome.includes('系统管理员'), welcome)

  const token = await page.evaluate(() => localStorage.getItem('g14_token'))
  check('令牌已持久化', Boolean(token) && token.length > 50)

  section('4. 权限驱动的菜单')
  const menuText = await page.locator('.layout-menu').innerText()
  check('管理员可见用户管理', menuText.includes('用户管理'), menuText.replace(/\s+/g, ' '))
  check('管理员可见注册审核', menuText.includes('注册审核'))
  check('管理员可见权限配置', menuText.includes('权限配置'))

  section('5. 用户管理列表（真实接口数据）')
  await page.click('.layout-menu >> text=用户管理')
  await page.waitForURL('**/admin/users', { timeout: 10000 })
  await page.waitForSelector('.el-table__row', { timeout: 15000 })
  const rowCount = await page.locator('.el-table__row').count()
  check('表格有数据', rowCount >= 3, `rows=${rowCount}`)
  const tableText = await page.locator('.el-table').innerText()
  check('列表含角色列', tableText.includes('教师') || tableText.includes('学生'))
  // 用户表按 id 倒序分页，压测或冒烟脚本新建的账号会把演示账号挤到后面几页。
  // 这里用搜索定位，而不是假设演示账号在第 1 页 —— 否则库里有几个测试账号就假失败。
  const search = page.locator('input[placeholder="搜索学号/工号或姓名"]')
  await search.fill('张老师')
  await search.press('Enter')
  await page.waitForTimeout(800)
  check('搜索到教师账号', (await page.locator('.el-table').innerText()).includes('张老师'))

  await search.fill('李同学')
  await search.press('Enter')
  await page.waitForTimeout(800)
  check('搜索到学生账号', (await page.locator('.el-table').innerText()).includes('李同学'))

  await search.fill('')
  await search.press('Enter')
  await page.waitForTimeout(600)

  section('6. 新建用户对话框')
  await page.click('button:has-text("新建用户")')
  await page.waitForSelector('.el-dialog', { timeout: 8000 })
  check('对话框打开', await page.locator('.el-dialog__title').innerText() === '新建用户')
  await page.click('.el-dialog button:has-text("取消")')

  section('7. 刷新保持登录态')
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('.layout-menu', { timeout: 15000 })
  check('刷新后仍在后台', page.url().includes('/admin/users'), page.url())
  check('刷新后仍显示用户名', (await page.locator('.user-name').innerText()).includes('系统管理员'))

  section('8. 学生账号看不到后台菜单')
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', 'student')
  await page.fill('input[autocomplete="current-password"]', 'Student@123')
  await page.click('button:has-text("登 录")')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15000 })
  const studentMenu = await page.locator('.layout-menu').innerText()
  check('学生看不到用户管理', !studentMenu.includes('用户管理'), studentMenu.replace(/\s+/g, ' '))

  section('9. 学生直接访问后台被拦下')
  await page.goto(`${BASE}/admin/users`, { waitUntil: 'networkidle' })
  await page.waitForURL('**/forbidden', { timeout: 10000 })
  check('跳转到 403 页面', page.url().includes('/forbidden'), page.url())
  check('403 提示可见', (await page.locator('.el-result__title').innerText()).includes('403'))

  section('10. 退出登录')
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle' })
  await page.click('.user-info')
  await page.click('text=退出登录')
  await page.waitForSelector('.el-message-box', { timeout: 8000 })
  await page.click('.el-message-box button:has-text("退出")')
  await page.waitForURL('**/login', { timeout: 10000 })
  check('退出后回到登录页', page.url().includes('/login'))
  const tokenAfter = await page.evaluate(() => localStorage.getItem('g14_token'))
  check('本地令牌已清除', !tokenAfter)

  section('11. 浏览器控制台')
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
  await browser.close()
}

console.log('\n============================================')
console.log(`结果：通过 ${pass} 项，失败 ${fail} 项`)
console.log('============================================')
process.exit(fail === 0 ? 0 : 1)
