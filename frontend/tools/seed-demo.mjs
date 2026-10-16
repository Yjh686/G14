// 演示数据初始化：通过**管理员 API** 开通三种角色的演示账号，
// 而不是直接写库 —— 这样密码哈希、mustChangePassword、tokenVersion
// 等字段都由后端真实业务逻辑产生，与手工点「开通账号」完全等价。
//
// 用法：
//   1) 后端已在 8080 运行（mvn 启动或 IDEA 启动）
//   2) node tools/seed-demo.mjs
//   3) 若走 Vite 代理：node tools/seed-demo.mjs http://localhost:5173/api
//   4) 加 --reset 把已存在账号的密码重置回初始密码
//      （e2e-journey.mjs 会改密码，跑完想再跑一次就加 --reset）
//
// 说明：账号若已存在（40901）会被视为成功，脚本可重复执行。
const BASE = process.argv[2] || 'http://localhost:8080/api'
const RESET = process.argv.includes('--reset')

const ADMIN_ACCOUNT = 'admin'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Admin@123'
const INITIAL_PASSWORD = 'Init@1234' // 与后端 app.auth.initial-password 一致

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
    /* 非 JSON */
  }
  return { status: res.status, body: json }
}

const DEMO_USERS = [
  { account: 'T1001', realName: '老师甲', role: 'TEACHER', phone: '13800000001' },
  { account: 'T1002', realName: '老师乙', role: 'TEACHER', phone: '13800000002' },
  { account: '2021001', realName: '学生一', role: 'STUDENT', phone: null },
  { account: '2021002', realName: '学生二', role: 'STUDENT', phone: null }
]

console.log(`目标后端: ${BASE}\n`)

const login = await call('POST', '/auth/login', {
  body: { account: ADMIN_ACCOUNT, password: ADMIN_PASSWORD }
})
if (login.body?.code !== 200) {
  console.error(`管理员登录失败: HTTP=${login.status} code=${login.body?.code} ${login.body?.message || ''}`)
  console.error('请确认后端已启动，且 admin 密码为 Admin@123（或设置 ADMIN_PASSWORD 环境变量）')
  process.exit(1)
}
const adminToken = login.body.data.token
console.log(`[OK] 管理员登录成功，realName=${login.body.data.user.realName}\n`)

let created = 0
let existed = 0
let failed = 0

for (const u of DEMO_USERS) {
  const r = await call('POST', '/admin/users', {
    body: {
      account: u.account,
      realName: u.realName,
      role: u.role,
      phone: u.phone,
      initialPassword: null // null → 后端使用 app.auth.initial-password
    },
    token: adminToken
  })
  const code = r.body?.code
  if (code === 200) {
    created++
    console.log(`[新建] ${u.role.padEnd(7)} ${u.account.padEnd(9)} ${u.realName}  id=${r.body.data.id}`)
  } else if (code === 40901) {
    existed++
    console.log(`[已存在] ${u.role.padEnd(7)} ${u.account.padEnd(9)} ${u.realName}`)
    if (RESET) {
      // 查 id → 重置密码。重置后 mustChangePassword=1、tokenVersion+1，
      // 与「首次开通」的状态一致。
      const found = await call('GET', `/admin/users?keyword=${u.account}&pageNum=1&pageSize=10`, {
        token: adminToken
      })
      const row = (found.body?.data?.list || []).find((x) => x.account === u.account)
      if (!row) {
        failed++
        console.log(`  [重置失败] 未查到 id`)
      } else {
        const rr = await call('POST', `/admin/users/${row.id}/password/reset`, {
          // 传 null → 后端恢复「系统初始密码」并只做长度校验。
          // 不能显式传 Init@1234：后端对显式指定的密码套用完整强度策略，
          // 而初始密码本身就在弱口令黑名单里，会被拒（400/40001）。
          body: { newPassword: null },
          token: adminToken
        })
        if (rr.body?.code === 200) {
          console.log(`  [已重置] id=${row.id} 密码回到 ${INITIAL_PASSWORD}`)
        } else {
          failed++
          console.log(`  [重置失败] HTTP=${rr.status} code=${rr.body?.code} ${rr.body?.message || ''}`)
        }
      }
    }
  } else {
    failed++
    console.log(`[失败] ${u.role.padEnd(7)} ${u.account.padEnd(9)} HTTP=${r.status} code=${code} ${r.body?.message || ''}`)
  }
}

console.log(`\n新建 ${created} / 已存在 ${existed} / 失败 ${failed}`)
console.log('\n可用演示账号（首次登录均要求修改密码）：')
console.log(`  管理员  ${ADMIN_ACCOUNT} / ${ADMIN_PASSWORD}   （已初始化，无需改密）`)
for (const u of DEMO_USERS) {
  const byPhone = u.phone ? `  或用手机号 ${u.phone}` : ''
  console.log(`  ${u.role === 'TEACHER' ? '老师  ' : '学生  '}  ${u.account} / ${INITIAL_PASSWORD}${byPhone}`)
}
process.exitCode = failed ? 1 : 0
