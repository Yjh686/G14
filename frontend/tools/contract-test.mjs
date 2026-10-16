// 前端契约联调测试：经由 Vite dev server 的 /api 代理打到真实 Spring Boot 后端。
// 每个请求的 URL / 方法 / 请求体形状都与 frontend/src/api/auth.js 完全一致，
// 用来证明「前端 API 层 ↔ 后端控制器」的契约吻合，而不是只测后端自身。
// 运行前需：后端 8080 在跑 + `npm run dev` 的 5173 在跑。
const BASE = process.env.PROXY || 'http://localhost:5173/api'

import { resetDemoAccounts } from './demo-fixture.mjs'

let pass = 0
let fail = 0
const failures = []

function check(label, ok, detail = '') {
  if (ok) {
    pass++
    console.log(`  [PASS] ${label} ${detail}`)
  } else {
    fail++
    failures.push(label)
    console.log(`  [FAIL] ${label} ${detail}`)
  }
}

async function call(method, path, { body, token } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8'
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    /* 非 JSON 响应 */
  }
  return { status: res.status, body: json, raw: text }
}

const section = (t) => console.log(`\n=== ${t} ===`)

// ---------------------------------------------------------------- 前置复位
// 本脚本会真实改密码，跑完账号就停在「已改密」状态。不先复位的话，
// 第二次运行会因旧密码失效而整片 40104/40101（看起来像回归，其实是脏状态）。
console.log('前置：把演示账号复位到首次开通状态…')
await resetDemoAccounts(BASE, { quiet: false })

// ---------------------------------------------------------------- 登录
section('1. POST /auth/login —— 前端 login() 的形状（仅 account+password）')

// 复用后端已有的引导管理员；老师/学生账号由下面用例通过管理接口开通
const adminLogin = await call('POST', '/auth/login', {
  body: { account: 'admin', password: 'Admin@123' }
})
check('admin 登录成功', adminLogin.status === 200 && adminLogin.body.code === 200,
  `HTTP=${adminLogin.status} code=${adminLogin.body?.code}`)
const adminToken = adminLogin.body?.data?.token
check('响应含 data.token', typeof adminToken === 'string' && adminToken.split('.').length === 3)
check('data.user.realName 存在（前端 normalizeUser 依赖它）',
  typeof adminLogin.body?.data?.user?.realName === 'string',
  `realName=${adminLogin.body?.data?.user?.realName}`)
check('data.mustChangePassword 存在（Login.vue:71 依赖它决定跳转）',
  typeof adminLogin.body?.data?.mustChangePassword === 'boolean',
  `mustChangePassword=${adminLogin.body?.data?.mustChangePassword}`)
check('data.user 不含 passwordHash', !('passwordHash' in (adminLogin.body?.data?.user || {})))

section('2. 登录失败：Login.vue 靠拦截器展示 message')
const badLogin = await call('POST', '/auth/login', {
  body: { account: 'admin', password: 'wrong-password' }
})
check('错误密码 → HTTP 401 + code 40104', badLogin.status === 401 && badLogin.body.code === 40104,
  `HTTP=${badLogin.status} code=${badLogin.body?.code}`)
check('message 可直接展示给用户', badLogin.body?.message === '账号或密码错误',
  `message=${badLogin.body?.message}`)

// ------------------------------------------------------- 管理员开通账号
section('3. POST /admin/users —— 前端 adminCreateUser() 显式传 null 的字段')

async function ensureUser(account, realName, role, phone) {
  // 与 frontend/src/api/auth.js:56-62 完全一致的请求体形状
  const body = { account, realName, role, phone: phone ? phone : null, initialPassword: null }
  const r = await call('POST', '/admin/users', { body, token: adminToken })
  if (r.body?.code === 200) return { created: true, id: r.body.data.id }
  if (r.body?.code === 40901) return { created: false, existing: true, raw: r }
  return { created: false, raw: r }
}

