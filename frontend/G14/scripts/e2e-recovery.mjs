/**
 * 前端 E2E 冒烟（密码找回）：
 * 个人资料页设置密保 → 未登录走「忘记密码」两步式重置 → 新密码登录 → 管理员在用户管理页清除密保。
 *
 * 用法（需先启动后端 8080 与前端 5173，或 E2E_BASE 指向容器）：
 *   node scripts/e2e-recovery.mjs
 *
 * 三个断言是历史缺陷的回归防线，不要删：
 *   1) 答错时提示里的剩余次数必须递减 —— 失败计数曾被事务回滚吃掉，锁定形同虚设；
 *   2) 操作列里「更多」与相邻按钮必须纵向对齐 —— el-dropdown 默认 vertical-align: top；
 *   3) 确认框中心必须落在视口中心 —— 漏引 Element Plus 编程式 API 样式时它会掉到 body 末尾。
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

async function logout() {
  await page.evaluate(() => localStorage.clear())
}

/** 试登录，失败返回 false 而不抛异常（用来自检演示密码是否还可用） */
async function tryLogin(username, password) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', username)
  await page.fill('input[autocomplete="current-password"]', password)
  await page.click('button:has-text("登 录")')
  try {
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 12000 })
    return true
  } catch {
    return false
  }
}

/**
 * 把演示学生的密码与密保都恢复成初始状态。
 *
 * 必须放在 finally 里跑：这个脚本自己会改掉学生密码（这正是被测功能），
 * 中途任何一步失败都会让下一个人（或下一轮自己）登录不上，然后看到一个
 * 与功能无关的「账号或密码错误」。
 */
async function restoreDemoStudent() {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.evaluate(() => localStorage.clear())
  await page.reload({ waitUntil: 'networkidle' })
  await page.fill('input[autocomplete="username"]', 'admin')
  await page.fill('input[autocomplete="current-password"]', 'Admin@123')
  await page.click('button:has-text("登 录")')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
  await api(`/users/${STUDENT_ID}/recovery`, 'DELETE')
  const tmp = await api(`/users/${STUDENT_ID}/reset-password`, 'POST')
  if (typeof tmp !== 'string') return false
  if (!(await tryLogin(DEMO_USER, tmp))) return false
  await api('/auth/password', 'POST', { oldPassword: tmp, newPassword: DEMO_PWD })
  return await tryLogin(DEMO_USER, DEMO_PWD)
}

/** 在浏览器里直连后端（带当前登录态），用于准备与清理数据 */
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
const Q1 = `E2E 宿舍号 ${STAMP}`
const A1 = `A1-${STAMP}`
const Q2 = `E2E 班主任姓 ${STAMP}`
const A2 = `Wang-${STAMP}`
// 与系统配置的默认规则一致（≥8 位且含字母与数字）；用 STAMP 保证每次不同
const NEW_PWD = `Rcv${STAMP}x9`
const DEMO_USER = 'student'
const DEMO_PWD = 'Student@123'
let STUDENT_ID = 3
const DEFAULT_PWD = 'G14@init2025'

