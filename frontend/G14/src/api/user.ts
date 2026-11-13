import { del, get, post, put } from './http'
import type {
  AuditUserRequest,
  CreateUserRequest,
  PageResult,
  PermissionVO,
  RoleVO,
  UpdateUserRequest,
  UserQuery,
  UserVO,
} from '@/types/api'

export const userApi = {
  page: (query: UserQuery) => get<PageResult<UserVO>>('/users', { ...query }),

  detail: (id: number) => get<UserVO>(`/users/${id}`),

  create: (data: CreateUserRequest) => post<UserVO>('/users', data),

  update: (id: number, data: UpdateUserRequest) => put<UserVO>(`/users/${id}`, data),

  remove: (id: number) => del<null>(`/users/${id}`),

  changeStatus: (id: number, status: number) =>
    put<null>(`/users/${id}/status`, null, { params: { status } }),

  audit: (id: number, data: AuditUserRequest) => put<null>(`/users/${id}/audit`, data),

  /** 重置为系统默认密码，返回明文供管理员线下告知 */
  resetPassword: (id: number) => post<string>(`/users/${id}/reset-password`),

  /**
   * 清除某用户的密保问题（含验证锁定）。
   *
   * <p>这是重置密码的配套兜底：用户可能连答案也忘了，或连续答错被锁了 30 分钟，
   * 这两种情况下重置密码只能让他登进来，找回通道依然是堵的。
   *
   * <p>后端返回的 msg 带不回来（拦截器只取 data），成功提示在前端写。
   */
  clearRecovery: (id: number) => del<null>(`/users/${id}/recovery`),
}

export const rbacApi = {
  roles: () => get<RoleVO[]>('/rbac/roles'),

  permissions: () => get<Record<string, PermissionVO[]>>('/rbac/permissions'),

  userPermissions: (userId: number) => get<string[]>(`/rbac/users/${userId}/permissions`),

  userExtraPermissions: (userId: number) =>
    get<string[]>(`/rbac/users/${userId}/extra-permissions`),

  grant: (userId: number, permCode: string) =>
    post<null>(`/rbac/users/${userId}/permissions`, null, { params: { permCode } }),

  revoke: (userId: number, permCode: string) =>
    del<null>(`/rbac/users/${userId}/permissions`, { permCode }),
}
