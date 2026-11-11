/**
 * 前端 E2E 冒烟（系统配置）：
 * 站点名改完登录页/侧边栏/浏览器标题立即跟着变、公告渲染、密码规则文案跟随配置、
 * 内置项不可删、自定义项增删、公开配置预览不含私有项、教师无权访问。
 *
 * 用法（需先启动后端 8080 与前端 5173）：
 *   node scripts/e2e-config.mjs
 *
 * 为什么把「改站点名」放在最前面断言登录页：这是整个 FR-CFG 里唯一
 * 用户肉眼能验证的闭环 —— 管理员在后台改一个字，未登录的登录页上就该变。
 * 只断言接口返回 200 是测不出这条链路的（前端可能缓存了旧值）。
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
const SITE_NAME = `E2E 站点 ${STAMP}`
const ANNOUNCEMENT = `E2E 公告 ${STAMP}`
const CUSTOM_KEY = `e2e.extra${STAMP}`

/** 读全量配置，拍平成 key → 值，便于断言 */
async function configMap() {
  const groups = await api('/config')
  // 未登录/无权限时后端返回的是 {code,msg,...}，不是分组 Map；
  // 直接 Object.values 会得到数字和字符串，报一个莫名其妙的 "list is not iterable"
  if (!groups || typeof groups !== 'object' || groups.code) {
    throw new Error(`读取配置失败（可能未登录）：${JSON.stringify(groups).slice(0, 120)}`)
  }
  const map = {}
  for (const list of Object.values(groups)) {
    if (!Array.isArray(list)) continue
    for (const c of list) map[c.configKey] = c
  }
  return map
}

/** 只把要改的几项提交上去，其余项原样回传（PUT 是全量语义，但传一个也算改） */
async function saveValues(pairs) {
  const map = await configMap()
  const items = Object.entries(pairs).map(([k, v]) => ({
    configKey: k,
    configValue: v,
    configType: map[k].configType,
    groupName: map[k].groupName,
    description: map[k].description ?? undefined,
    publicVisible: map[k].publicVisible,
  }))
  return api('/config', 'PUT', items)
}

let customId = null
let origin = {}

