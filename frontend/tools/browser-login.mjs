// 真实浏览器点击验证：用 Chrome DevTools Protocol 驱动无头 Chrome，
// 在登录页真实填表、点「登录」，然后断言落到 Dashboard 且渲染出用户姓名。
//
// 为什么需要它：tools/contract-test.mjs 与 e2e-journey.mjs 只验证「HTTP 契约」，
// 证明不了 Vue 组件、路由守卫、Pinia store、axios 拦截器串起来是否真的能跑。
// 这个脚本补上最后一环 —— 真浏览器、真 DOM、真点击。
//
// 用法（需先启动后端 8080 与 npm run dev 5173）：
//   node tools/browser-login.mjs
//
// 依赖：仅 Node 内置能力（node:child_process / 全局 WebSocket，Node >= 22）。
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resetDemoAccounts } from './demo-fixture.mjs'

const APP = process.env.APP_URL || 'http://localhost:5173'
const PORT = 9333

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
]

let pass = 0
let fail = 0
const failures = []
function check(label, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  [PASS] ${label}${detail ? ' — ' + detail : ''}`)
  } else {
    fail++
    failures.push(label)
    console.log(`  [FAIL] ${label}${detail ? ' — ' + detail : ''}`)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ---------------------------------------------------------------- CDP 最小客户端
class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP 超时: ${method}`))
        }
      }, 20000)
    })
  }
  /** 在页面里求值，返回 JSON 化结果 */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true
    })
    if (r.exceptionDetails) {
      throw new Error(`页面求值异常: ${r.exceptionDetails.text} ${r.result?.description || ''}`)
    }
    return r.result.value
  }
}

// ---------------------------------------------------------------- 启动 Chrome
const exe = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!exe) {
  console.error('未找到 Chrome/Edge，跳过浏览器验证')
  process.exit(2)
}

const profile = mkdtempSync(join(tmpdir(), 'g14-cdp-'))
console.log(`浏览器: ${exe}`)
console.log(`目标:   ${APP}\n`)

