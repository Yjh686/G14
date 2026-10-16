// 演示账号基线复位（测试前置步骤）。
//
// 为什么需要它：e2e-journey.mjs 与 contract-test.mjs 都会**真实改密码**
// （这正是要验证的业务流程），因此脚本不是幂等的 —— 第二次跑会因为
// 「旧密码已失效」而整片报 40104/40101，看起来像回归，其实是测试状态脏了。
//
// 这里通过管理员 API 把演示账号复位到「首次开通」状态：
//   - 密码回到 app.auth.initial-password（Init@1234）
//   - mustChangePassword = 1
//   - status = 1（启用），清空失败计数与锁定
//   - tokenVersion + 1（旧令牌全部失效）
//
// 关键点：重置接口必须传 `newPassword: null`。
// 传显式密码会走**完整强度策略**，而 Init@1234 本身就在弱口令黑名单里，
// 会被拒（400/40001）；传 null 才走「恢复系统初始密码」分支，只校验长度。

const INITIAL_PASSWORD = 'Init@1234'
const ADMIN_ACCOUNT = 'admin'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Admin@123'

/** 需要复位的演示账号（与 seed-demo.mjs 保持一致） */
export const DEMO_ACCOUNTS = ['T1001', 'T1002', '2021001', '2021002']

export function makeCall(base) {
  return async function call(method, path, { body, token } = {}) {
    const headers = {}
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8'
    const res = await fetch(`${base}${path}`, {
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
}

/**
 * 把演示账号复位到首次开通状态。返回管理员令牌，供调用方继续使用。
 * 账号不存在时视为成功（调用方应先跑 seed-demo.mjs）。
 */
export async function resetDemoAccounts(base, { quiet = false } = {}) {
  const call = makeCall(base)
  const log = (...a) => {
    if (!quiet) console.log(...a)
  }

  const admin = await call('POST', '/auth/login', {
    body: { account: ADMIN_ACCOUNT, password: ADMIN_PASSWORD }
  })
  if (admin.body?.code !== 200) {
    throw new Error(
      `前置复位失败：管理员登录不成功 HTTP=${admin.status} code=${admin.body?.code} ` +
        `${admin.body?.message || ''}（请确认后端在跑，且 admin 密码为 ${ADMIN_PASSWORD}）`
    )
  }
  const adminToken = admin.body.data.token

  const listing = await call('GET', '/admin/users?pageNum=1&pageSize=100', { token: adminToken })
  const byAccount = new Map((listing.body?.data?.list || []).map((u) => [u.account, u]))

  for (const account of DEMO_ACCOUNTS) {
    const row = byAccount.get(account)
    if (!row) {
      log(`  [跳过] ${account} 不存在（先跑 node tools/seed-demo.mjs）`)
      continue
    }
    // 1) 复位密码 → 回到初始密码 + mustChangePassword=1 + tokenVersion+1
    const reset = await call('POST', `/admin/users/${row.id}/password/reset`, {
      body: { newPassword: null },
      token: adminToken
    })
    if (reset.body?.code !== 200) {
      throw new Error(`前置复位失败：${account} 重置密码 code=${reset.body?.code} ${reset.body?.message || ''}`)
    }
    // 2) 确保启用（上一轮 e2e 可能把它禁用了）
    if (row.status !== 1) {
      await call('PUT', `/admin/users/${row.id}/status?enabled=true`, { token: adminToken })
    }
    log(`  [复位] ${account} → ${INITIAL_PASSWORD}，mustChangePassword=1，status=1`)
  }

  return adminToken
}
