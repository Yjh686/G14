// mock/server.js —— 本地开发用 Mock 后端（仅用于前端独立预览页面，不连真实后端时使用）
// 说明：
//   1. 【默认端口 9090】故意避开真实后端占用的 8080，避免 `npm run mock` 时
//      EADDRINUSE，或在 vite 代理下「悄悄顶掉」真实后端让联调结果失真。
//      要用 mock 时把 .env.development 的 VITE_PROXY_TARGET 改成 http://localhost:9090。
//   2. 真实的 Spring Boot 后端已实现全部这些接口（契约以它为准），
//      本文件保留仅为「后端没起时也能看页面」的降级方案；
//      它的字段名（name / password / page / size）与真实后端（realName /
//      initialPassword / pageNum / pageSize）不同，src/api/auth.js 按真实后端转换。
//   3. 为演示方便，密码以明文比对并写入内存，真实后端使用 bcrypt 加密存储。
import http from 'http'
import crypto from 'crypto'

const SECRET = 'mock-secret-do-not-use-in-prod'
const PORT = process.env.PORT || 9090
const TOKEN_TTL = 7200 // 秒

// ---------------- 内存用户表（重启即重置） ----------------
let nextId = 5
const users = [
  // 学生（学号登录）
  { id: 1, account: '20250001', name: '张三', role: 'STUDENT', status: 1, mustChangePassword: false, phone: null, createdAt: '2026-10-01 09:00:00', password: '123456' },
  // 学生（首次登录需改密，用于演示强制改密流程）
  { id: 2, account: '20250002', name: '李四', role: 'STUDENT', status: 1, mustChangePassword: true, phone: null, createdAt: '2026-10-02 10:00:00', password: '123456' },
  // 老师（工号登录）
  { id: 3, account: '10001', name: '王老师', role: 'TEACHER', status: 1, mustChangePassword: false, phone: '13800000000', createdAt: '2026-10-01 09:00:00', password: '123456' },
  // 管理员
  { id: 4, account: 'admin', name: '系统管理员', role: 'ADMIN', status: 1, mustChangePassword: false, phone: null, createdAt: '2026-10-01 09:00:00', password: '123456' }
]

// ---------------- 工具函数 ----------------
function b64url(buf) {
  return Buffer.from(buf).toString('base64url')
}
function signJwt(payload) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const body = b64url(JSON.stringify(payload))
  const sig = crypto.createHmac('sha256', SECRET).update(`${header}.${body}`).digest('base64url')
  return `${header}.${body}.${sig}`
}
function verifyJwt(token) {
  try {
    const [h, b, s] = String(token || '').split('.')
    const expect = crypto.createHmac('sha256', SECRET).update(`${h}.${b}`).digest('base64url')
    if (s !== expect) return null
    const payload = JSON.parse(Buffer.from(b, 'base64url').toString('utf8'))
    if (payload.exp < Date.now() / 1000) return null
    return payload
  } catch {
    return null
  }
}
function publicUser(u) {
  const { password, ...rest } = u
  return rest
}
function json(res, status, obj) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(obj))
}
function ok(res, data, message = '成功') {
  json(res, 200, { code: 200, message, data })
}
function fail(res, status, message) {
  json(res, status, { code: status, message })
}
function readBody(req) {
  return new Promise((resolve) => {
    let data = ''
    req.on('data', (c) => (data += c))
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {})
      } catch {
        resolve({})
      }
    })
  })
}
function authFromReq(req) {
  const header = req.headers.authorization || ''
  const token = header.startsWith('Bearer ') ? header.slice(7) : null
  const payload = token ? verifyJwt(token) : null
  return payload ? users.find((u) => u.id === payload.userId) || null : null
}

// ---------------- 业务处理 ----------------
async function handleLogin(req, res, body) {
  const { account, password } = body
  const user = users.find((u) => u.account === account)
  if (!user || user.password !== password) return fail(res, 401, '账号或密码错误')
  if (user.status !== 1) return fail(res, 403, '账号已被禁用')

  const now = Math.floor(Date.now() / 1000)
  const token = signJwt({ userId: user.id, role: user.role, iat: now, exp: now + TOKEN_TTL })
  ok(
    res,
    {
      token,
      tokenType: 'Bearer',
      expiresIn: TOKEN_TTL,
      mustChangePassword: !!user.mustChangePassword,
      user: publicUser(user)
    },
    '登录成功'
  )
}