try {
  // ---------------------------------------------------------------- 0. 预清理
  section('0. 预清理：解绑可能残留的密保')
  await login('admin', 'Admin@123')
  const users = await api('/users?keyword=student&page=1&size=10')
  const student = (users.records ?? []).find((u) => u.username === 'student')
  check('找到演示学生账号', !!student, student ? `id=${student.id}` : '未找到')
  if (!student) throw new Error('演示学生账号 student 不存在，无法继续')
  STUDENT_ID = student.id

  // 上一轮跑到一半崩掉时学生密码会停在测试值，这里先复原，
  // 否则后面第一步 login() 会失败在一个与功能无关的地方
  if (!(await tryLogin(DEMO_USER, DEMO_PWD))) {
    const ok = await restoreDemoStudent()
    check('演示密码不可用，已自动复原', ok)
  } else {
    check('演示密码可用', true)
  }

  const cleared = await api(`/users/${STUDENT_ID}/recovery`, 'DELETE')
  check('清除密保（幂等：本来没有也算成功）', cleared !== undefined)

  // ---------------------------------------------------------- 1. 个人资料页
  section('1. 学生个人资料页：账号安全卡片未设置')
  await login(DEMO_USER, DEMO_PWD)
  await page.goto(`${BASE}/profile`, { waitUntil: 'networkidle' })
  const card = page.locator('.recovery-card')
  check('出现「账号安全」卡片', (await card.count()) === 1)
  check('卡片标题是「账号安全」', (await card.innerText()).includes('账号安全'))
  check('显示未设置状态', (await card.innerText()).includes('未设置'))
  check('按钮文案是「去设置」', (await card.locator('button').first().innerText()).includes('去设置'))

  // ---------------------------------------------------------- 2. 设置密保
  section('2. 设置密保问题（含预设标签与校验）')
  await card.locator('button', { hasText: '去设置' }).click()
  const dialog = page.locator('.el-dialog')
  await dialog.waitFor({ state: 'visible', timeout: 10000 })
  check('对话框标题是「设置密保问题」', (await dialog.locator('.el-dialog__title').innerText()).includes('设置密保问题'))
  check('有 4 个输入框（2 问 2 答）', (await dialog.locator('input').count()) === 4)
  check('提示里写明答案不可查看', (await dialog.innerText()).includes('无法查看原文'))

  // 预设标签：点第一个，应填进「密保问题 1」
  await dialog.locator('.preset-tag').first().click()
  const presetText = await dialog.locator('input[placeholder="如 我的宿舍号是多少？"]').inputValue()
  check('点预设标签能填入问题 1', presetText.length > 0, presetText)

  await dialog.locator('input[placeholder="如 我的宿舍号是多少？"]').fill(Q1)
  await dialog.locator('input[placeholder="再设一个不同的问题"]').fill(Q1)
  await dialog.locator('input[placeholder="不区分大小写，忽略首尾空格"]').nth(0).fill(A1)
  await dialog.locator('input[placeholder="不区分大小写，忽略首尾空格"]').nth(1).fill(A2)
  await dialog.locator('button:has-text("保存")').click()
  await page.waitForTimeout(600)
  check('两问相同时保存被拦下', (await dialog.isVisible()) && (await dialog.innerText()).includes('不能相同'))

  await dialog.locator('input[placeholder="再设一个不同的问题"]').fill(Q2)
  await dialog.locator('button:has-text("保存")').click()
  await dialog.waitFor({ state: 'hidden', timeout: 10000 })
  await page.waitForTimeout(500)
  check('保存后卡片显示「已设置」', (await card.innerText()).includes('已设置'))
  check('按钮文案变成「修改密保」', (await card.innerText()).includes('修改密保'))

  const mineFlag = await api('/auth/recovery/mine')
  check('接口 mine 返回 true', mineFlag === true, String(mineFlag))

  // ------------------------------------------------- 3. 忘记密码（第一步）
  section('3. 登录页入口与第一步：用学号换题面')
  await logout()
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  const forgotLink = page.locator('.login-forgot')
  check('登录页有「忘记密码？」入口', (await forgotLink.count()) === 1)
  check('入口文案正确', (await forgotLink.innerText()).includes('忘记密码'))
  await forgotLink.locator('a, .el-link').first().click()
  await page.waitForURL('**/forgot-password', { timeout: 10000 })
  check('跳到 /forgot-password', page.url().includes('/forgot-password'))

  const step1Input = page.locator('input[placeholder="请输入学号或工号"]')
  check('第一步只有一个输入框', (await page.locator('input').count()) === 1)
  await step1Input.fill('not-exist-user')
  await page.locator('button:has-text("下一步")').click()
  await page.waitForTimeout(800)
  check('不存在的学号有明确提示', (await page.locator('.el-alert').first().innerText()).includes('不存在'))

  await step1Input.fill(DEMO_USER)
  await page.locator('button:has-text("下一步")').click()
  await page.waitForTimeout(800)

  // --------------------------------------------- 4. 第二步：题面与计数
  section('4. 第二步：显示题面，答错时剩余次数递减')
  const bodyText = await page.locator('.auth-card').innerText()
  check('显示第一个密保问题题面', bodyText.includes(Q1))
  check('显示第二个密保问题题面', bodyText.includes(Q2))
  check('显示新密码提示（跟着系统配置）', bodyText.includes('位') && bodyText.includes('密码'))
  check('有「换个账号」返回链接', bodyText.includes('换个账号'))

  const ans1 = page.locator('input[placeholder="请输入答案"]').nth(0)
  const ans2 = page.locator('input[placeholder="请输入答案"]').nth(1)
  const pwd1 = page.locator('input[autocomplete="new-password"]').nth(0)
  const pwd2 = page.locator('input[autocomplete="new-password"]').nth(1)

  // 重复密码不一致
  await ans1.fill(A1)
  await ans2.fill(A2)
  await pwd1.fill(NEW_PWD)
  await pwd2.fill(`${NEW_PWD}x`)
  await page.locator('button:has-text("重置密码")').click()
  await page.waitForTimeout(500)
  check('两次密码不一致被拦下', (await page.locator('.auth-card').innerText()).includes('不一致'))

  // 答错一次，看剩余次数
  await ans1.fill('wrong-answer-1')
  await pwd2.fill(NEW_PWD)
  await page.locator('button:has-text("重置密码")').click()
  await page.waitForTimeout(1200)
  const firstErr = await page.locator('.recover-error').innerText()
  check('答错提示含剩余 4 次', firstErr.includes('4 次'), firstErr.replace(/\s+/g, ' '))

  await page.locator('button:has-text("重置密码")').click()
  await page.waitForTimeout(1200)
  const secondErr = await page.locator('.recover-error').innerText()
  check('第二次答错剩余 3 次（计数确实递减）', secondErr.includes('3 次'), secondErr.replace(/\s+/g, ' '))

  // ---------------------------------------------------- 5. 正确答案重置
  section('5. 正确答案重置密码')
  await ans1.fill(A1)
  await ans2.fill(A2)
  await page.locator('button:has-text("重置密码")').click()
  await page.waitForSelector('.el-result', { timeout: 15000 })
  const resultText = await page.locator('.el-result').innerText()
  check('出现成功页', resultText.includes('密码已重置'))
  check('成功页提示其它设备登录已失效', resultText.includes('失效'))

  await page.locator('button:has-text("去登录")').click()
  await page.waitForURL('**/login', { timeout: 10000 })
  await page.fill('input[autocomplete="username"]', DEMO_USER)
  await page.fill('input[autocomplete="current-password"]', NEW_PWD)
  await page.click('button:has-text("登 录")')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
  check('新密码可以登录', !page.url().includes('/login'), page.url())

  // ------------------------------------------------ 6. 管理员清除密保
  section('6. 管理员在用户管理页清除密保')
  await login('admin', 'Admin@123')
  await page.goto(`${BASE}/admin/users`, { waitUntil: 'networkidle' })
  const search = page.locator('input[placeholder="搜索学号/工号或姓名"]')
  await search.fill(DEMO_USER)
  // 搜索框只绑了 @keyup.enter / 「查询」按钮，没有 watch 自动查询
  await search.press('Enter')
  await page.waitForTimeout(1200)

  const row = page.locator('.el-table__row', { hasText: DEMO_USER }).first()
  check('搜索到目标用户行', (await row.count()) === 1)

  // 回归 9c698a5：操作列里 el-dropdown 与 el-button 必须纵向对齐
  const ops = row.locator('td').last()
  const btnY = await ops.locator('button').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().top)))
  const spread = Math.max(...btnY) - Math.min(...btnY)
  check('操作列各按钮纵向对齐（误差 ≤1px）', spread <= 1, `ys=${btnY.join(',')}`)
  check(
    '「更多」与相邻按钮有间距',
    await ops
      .locator('button')
      .evaluateAll((els) => {
        const more = els.find((e) => e.innerText.replace(/\s/g, '') === '更多')
        const prev = els[els.indexOf(more) - 1]
        if (!more || !prev) return false
        return Math.round(more.getBoundingClientRect().left - prev.getBoundingClientRect().right) >= 8
      }),
  )

  await ops.locator('button', { hasText: '更多' }).click()
  await page.waitForTimeout(400)
  const items = await page.locator('.el-dropdown-menu__item:visible').allInnerTexts()
  check('下拉含「清除密保」', items.some((t) => t.includes('清除密保')), items.join('|'))
  check('下拉含「删除账号」', items.some((t) => t.includes('删除账号')))

  await page.locator('.el-dropdown-menu__item:visible', { hasText: '清除密保' }).click()
  const box = page.locator('.el-message-box')
  await box.waitFor({ state: 'visible', timeout: 8000 })

  // 回归 17b7275：确认框必须在视口中心，不能掉到 body 末尾。
  // 注意 Element Plus 2.9 的结构：吊在 .el-message-box 上的样式是 relative，
  // 真正做定位与遮罩的是外层 .el-overlay-message-box（fixed + flex 居中）。
  // 所以断言必须打在 overlay 上 —— 只看 .el-message-box 会误判成「没定位」。
  const geom = await page.evaluate(() => {
    const overlay = document.querySelector('.el-overlay-message-box')
    const boxEl = document.querySelector('.el-message-box')
    if (!overlay || !boxEl) return null
    const os = getComputedStyle(overlay)
    const or = overlay.getBoundingClientRect()
    const r = boxEl.getBoundingClientRect()
    return {
      overlayPos: os.position,
      overlayZ: os.zIndex,
      overlayBg: os.backgroundColor,
      coverW: Math.round(or.width),
      coverH: Math.round(or.height),
      cx: Math.round(r.left + r.width / 2),
      cy: Math.round(r.top + r.height / 2),
      vw: window.innerWidth,
      vh: window.innerHeight,
      boxPos: getComputedStyle(boxEl).position,
    }
  })
  check('遮罩层是 fixed 定位', geom?.overlayPos === 'fixed', String(geom?.overlayPos))
  check(
    '遮罩铺满视口',
    geom?.coverW === geom?.vw && geom?.coverH === geom?.vh,
    `${geom?.coverW}x${geom?.coverH} vs ${geom?.vw}x${geom?.vh}`,
  )
  check(
    '确认框在视口中心（±20px）',
    Math.abs(geom.cx - geom.vw / 2) <= 20 && Math.abs(geom.cy - geom.vh / 2) <= 20,
    `${geom.cx},${geom.cy} vs ${geom.vw / 2},${geom.vh / 2}`,
  )
  check('确认框不在文档流里（不是 relative 硬撑出来）', geom.boxPos !== 'static', geom.boxPos)
  check('确认框文案说明后果', (await box.innerText()).includes('重新设置'))
  check('确认按钮文案是「清除」', (await box.innerText()).includes('清除'))

  await box.locator('.el-message-box__btns button').last().click()
  await page.waitForTimeout(1200)
  const afterClear = await api('/auth/recovery/lookup', 'POST', { username: DEMO_USER })
  check('清除后 lookup 返回 404', (afterClear?.code ?? afterClear) === 404 || afterClear === undefined, String(afterClear?.code))

  // ----------------------------------------------------------- 7. 控制台
  section('7. 控制台')
  // 只留真正的 JS 异常。浏览器的资源加载失败（401/400）不算：
  // 本脚本故意用错误密码登录、故意答错密保，这些请求本来就会返回错误码，
  // 把它们算作「控制台报错」等于要求测试不要去测失败路径。
  const real = consoleErrors.filter(
    (t) =>
      !/favicon|ResizeObserver/.test(t) &&
      !/Failed to load resource: the server responded with a status of \d{3}/.test(t),
  )
  check('无未捕获 JS 异常', real.length === 0, real.slice(0, 3).join(' | '))
} catch (e) {
  fail += 1
  console.log(`\n[FATAL] ${e instanceof Error ? e.message : String(e)}`)
} finally {
  // 无论成败都把演示学生的密码与密保复原（见 restoreDemoStudent 的注释）
  section('复原：把演示学生的密码与密保恢复成初始状态')
  try {
    const ok = await restoreDemoStudent()
    check('演示学生已复原（密码 Student@123、无密保）', ok)
  } catch (e) {
    check('演示学生已复原', false, e instanceof Error ? e.message : String(e))
  }
  await browser.close()
  console.log(`\n结果：${pass} 通过，${fail} 失败（合计 ${pass + fail}）`)
  process.exit(fail === 0 ? 0 : 1)
}