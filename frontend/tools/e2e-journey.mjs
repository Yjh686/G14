// 最终验收：模拟浏览器里用户真实走一遍的链路（经由 Vite dev server 的 /api 代理）。
// 与 contract-test.mjs 的区别：这里按「一个用户从登录到改密到访问业务接口」的顺序串起来，
// 验证状态在前后端之间确实流转正确，而不是逐条孤立地打接口。
const BASE = process.env.PROXY || 'http://localhost:5173/api'

import { resetDemoAccounts } from './demo-fixture.mjs'

let pass = 0
let fail = 0
const failures = []
function check(label, ok, detail = '') {
  if (ok) { pass++; console.log(`  [PASS] ${label}${detail ? ' — ' + detail : ''}`) }
  else { fail++; failures.push(label); console.log(`  [FAIL] ${label}${detail ? ' — ' + detail : ''}`) }
}

async function call(method, path, { body, token } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8'
  const res = await fetch(`${BASE}${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* ignore */ }
  return { status: res.status, body: json }
}
const section = (t) => console.log(`\n=== ${t} ===`)

// ---------------------------------------------------------------- 前置复位
// 本脚本会真实改密码（场景 1 学生改密、场景 2 老师改密），跑完账号就停在
// 「已改密」状态。不先复位的话第二次运行会整片 40104/40101。
// 需要先跑 `node tools/seed-demo.mjs` 建账号、`tools/seed-demo.sql` 建班级数据。
console.log('前置：把演示账号复位到首次开通状态…')
await resetDemoAccounts(BASE, { quiet: false })

// ---------------------------------------------------------------- 场景 1：学生首次登录
section('场景 1：学生首次登录 → 强制改密 → 用新令牌访问业务接口')
const sLogin = await call('POST', '/auth/login', { body: { account: '2021001', password: 'Init@1234' } })
check('登录成功', sLogin.body?.code === 200, `code=${sLogin.body?.code}`)
check('role=STUDENT（后端查库识别，非前端提交）', sLogin.body?.data?.user?.role === 'STUDENT')
check('realName 有值（前端 normalizeUser 依赖）', !!sLogin.body?.data?.user?.realName,
  `realName=${sLogin.body?.data?.user?.realName}`)
check('mustChangePassword=true（前端跳 ForcePassword）', sLogin.body?.data?.mustChangePassword === true)
const sOldToken = sLogin.body?.data?.token

// 改密前，业务接口被拦
const beforeChange = await call('GET', '/permission-demo/class/1/view', { token: sOldToken })
check('改密前访问业务接口被拦 → 403/40302',
  beforeChange.status === 403 && beforeChange.body?.code === 40302,
  `HTTP=${beforeChange.status} code=${beforeChange.body?.code}`)
// 改密白名单放行
const meBefore = await call('GET', '/auth/me', { token: sOldToken })
check('改密白名单内 /auth/me 放行', meBefore.status === 200)

// 改密（前端只发 oldPassword/newPassword）
const sChange = await call('POST', '/auth/password', {
  body: { oldPassword: 'Init@1234', newPassword: 'Student@123' }, token: sOldToken
})
check('改密成功', sChange.body?.code === 200, `code=${sChange.body?.code}`)
check('响应回签新 token（前端必须落盘）',
  typeof sChange.body?.data?.token === 'string' && sChange.body.data.token !== sOldToken)
check('改密后 mustChangePassword=false', sChange.body?.data?.mustChangePassword === false)
const sNewToken = sChange.body?.data?.token

// 旧令牌失效
const oldReuse = await call('GET', '/auth/me', { token: sOldToken })
check('旧令牌立即失效 → 401/40103',
  oldReuse.status === 401 && oldReuse.body?.code === 40103,
  `HTTP=${oldReuse.status} code=${oldReuse.body?.code}`)
// 新令牌可用
const newUse = await call('GET', '/auth/me', { token: sNewToken })
check('新令牌可用', newUse.status === 200, `code=${newUse.body?.code}`)
// 改密后可访问业务接口
//
// 前置：需先执行 `tools/seed-demo.sql` 造出班级/问卷演示数据
// （班级与问卷模块尚未交付，只有权限校验层 + 演示端点）。
const afterChange = await call('GET', '/permission-demo/class/1/view', { token: sNewToken })
check('改密后不再被 40302 拦截，进入数据级校验',
  afterChange.body?.code !== 40302,
  `code=${afterChange.body?.code} msg=${afterChange.body?.message}`)

// ---------------------------------------------------------------- 场景 1b：数据级权限
section('场景 1b：数据级权限（水平越权）—— 需先跑 tools/seed-demo.sql')
if (afterChange.body?.code === 40402) {
  console.log('  [SKIP] class_info 为空，未执行 tools/seed-demo.sql；跳过数据级权限断言')
} else {
  check('学生一可查看本班班级（class 1）',
    afterChange.status === 200 && afterChange.body?.code === 200,
    `code=${afterChange.body?.code}`)
  const sForeign = await call('GET', '/permission-demo/class/2/view', { token: sNewToken })
  check('学生一查看别班班级（class 2）→ 404，与「不存在」同码',
    sForeign.status === 404 && sForeign.body?.code === 40402,
    `code=${sForeign.body?.code}`)
  const sAbsent = await call('GET', '/permission-demo/class/99999/view', { token: sNewToken })
  check('「不存在」与「越权」响应完全一致（防 ID 枚举）',
    sAbsent.body?.code === sForeign.body?.code && sAbsent.body?.message === sForeign.body?.message,
    `absent=${sAbsent.body?.code} foreign=${sForeign.body?.code}`)
  // 已发布问卷对学生可见，草稿不可见
  const sPub = await call('GET', '/permission-demo/questionnaire/1/view', { token: sNewToken })
  check('学生可见已发布问卷（id=1）', sPub.body?.code === 200, `code=${sPub.body?.code}`)
  const sDraft = await call('GET', '/permission-demo/questionnaire/2/view', { token: sNewToken })
  check('学生不可见草稿问卷（id=2）→ 404',
    sDraft.status === 404 && sDraft.body?.code === 40403,
    `code=${sDraft.body?.code}`)
}

// ---------------------------------------------------------------- 场景 2：老师手机号登录
section('场景 2：老师用手机号登录（findByAccountOrPhone 手机号分支）')
const tLogin = await call('POST', '/auth/login', { body: { account: '13800000001', password: 'Init@1234' } })
check('手机号登录成功', tLogin.body?.code === 200, `code=${tLogin.body?.code}`)
check('识别为 TEACHER', tLogin.body?.data?.user?.role === 'TEACHER')
check('返回的 account 是工号 T1001（不是手机号）', tLogin.body?.data?.user?.account === 'T1001',
  `account=${tLogin.body?.data?.user?.account}`)
check('手机号已脱敏', /^\d{3}\*{4}\d{4}$/.test(tLogin.body?.data?.user?.phone || ''),
  `phone=${tLogin.body?.data?.user?.phone}`)
const tToken0 = tLogin.body?.data?.token
const tChange = await call('POST', '/auth/password', {
  body: { oldPassword: 'Init@1234', newPassword: 'Teacher@123' }, token: tToken0
})
check('老师改密成功', tChange.body?.code === 200, `code=${tChange.body?.code}`)
const tToken = tChange.body?.data?.token

// ---------------------------------------------------------------- 场景 3：越权
section('场景 3：越权拦截（接口级 + 数据级）')
const sAdmin = await call('GET', '/admin/users', { token: sNewToken })
check('学生调管理员接口 → 403/40300',
  sAdmin.status === 403 && sAdmin.body?.code === 40300,
  `code=${sAdmin.body?.code} msg=${sAdmin.body?.message}`)
const tAdmin = await call('GET', '/admin/users', { token: tToken })
check('老师调管理员接口 → 403/40300', tAdmin.status === 403 && tAdmin.body?.code === 40300)
const sTeacherOnly = await call('GET', '/auth/teacher-only-demo', { token: sNewToken })
check('学生调老师专属接口 → 403/40300', sTeacherOnly.status === 403 && sTeacherOnly.body?.code === 40300)
// 数据级：学生访问不属于自己的班级
const sClass2 = await call('GET', '/permission-demo/class/2/view', { token: sNewToken })
check('学生访问别班班级 → 404（与「不存在」同码，防 ID 枚举）',
  sClass2.status === 404 && sClass2.body?.code === 40402,
  `code=${sClass2.body?.code}`)

// ---------------------------------------------------------------- 场景 4：管理员
section('场景 4：管理员全权限 + 账号管理')
const aLogin = await call('POST', '/auth/login', { body: { account: 'admin', password: 'Admin@123' } })
check('管理员登录成功', aLogin.body?.code === 200)
const aToken = aLogin.body?.data?.token
const aAdmin = await call('GET', '/admin/users?pageNum=1&pageSize=10', { token: aToken })
check('管理员可访问管理接口', aAdmin.body?.code === 200)
check('分页字段 list/total/pageNum/pageSize',
  Array.isArray(aAdmin.body?.data?.list) &&
  typeof aAdmin.body?.data?.total === 'number' &&
  aAdmin.body?.data?.pageNum === 1 && aAdmin.body?.data?.pageSize === 10,
  `total=${aAdmin.body?.data?.total}`)
check('列表项含 realName 且无 passwordHash',
  typeof aAdmin.body?.data?.list?.[0]?.realName === 'string' &&
  !('passwordHash' in (aAdmin.body?.data?.list?.[0] || {})))
const aTeacherOnly = await call('GET', '/auth/teacher-only-demo', { token: aToken })
check('管理员拥有全部权限（可访问老师专属接口）', aTeacherOnly.body?.code === 200)
// 分页上限
const bigPage = await call('GET', '/admin/users?pageNum=1&pageSize=500', { token: aToken })
check('pageSize 超限被截到 100', bigPage.body?.data?.pageSize === 100,
  `pageSize=${bigPage.body?.data?.pageSize}`)

// ---------------------------------------------------------------- 场景 5：禁用即踢下线
section('场景 5：管理员禁用账号 → 在线令牌立即失效')
const sId = (await call('GET', '/admin/users?keyword=2021001', { token: aToken })).body?.data?.list?.[0]?.id
const disable = await call('PUT', `/admin/users/${sId}/status?enabled=false`, { token: aToken })
check('禁用成功', disable.body?.code === 200, `code=${disable.body?.code}`)
const disabledUse = await call('GET', '/auth/me', { token: sNewToken })
check('禁用后在线令牌失效 → 403/40301',
  disabledUse.status === 403 && disabledUse.body?.code === 40301,
  `HTTP=${disabledUse.status} code=${disabledUse.body?.code}`)
const disabledLogin = await call('POST', '/auth/login', { body: { account: '2021001', password: 'Student@123' } })
check('禁用后无法登录 → 403/40301',
  disabledLogin.status === 403 && disabledLogin.body?.code === 40301)
const enable = await call('PUT', `/admin/users/${sId}/status?enabled=true`, { token: aToken })
check('重新启用成功', enable.body?.code === 200)
const reLogin = await call('POST', '/auth/login', { body: { account: '2021001', password: 'Student@123' } })
check('启用后可重新登录（onLoginSuccess 清锁定生效）', reLogin.body?.code === 200)

// ---------------------------------------------------------------- 场景 6：登出
section('场景 6：登出 → 令牌进黑名单')
const logout = await call('POST', '/auth/logout', { token: tToken })
check('登出成功', logout.body?.code === 200, `code=${logout.body?.code}`)
const afterLogout = await call('GET', '/auth/me', { token: tToken })
check('登出后令牌立即失效 → 401/40103',
  afterLogout.status === 401 && afterLogout.body?.code === 40103,
  `HTTP=${afterLogout.status} code=${afterLogout.body?.code}`)

console.log('\n===============================')
console.log(`  端到端场景验收: ${pass} PASS / ${fail} FAIL`)
console.log('===============================')
if (fail) {
  console.log('失败用例:')
  failures.forEach((f) => console.log('  - ' + f))
}
process.exitCode = fail ? 1 : 0