const t1 = await ensureUser('T1001', '老师甲', 'TEACHER', '13800000001')
check('开通老师（phone 传字符串，initialPassword 传 null）',
  t1.created || t1.existing,
  t1.created ? `id=${t1.id}` : `HTTP=${t1.raw.status} code=${t1.raw.body?.code}`)
const s1 = await ensureUser('2021001', '学生一', 'STUDENT', null)
check('开通学生（phone 传 null，initialPassword 传 null）',
  s1.created || s1.existing,
  s1.created ? `id=${s1.id}` : `HTTP=${s1.raw.status} code=${s1.raw.body?.code}`)
check('后端接受前端传来的显式 null（未被 @Valid 拒绝）', t1.created || t1.existing)

// ---------------------------------------------------------- 老师/学生登录
section('4. 老师/学生登录 + 强制改密标记')

const t1Login = await call('POST', '/auth/login', { body: { account: 'T1001', password: 'Init@1234' } })
check('老师登录成功', t1Login.status === 200 && t1Login.body.code === 200,
  `code=${t1Login.body?.code}`)
check('role=TEACHER（前端 role.js 的 ROLE.TEACHER 能匹配）',
  t1Login.body?.data?.user?.role === 'TEACHER', `role=${t1Login.body?.data?.user?.role}`)
check('mustChangePassword=true → 前端会跳 ForcePassword',
  t1Login.body?.data?.mustChangePassword === true)
let teacherToken = t1Login.body?.data?.token

const s1Login = await call('POST', '/auth/login', { body: { account: '2021001', password: 'Init@1234' } })
check('学生登录成功 role=STUDENT', s1Login.body?.data?.user?.role === 'STUDENT',
  `role=${s1Login.body?.data?.user?.role}`)
let studentToken = s1Login.body?.data?.token

// ------------------------------------------------------------- 强制改密
section('5. POST /auth/password —— 前端 changePassword() 的形状')

const tChange = await call('POST', '/auth/password', {
  body: { oldPassword: 'Init@1234', newPassword: 'Teacher@123' },
  token: teacherToken
})
check('老师改密成功', tChange.status === 200 && tChange.body.code === 200,
  `code=${tChange.body?.code}`)
check('响应 data.token 是新令牌（前端 store 必须替换，否则后续 40103）',
  typeof tChange.body?.data?.token === 'string' && tChange.body.data.token !== teacherToken)
check('响应 data.user.realName 存在（store.persist 依赖）',
  typeof tChange.body?.data?.user?.realName === 'string')
check('改密后 mustChangePassword=false', tChange.body?.data?.mustChangePassword === false)
const newTeacherToken = tChange.body?.data?.token

const oldTokenUse = await call('GET', '/auth/me', { token: teacherToken })
check('旧令牌立即失效 → 401/40103（前端须换新令牌）',
  oldTokenUse.status === 401 && oldTokenUse.body.code === 40103,
  `HTTP=${oldTokenUse.status} code=${oldTokenUse.body?.code}`)
const newTokenUse = await call('GET', '/auth/me', { token: newTeacherToken })
check('新令牌可用', newTokenUse.status === 200 && newTokenUse.body.code === 200)

const sChange = await call('POST', '/auth/password', {
  body: { oldPassword: 'Init@1234', newPassword: 'Student@123' },
  token: studentToken
})
check('学生改密成功', sChange.body?.code === 200, `code=${sChange.body?.code}`)
const newStudentToken = sChange.body?.data?.token

section('6. 改密错误码 —— 页面靠 e.code 区分提示')
const wrongOld = await call('POST', '/auth/password', {
  body: { oldPassword: 'nope-nope', newPassword: 'Whatever@123' }, token: newStudentToken
})
check('原密码错 → 400/40002', wrongOld.status === 400 && wrongOld.body.code === 40002,
  `code=${wrongOld.body?.code}`)
const samePwd = await call('POST', '/auth/password', {
  body: { oldPassword: 'Student@123', newPassword: 'Student@123' }, token: newStudentToken
})
check('新旧相同 → 400/40003', samePwd.status === 400 && samePwd.body.code === 40003,
  `code=${samePwd.body?.code}`)
