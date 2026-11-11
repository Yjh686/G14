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