const chrome = spawn(
  exe,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--no-first-run',
    '--disable-extensions',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`,
    '--window-size=1440,900',
    'about:blank'
  ],
  { stdio: 'ignore' }
)

let cdp
try {
  // 等 CDP 端口起来
  let target = null
  for (let i = 0; i < 40; i++) {
    await sleep(300)
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`)
      const list = await res.json()
      target = list.find((t) => t.type === 'page')
      if (target?.webSocketDebuggerUrl) break
    } catch {
      /* 还没起来 */
    }
  }
  if (!target?.webSocketDebuggerUrl) throw new Error('无法连接 Chrome 调试端口')

  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('WebSocket 连接失败')), { once: true })
  })
  cdp = new Cdp(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')

  // 前置：本脚本会真实改密（场景 4），不先复位的话第二次跑会因旧密码失效而失败。
  console.log('前置：把演示账号复位到首次开通状态…')
  await resetDemoAccounts(`${APP}/api`, { quiet: false })
  console.log('')

  // ------------------------------------------------------------ 场景 1：登录页渲染
  console.log('=== 场景 1：打开登录页，确认 Vue 真的挂载了 ===')
  await cdp.send('Page.navigate', { url: `${APP}/login` })
  await sleep(2500)

  const mounted = await cdp.eval(`!!document.querySelector('#app')?.children.length`)
  check('Vue 应用已挂载（#app 有子节点）', mounted === true)

  const title = await cdp.eval(`document.querySelector('.brand-title, .title, h1, h2')?.textContent?.trim() || ''`)
  check('渲染出品牌/标题文案', title.length > 0, `title=${title}`)

  const inputs = await cdp.eval(`document.querySelectorAll('input').length`)
  check('登录表单有输入框', inputs >= 2, `input 数量=${inputs}`)

  // ------------------------------------------------------------ 场景 2：学生登录
  console.log('\n=== 场景 2：学生 2021001 真实填表 + 点击登录 ===')
  // 用原生 setter 触发 Vue 的 v-model（直接赋 value 不会触发 input 事件）
  //
  // 选择器注意：登录页的 input 顺序是 [3 个角色 radio, 账号, 密码, 记住账号 checkbox]。
  // 不能用 `type !== 'password'` 找账号 —— 那会命中排在最前的 radio。
  // 统一按 Element Plus 的 `input.el-input__inner` + type 定位。
  await cdp.eval(`
    (() => {
      const setVal = (el, v) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
        setter.call(el, v)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
      }
      const inners = [...document.querySelectorAll('input.el-input__inner')]
      const accountEl = inners.find(i => i.type === 'text')
      const pwdEl = inners.find(i => i.type === 'password')
      setVal(accountEl, '2021001')
      setVal(pwdEl, 'Init@1234')
      return true
    })()
  `)
  const filled = await cdp.eval(`
    (() => {
      const inners = [...document.querySelectorAll('input.el-input__inner')]
      const a = inners.find(i => i.type === 'text')
      const p = inners.find(i => i.type === 'password')
      return { account: a?.value, pwdLen: p?.value?.length || 0 }
    })()
  `)
  check('账号已填入表单', filled.account === '2021001', `account=${filled.account}`)
  check('密码已填入表单', filled.pwdLen === 9, `pwdLen=${filled.pwdLen}`)

  // 点登录按钮
  const clicked = await cdp.eval(`
    (() => {
      const btn = [...document.querySelectorAll('button')].find(b => /登\\s*录/.test(b.textContent))
      if (!btn) return false
      btn.click()
      return true
    })()
  `)
  check('找到并点击了「登录」按钮', clicked === true)

  await sleep(3000)

  // ------------------------------------------------------------ 场景 3：落到强制改密页
  console.log('\n=== 场景 3：首次登录被路由守卫送到「修改初始密码」页 ===')
  const url3 = await cdp.eval(`location.pathname`)
  check('已离开登录页', !url3.includes('/login'), `path=${url3}`)
  // 注意：不能用 body.innerText 匹配「修改初始密码」—— 登录页左侧品牌面板
  // 就写着「首次登录强制修改初始密码」，会假阳性。改用路由路径 + 卡片标题。
  check('路由已切到 /force-password', url3.includes('force-password'), `path=${url3}`)
  const heading3 = await cdp.eval(`document.querySelector('.head h3')?.textContent?.trim() || ''`)
  check('渲染出强制改密卡片标题', heading3 === '修改初始密码', `h3=${heading3}`)

  const tokenStored = await cdp.eval(`!!localStorage.getItem('access_token')`)
  check('令牌已落盘到 localStorage', tokenStored === true)

  const userStored = await cdp.eval(`
    (() => {
      try { return JSON.parse(localStorage.getItem('user_info') || 'null') } catch { return null }
    })()
  `)
  check('用户信息已落盘且含 name（normalizeUser 生效）',
    typeof userStored?.name === 'string' && userStored.name.length > 0,
    `name=${userStored?.name} role=${userStored?.role}`)
  check('落盘角色是 STUDENT（后端查库识别）', userStored?.role === 'STUDENT')

  // ------------------------------------------------------------ 场景 4：真实改密
  console.log('\n=== 场景 4：在页面上真实改密，验证新令牌被落盘 ===')
  const oldToken = await cdp.eval(`localStorage.getItem('access_token')`)
  await cdp.eval(`
    (() => {
      const setVal = (el, v) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
        setter.call(el, v)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
      }
      const pwds = [...document.querySelectorAll('input.el-input__inner[type=password]')]
      const vals = ['Init@1234', 'Student@456', 'Student@456']
      pwds.slice(0, 3).forEach((el, i) => setVal(el, vals[i]))
      return pwds.length
    })()
  `)
  await sleep(400)
  const submitted = await cdp.eval(`
    (() => {
      const btn = [...document.querySelectorAll('button')].find(b => /确认修改/.test(b.textContent))
      if (!btn) return false
      btn.click()
      return true
    })()
  `)
  check('找到并点击了改密提交按钮', submitted === true)
  await sleep(3500)

  const newToken = await cdp.eval(`localStorage.getItem('access_token')`)
  check('改密后 localStorage 里的令牌已换新',
    typeof newToken === 'string' && newToken && newToken !== oldToken)
  const path4 = await cdp.eval(`location.pathname`)
  check('改密后离开强制改密页', !path4.includes('force-password'), `path=${path4}`)

  // ------------------------------------------------------------ 场景 5：改密后能正常访问业务页
  console.log('\n=== 场景 5：改密后首页正常渲染（令牌有效） ===')
  const dashText = await cdp.eval(`document.body.innerText`)
  check('页面渲染出用户姓名（不是 undefined）',
    /学生一/.test(dashText), `含"学生一"=${/学生一/.test(dashText)}`)
  check('页面未出现 undefined 字样（字段映射正确）', !/undefined/.test(dashText))

  const greeting = await cdp.eval(`
    document.querySelector('.hello, .hero-name, .name')?.textContent?.trim() || ''
  `)
  check('欢迎语取到了姓名', greeting.length > 0 && !greeting.includes('undefined'), `greeting=${greeting}`)

  // 截图留证。
  // 放在 tools/artifacts/ 而不是 dist/ —— dist 会被 `npm run build` 整个清空，
  // 证据图不能依赖构建产物目录存活。
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
  const { writeFileSync, mkdirSync } = await import('node:fs')
  mkdirSync('tools/artifacts', { recursive: true })
  const out = 'tools/artifacts/browser-after-login.png'
  writeFileSync(out, Buffer.from(shot.data, 'base64'))
  console.log(`\n截图已保存: ${out}`)
} catch (e) {
  fail++
  failures.push(`浏览器驱动异常: ${e.message}`)
  console.error(`\n[ERROR] ${e.message}`)
} finally {
  try {
    cdp?.ws?.close()
  } catch {
    /* ignore */
  }
  chrome.kill()
  await sleep(500)
  try {
    rmSync(profile, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
}

console.log('\n===============================')
console.log(`  浏览器点击验收: ${pass} PASS / ${fail} FAIL`)
console.log('===============================')
if (failures.length) {
  console.log('失败用例:')
  failures.forEach((f) => console.log('  - ' + f))
}
process.exitCode = fail ? 1 : 0
