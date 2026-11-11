/**
 * 后端统一响应体。
 * code 与 HTTP 状态码一致（200/400/401/403/404/409/500）。
 */
export interface ApiResult<T = unknown> {
  code: number
  msg: string
  data: T
  timestamp: number
  success: boolean
}

/** 分页结果，对应后端 PageResult */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

/* ------------------------- 认证 ------------------------- */

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  expiresIn: number
  userId: number
  username: string
  realName: string
  roleCode: RoleCode
  roleName: string
  mustChangePassword: boolean
  permissions: string[]
}

export interface RegisterRequest {
  username: string
  password: string
  realName: string
  role: 'STUDENT' | 'TEACHER'
  department?: string
  phone?: string
}

export interface MeResponse {
  userId: number
  username: string
  realName: string
  roleCode: RoleCode
  roleName: string
  department: string | null
  phone: string | null
  email: string | null
  mustChangePassword: boolean
  lastLoginTime: string | null
  permissions: string[]
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

export interface UpdateProfileRequest {
  realName?: string
  department?: string
  phone?: string
  email?: string
}

/* ------------------------- 用户 ------------------------- */

export type RoleCode = 'ROLE_ADMIN' | 'ROLE_TEACHER' | 'ROLE_STUDENT'

export interface UserVO {
  id: number
  username: string
  realName: string
  status: number
  auditStatus: number
  auditRemark: string | null
  mustChangePassword: number
  phone: string | null
  email: string | null
  department: string | null
  remark: string | null
  roleCode: RoleCode
  roleName: string
  lastLoginTime: string | null
  createTime: string
  updateTime: string
}

export interface UserQuery {
  keyword?: string
  status?: number
  auditStatus?: number
  roleCode?: RoleCode
  page?: number
  size?: number
}

export interface CreateUserRequest {
  username: string
  realName: string
  roleCode: RoleCode
  password?: string
  department?: string
  phone?: string
  email?: string
  remark?: string
}

export interface UpdateUserRequest {
  realName?: string
  department?: string
  phone?: string
  email?: string
  remark?: string
  roleCode?: RoleCode
}

export interface AuditUserRequest {
  auditStatus: 1 | 2
  auditRemark?: string
}

/* ------------------------- 角色权限 ------------------------- */

export interface RoleVO {
  id: number
  code: RoleCode
  name: string
  description: string | null
  builtin: number
}

export interface PermissionVO {
  id: number
  code: string
  name: string
  module: string
  description: string | null
}