function handleLogout(req, res) {
  // 无状态 JWT：前端丢弃 token 即完成登出
  ok(res, null, '已登出')
}

function handleMe(req, res, body, user) {
  ok(res, publicUser(user))
}

async function handleChangePassword(req, res, body, user) {
  const { oldPassword, newPassword } = body
  if (user.password !== oldPassword) return fail(res, 400, '原密码错误')
  if (!newPassword || newPassword.length < 8) return fail(res, 400, '新密码长度至少 8 位')
  user.password = newPassword
  user.mustChangePassword = false
  ok(res, null, '密码修改成功')
}

async function handleCreateUser(req, res, body, user) {
  const { account, name, role, password, phone } = body
  if (!account || !name || !password) return fail(res, 400, '账号、姓名、密码不能为空')
  if (!['STUDENT', 'TEACHER', 'ADMIN'].includes(role)) return fail(res, 400, '角色不合法')
  if (users.some((u) => u.account === account)) return fail(res, 409, '账号已存在')

  const nu = {
    id: nextId++,
    account,
    name,
    role,
    status: 1,
    mustChangePassword: true, // 新账号首次登录强制改密
    phone: phone || null,
    createdAt: new Date().toISOString().replace('T', ' ').slice(0, 19),
    password
  }
  users.push(nu)
  ok(res, publicUser(nu), '账号开通成功')
}

async function handleResetPassword(req, res, body, user, match) {
  const id = Number(match[1])
  const target = users.find((u) => u.id === id)
  if (!target) return fail(res, 404, '用户不存在')
  const { newPassword } = body
  if (!newPassword || newPassword.length < 8) return fail(res, 400, '新密码长度至少 8 位')
  target.password = newPassword
  target.mustChangePassword = true
  ok(res, null, '密码已重置')
}

function handleListUsers(req, res, body, user) {
  ok(res, { total: users.length, list: users.map(publicUser) })
}

// ---------------- 路由 ----------------
// [method, matcher(字符串精确/正则), roles(null=无需登录, []或['ADMIN']=需登录并可限制角色), fn(req,res,body,user,match)]
const routes = [
  ['POST', '/api/auth/login', null, handleLogin],
  ['POST', '/api/auth/logout', [], handleLogout],
  ['GET', '/api/auth/me', [], handleMe],
  ['PUT', '/api/auth/password', [], handleChangePassword],
  ['POST', '/api/admin/users', ['ADMIN'], handleCreateUser],
  ['PUT', /^\/api\/admin\/users\/(\d+)\/password\/reset$/, ['ADMIN'], handleResetPassword],
  ['GET', '/api/admin/users', ['ADMIN'], handleListUsers]
]

const server = http.createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname
  const method = req.method

  for (const [m, matcher, roles, fn] of routes) {
    const match =
      typeof matcher === 'string' ? (m === method && matcher === path ? [path] : null) : m === method ? path.match(matcher) : null
    if (!match) continue

    const body = await readBody(req)

    if (roles !== null) {
      // 需要登录 / 指定角色
      const user = authFromReq(req)
      if (!user) return fail(res, 401, '未登录或 token 无效')
      if (roles.length && !roles.includes(user.role)) return fail(res, 403, '无权限访问该资源')
      return fn(req, res, body, user, match)
    }
    return fn(req, res, body, null, match)
  }

  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type,Authorization'
    })
    return res.end()
  }
  fail(res, 404, '接口不存在')
})

server.listen(PORT, () => {
  console.log(`[mock] 认证接口已就绪，监听 http://localhost:${PORT}/api`)
  console.log('[mock] 测试账号：')
  console.log('   学生    20250001 / 123456（正常）')
  console.log('   学生    20250002 / 123456（首次登录需改密）')
  console.log('   老师    10001    / 123456')
  console.log('   管理员  admin    / 123456')
})
