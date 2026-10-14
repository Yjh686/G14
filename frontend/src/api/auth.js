import request from './request'

// ---------- 认证接口 ----------
// 登录（后端根据账号自动识别角色，返回 JWT + 用户信息）
export function login(data) {
  return request.post('/auth/login', data)
}

export function logout() {
  return request.post('/auth/logout')
}

// 获取当前登录用户信息
export function getMe() {
  return request.get('/auth/me')
}

// 修改本人密码（首次登录强制改密也复用此接口）
export function changePassword(data) {
  return request.put('/auth/password', data)
}

// ---------- 管理端接口（仅管理员） ----------
// 开通账号
export function adminCreateUser(data) {
  return request.post('/admin/users', data)
}

// 管理员重置指定用户密码
export function adminResetPassword(id, data) {
  return request.put(`/admin/users/${id}/password/reset`, data)
}

// 用户列表（分页 + 过滤）
export function adminListUsers(params) {
  return request.get('/admin/users', { params })
}