const weakPwd = await call('POST', '/auth/password', {
  body: { oldPassword: 'Student@123', newPassword: '12345678' }, token: newStudentToken
})
check('弱口令 → 400/40001', weakPwd.status === 400 && weakPwd.body.code === 40001,
  `code=${weakPwd.body?.code}`)

// ------------------------------------------------------------- 强制改密拦截
section('7. 40302 强制改密拦截（前端 request.js 会跳 ForcePassword）')
const t2 = await ensureUser('T1002', '老师乙', 'TEACHER', '13800000002')
const t2Login = await call('POST', '/auth/login', { body: { account: 'T1002', password: 'Init@1234' } })
const t2Token = t2Login.body?.data?.token
const blocked = await call('GET', '/auth/teacher-only-demo', { token: t2Token })
check('改密前访问业务接口 → 403/40302',
  blocked.status === 403 && blocked.body.code === 40302,
  `HTTP=${blocked.status} code=${blocked.body?.code} msg=${blocked.body?.message}`)
const meAllowed = await call('GET', '/auth/me', { token: t2Token })
check('/auth/me 在改密白名单内，放行', meAllowed.status === 200)

// ------------------------------------------------------------ 管理端列表
section('8. GET /admin/users —— 前端 adminListUsers() 用 pageNum/pageSize')
const list = await call('GET', '/admin/users?pageNum=1&pageSize=10', { token: adminToken })
check('列表成功', list.body?.code === 200, `code=${list.body?.code}`)
check('响应字段 list/total/pageNum/pageSize（UserManage.vue 读取）',
  Array.isArray(list.body?.data?.list) && typeof list.body?.data?.total === 'number' &&
  list.body?.data?.pageNum === 1 && list.body?.data?.pageSize === 10,
  `pageNum=${list.body?.data?.pageNum} pageSize=${list.body?.data?.pageSize} total=${list.body?.data?.total}`)
check('列表项含 realName（前端 normalizeUsers 依赖）',
  typeof list.body?.data?.list?.[0]?.realName === 'string',
  `realName=${list.body?.data?.list?.[0]?.realName}`)
check('列表项手机号已脱敏', (list.body?.data?.list || []).some((u) => /^\d{3}\*{4}\d{4}$/.test(u.phone || '')))
check('列表项不含 passwordHash', !('passwordHash' in (list.body?.data?.list?.[0] || {})))
const filtered = await call('GET', '/admin/users?keyword=T1001&role=TEACHER&pageNum=1&pageSize=10', { token: adminToken })
check('keyword+role 过滤生效', filtered.body?.data?.total === 1, `total=${filtered.body?.data?.total}`)

// --------------------------------------------------------- 重置密码 / 状态
section('9. POST /admin/users/{id}/password/reset —— 前端用 POST 不是 PUT')
const t2id = (await call('GET', '/admin/users?keyword=T1002', { token: adminToken })).body.data.list[0].id
const reset = await call('POST', `/admin/users/${t2id}/password/reset`, {
  body: { newPassword: 'Reset@2024' }, token: adminToken
})
check('重置成功（前端 adminResetPassword 用 POST）',
  reset.status === 200 && reset.body.code === 200, `HTTP=${reset.status} code=${reset.body?.code}`)
const putReset = await call('PUT', `/admin/users/${t2id}/password/reset`, {
  body: { newPassword: 'Reset@2024' }, token: adminToken
})
// 后端对「方法不匹配」统一转成 400/40000（GlobalExceptionHandler），而不是裸 405
check('用 PUT 会被拒（证明前端必须用 POST）',
  putReset.status === 400 && putReset.body?.code === 40000,
  `PUT → HTTP=${putReset.status} code=${putReset.body?.code} msg=${putReset.body?.message}`)
