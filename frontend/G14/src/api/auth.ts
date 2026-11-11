import { get, post, put } from './http'
import type {
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  MeResponse,
  RegisterRequest,
  UpdateProfileRequest,
} from '@/types/api'

export const authApi = {
  login: (data: LoginRequest) => post<LoginResponse>('/auth/login', data),

  logout: () => post<null>('/auth/logout'),

  register: (data: RegisterRequest) => post<null>('/auth/register', data),

  /** 改密成功后返回新 token，当前设备无需重新登录 */
  changePassword: (data: ChangePasswordRequest) => post<LoginResponse>('/auth/password', data),

  /** 是否处于「必须改密」状态，路由守卫用 */
  passwordStatus: () => get<boolean>('/auth/password-status'),

  me: () => get<MeResponse>('/auth/me'),

  updateProfile: (data: UpdateProfileRequest) => put<MeResponse>('/auth/profile', data),
}
