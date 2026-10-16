import request from './request'

// ============================================================================
// 本文件是「前端 UI 模型 → 后端 DTO」的唯一转换层。
// 后端契约（Spring Boot，com.g14.g14.Login.config）：
//   响应统一为 { code, message, data, timestamp }，code=200 为成功；
//   HTTP 状态码与 code 对齐（40000→400、40104→401、40300→403、40901→409）。
// 注意：后端的字段名与 mock 不同（realName 而非 name、initialPassword 而非 password、
//       pageNum/pageSize 而非 page/size），转换只在这里做一次，组件层不必关心。
// ============================================================================

// ---------- 认证接口 ----------
// 登录。请求体只有 account + password —— 角色由后端查库自动识别，前端不提交 role。
// skipAuth：不带旧 token（避免 localStorage 里的过期令牌干扰登录）。
export function login(data) {
  return request.post(
    '/auth/login',
    {
      account: data.account,
      password: data.password
    },
    { skipAuth: true }
  )
}

export function logout() {
  return request.post('/auth/logout')
}

// 获取当前登录用户信息
export function getMe() {
  return request.get('/auth/me')
}

/**
 * 修改本人密码（首次登录强制改密复用此接口）。
 * 后端是 POST /api/auth/password（不是 PUT）。
 * 响应 data 是新的 LoginVO：改密会让 tokenVersion+1、旧令牌立即失效，
 * 因此后端回签了一个新 token，前端必须替换本地存储，否则下一次请求就 40103。
 */
export function changePassword(data) {
  return request.post('/auth/password', {
    oldPassword: data.oldPassword,
    newPassword: data.newPassword
  })
}

// ---------- 管理端接口（仅管理员） ----------
/**
 * 开通账号。
 * 后端字段：account / realName / role / phone / initialPassword。
 * initialPassword 留空则后端使用配置的初始密码（app.auth.initial-password）。
 * 无论哪种情况后端都会把 mustChangePassword 置为 true。
 */
export function adminCreateUser(data) {
  return request.post('/admin/users', {
    account: data.account,
    realName: data.realName,
    role: data.role,
    phone: data.phone ? data.phone : null,
    initialPassword: data.initialPassword ? data.initialPassword : null
  })
}

/** 管理员重置指定用户密码（后端是 POST，不是 PUT） */
export function adminResetPassword(id, data) {
  return request.post(`/admin/users/${id}/password/reset`, {
    newPassword: data.newPassword
  })
}

/**
 * 用户列表（分页 + 过滤）。
 * 后端参数名是 pageNum / pageSize，且每页上限 100（AdminUserService.MAX_PAGE_SIZE）。
 * 空串会被后端当作「无此条件」，但显式传 undefined 更干净（axios 会省略该参数）。
 */
export function adminListUsers(params) {
  return request.get('/admin/users', {
    params: {
      keyword: params.keyword ? params.keyword : undefined,
      role: params.role ? params.role : undefined,
      pageNum: params.pageNum,
      pageSize: params.pageSize
    }
  })
}

/**
 * 启用 / 禁用账号。
 * 后端是 PUT /api/admin/users/{id}/status?enabled=true|false —— 没有删除接口，
 * 所以管理页的「删除」按钮改为「禁用/启用」（禁用会 tokenVersion+1，在线会话立即失效）。
 */
export function adminUpdateStatus(id, enabled) {
  return request.put(`/admin/users/${id}/status`, null, {
    params: { enabled: !!enabled }
  })
}