const t2Relogin = await call('POST', '/auth/login', { body: { account: 'T1002', password: 'Reset@2024' } })
check('重置后新密码可登录且必须改密', t2Relogin.body?.code === 200 && t2Relogin.body?.data?.mustChangePassword === true)

section('10. PUT /admin/users/{id}/status?enabled= —— 前端 adminUpdateStatus()')
const s2 = await ensureUser('2021002', '学生二', 'STUDENT', null)
const s2Login = await call('POST', '/auth/login', { body: { account: '2021002', password: 'Init@1234' } })
const s2Token = s2Login.body?.data?.token
const s2id = (await call('GET', '/admin/users?keyword=2021002', { token: adminToken })).body.data.list[0].id
const disable = await call('PUT', `/admin/users/${s2id}/status?enabled=false`, { token: adminToken })
check('禁用成功', disable.body?.code === 200, `code=${disable.body?.code}`)
const disabledUse = await call('GET', '/auth/me', { token: s2Token })
check('禁用后在线令牌失效 → 403/40301（request.js 会登出并回登录页）',
  disabledUse.status === 403 && disabledUse.body.code === 40301,
  `HTTP=${disabledUse.status} code=${disabledUse.body?.code}`)
const enable = await call('PUT', `/admin/users/${s2id}/status?enabled=true`, { token: adminToken })
check('重新启用成功', enable.body?.code === 200, `code=${enable.body?.code}`)

// ------------------------------------------------------------------ 越权
section('11. 越权拦截（前端路由守卫 + 后端接口级双重防护）')
const studentAdmin = await call('GET', '/admin/users', { token: newStudentToken })
check('学生调管理接口 → 403/40300（不是 40302，说明已完成改密）',
  studentAdmin.status === 403 && studentAdmin.body.code === 40300,
  `HTTP=${studentAdmin.status} code=${studentAdmin.body?.code} msg=${studentAdmin.body?.message}`)
const studentTeacher = await call('GET', '/auth/teacher-only-demo', { token: newStudentToken })
check('学生调老师接口 → 403/40300',
  studentTeacher.status === 403 && studentTeacher.body.code === 40300,
  `code=${studentTeacher.body?.code}`)
const teacherAdmin = await call('GET', '/admin/users', { token: newTeacherToken })
check('老师调管理接口 → 403/40300', teacherAdmin.status === 403 && teacherAdmin.body.code === 40300)
const adminTeacher = await call('GET', '/auth/teacher-only-demo', { token: adminToken })
check('管理员拥有全部权限（@RequireRole 含 ADMIN）', adminTeacher.body?.code === 200,
  `code=${adminTeacher.body?.code}`)

// ------------------------------------------------------------------ 登出
section('12. POST /auth/logout —— MainLayout.handleLogout()')
const logoutRes = await call('POST', '/auth/logout', { token: newStudentToken })
check('登出成功', logoutRes.body?.code === 200, `code=${logoutRes.body?.code}`)
const afterLogout = await call('GET', '/auth/me', { token: newStudentToken })
check('登出后令牌立即失效 → 401/40103',
  afterLogout.status === 401 && afterLogout.body.code === 40103,
  `HTTP=${afterLogout.status} code=${afterLogout.body?.code}`)

// ------------------------------------------------------------- 无令牌
section('13. 无令牌（路由守卫失败时的兜底）')
const noToken = await call('GET', '/auth/me')
check('无令牌 → 401/40101', noToken.status === 401 && noToken.body.code === 40101,
  `HTTP=${noToken.status} code=${noToken.body?.code}`)

console.log(`\n===============================`)
console.log(`  前端契约联调: ${pass} PASS / ${fail} FAIL`)
console.log(`===============================\n`)
if (fail) {
  console.log('失败用例:')
  failures.forEach((f) => console.log(`  - ${f}`))
}
// 用 exitCode 而不是 process.exit()：undici 连接池仍持有未关闭句柄，
// 强制 exit 会触发 libuv "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)"。
process.exitCode = fail ? 1 : 0