try {
  section('1. 管理员登录并备份原配置')
  await login('admin', 'Admin@123')
  check('管理员登录成功', !page.url().includes('/login'))

  const before = await configMap()
  origin = {
    'site.name': before['site.name']?.configValue ?? '校园问卷系统',
    'site.announcement': before['site.announcement']?.configValue ?? '',
    'security.password.min-length':
      before['security.password.min-length']?.configValue ?? '8',
    'security.password.require-special':
      before['security.password.require-special']?.configValue ?? '0',
  }
  check('读到内置配置项', Object.keys(before).length >= 10, `${Object.keys(before).length} 项`)
  check(
    '默认密码不下发到公开接口（列表里打码）',
    before['security.default-password']?.masked === true,
  )

  section('2. 系统配置页渲染')
  await page.goto(`${BASE}/admin/config`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.cfg-row', { timeout: 15000 })
  const rows = await page.locator('.cfg-row').count()
  check('配置项渲染为行', rows >= 9, `${rows} 行`)
  check('侧边栏含「系统配置」', (await page.locator('.layout-menu').innerText()).includes('系统配置'))
  check('分组标题含站点信息与安全策略', (await page.locator('.group-title').allInnerTexts()).some((t) => t.includes('站点信息')))
  check('侧边栏品牌显示站点名', (await page.locator('.brand-text').innerText()).includes(origin['site.name']))

  section('3. 改站点名 → 登录页与标题立即跟随')
  await saveValues({ 'site.name': SITE_NAME })
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForSelector('.brand-text')
  check('侧边栏品牌已更新', (await page.locator('.brand-text').innerText()).trim() === SITE_NAME)
  check(
    '浏览器标题已更新',
    (await page.title()).includes(SITE_NAME),
    await page.title(),
  )

  await logout()
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  check('未登录登录页显示新站点名', (await page.locator('.auth-brand').innerText()).trim() === SITE_NAME)
  check('未登录登录页标题含新站点名', (await page.title()).includes(SITE_NAME))

  section('4. 公告渲染')
  // 第 3 节结尾为了验证「未登录也能看到站点名」已经登出，这里先登回管理员
  await login('admin', 'Admin@123')
  await saveValues({ 'site.announcement': ANNOUNCEMENT })
  await logout()
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.login-notice', { timeout: 10000 })
  check(
    '登录页显示公告',
    (await page.locator('.login-notice').innerText()).includes(ANNOUNCEMENT),
  )
  await login('admin', 'Admin@123')
  await saveValues({ 'site.announcement': '' })
  await logout()
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  check('公告清空后不显示公告条', (await page.locator('.login-notice').count()) === 0)

  section('5. 密码规则跟随配置')
  await login('admin', 'Admin@123')
  await saveValues({
    'security.password.min-length': '12',
    'security.password.require-special': '1',
  })
  await logout()
  await page.goto(`${BASE}/register`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.field-tip')
  // 注册页的密码提示文案应来自配置，而不是写死的 8~32
  const ruleText = await page.locator('.field-tip').first().innerText()
  check('注册页提示 12~32 位', ruleText.includes('12~32'), ruleText)
  check('注册页提示包含特殊字符要求', ruleText.includes('特殊字符'), ruleText)

  // 8 位密码应被前端拦下（配置要求 12 位）——记的是提示文案，不是「有没有红字」，
  // 因为确认密码那栏也会因为两次不一致而报错，光看红字数量会误判
  await page.fill('input[autocomplete="username"]', `e2e${STAMP}`)
  await page.fill('input[placeholder="用于审核与教师查看"]', 'E2E 用户')
  await page.fill('input[autocomplete="new-password"]', 'abc12345')
  await page.locator('input[placeholder="请再次输入密码"]').fill('abc12345')
  await page.locator('button:has-text("提交注册申请")').click()
  await page.waitForTimeout(800)
  const errText = await page.locator('.el-form-item__error').allInnerTexts()
  check(
    '8 位密码被拦下并提示 12 位',
    errText.some((t) => t.includes('12~32')),
    errText.join(' | '),
  )

  section('6. 恢复密码规则')
  await login('admin', 'Admin@123')
  await saveValues(origin)
  const restored = await configMap()
  check('min-length 已复原', restored['security.password.min-length'].configValue === origin['security.password.min-length'])
  check('require-special 已复原', restored['security.password.require-special'].configValue === origin['security.password.require-special'])

  section('7. 内置项不可删')
  const delBuiltin = await api(`/config/${restored['site.name'].id}`, 'DELETE')
  check('删除内置项被拒', delBuiltin?.code === 400, delBuiltin?.msg ?? '')

  section('8. 自定义项增删')
  const created = await api('/config', 'POST', {
    configKey: CUSTOM_KEY,
    configValue: 'v1',
    configType: 'STRING',
    groupName: 'OTHER',
    description: 'E2E 自定义项',
    publicVisible: true,
  })
  check('创建自定义项成功', created?.configKey === CUSTOM_KEY, JSON.stringify(created).slice(0, 60))
  customId = created?.id ?? null
  check('自定义项非内置', created?.builtin === false)

  const dup = await api('/config', 'POST', {
    configKey: CUSTOM_KEY,
    configValue: 'v2',
    configType: 'STRING',
    groupName: 'OTHER',
  })
  check('重复创建返回 409', dup?.code === 409, dup?.msg ?? '')

  // 第 6 节登录后停在工作台，这里必须先回到配置页再刷新
  await page.goto(`${BASE}/admin/config`, { waitUntil: 'networkidle' })
  await page.waitForSelector('.cfg-row', { timeout: 15000 })
  check('新项出现在页面', (await page.locator('.cfg-row').filter({ hasText: CUSTOM_KEY }).count()) === 1)

  section('9. 公开配置预览不含私有项')
  await page.click('button:has-text("查看公开配置")')
  await page.waitForSelector('.el-drawer', { timeout: 10000 })
  const drawerText = await page.locator('.el-drawer').innerText()
  check('预览含站点名', drawerText.includes('site.name'))
  check('预览含自定义公开项', drawerText.includes(CUSTOM_KEY))
  check('预览不含默认密码', !drawerText.includes('security.default-password'))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)

  section('10. 教师与学生无权访问')
  await logout()
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/admin/config`, { waitUntil: 'networkidle' })
  await page.waitForURL((url) => url.pathname.includes('forbidden'), { timeout: 15000 })
  check('教师访问配置页被拦到 403', page.url().includes('forbidden'))
  const teacherMenu = await page.locator('.layout-menu').innerText()
  check('教师菜单无系统配置', !teacherMenu.includes('系统配置'))
  const teacherApi = await api('/config')
  check('教师调配置接口 403', teacherApi?.code === 403, teacherApi?.msg ?? '')

  await logout()
  await login('student', 'Student@123')
  const studentApi = await api('/config')
  check('学生调配置接口 403', studentApi?.code === 403, studentApi?.msg ?? '')
  const studentPublic = await api('/config/public')
  check('学生可读公开配置', typeof studentPublic === 'object' && studentPublic['site.name'] !== undefined)

  section('11. 无控制台报错')
  // 403 是通过 localStorage 直调接口造出来的，会打一条 Failed to load resource，属预期噪音
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
  // 清理：还原配置 + 删自定义项。必须在登录态下做，所以重新登一次管理员
  try {
    await login('admin', 'Admin@123')
    if (Object.keys(origin).length > 0) await saveValues(origin)
    if (customId) {
      const r = await api(`/config/${customId}`, 'DELETE')
      console.log(`  清理自定义项 ${CUSTOM_KEY} -> ${r?.code ?? '已删除'}`)
    }
    const finalMap = await configMap()
    console.log(`  复核 site.name = ${finalMap['site.name']?.configValue}`)
  } catch (e) {
    console.log(`  清理失败：${e}`)
  }
  await browser.close()
  console.log('\n' + '-'.repeat(40))
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  process.exit(fail === 0 ? 0 : 1)
}
