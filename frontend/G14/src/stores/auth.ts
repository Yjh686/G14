import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type {
  ChangePasswordRequest,
  LoginRequest,
  MeResponse,
  RoleCode,
  UpdateProfileRequest,
} from '@/types/api'
import {
  clearToken,
  clearUserSnapshot,
  getToken,
  readUserSnapshot,
  setToken,
  writeUserSnapshot,
} from '@/utils/token'

/**
 * 登录态与权限的唯一来源。
 *
 * 关键约定：角色判断一律走 permissions，不硬编码 roleCode。
 * 因为「学生干部发卷」是给用户追加 survey:create，角色仍是 STUDENT。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const user = ref<MeResponse | null>(readUserSnapshot<MeResponse>())
  /** 是否已向服务端确认过登录态（刷新页面后需要重新确认一次） */
  const loaded = ref(false)

  const isLoggedIn = computed(() => Boolean(token.value))
  const permissions = computed<string[]>(() => user.value?.permissions ?? [])
  const roleCode = computed<RoleCode | ''>(() => user.value?.roleCode ?? '')
  const mustChangePassword = computed(() => Boolean(user.value?.mustChangePassword))
  const isAdmin = computed(() => roleCode.value === 'ROLE_ADMIN')
  const displayName = computed(() => user.value?.realName || user.value?.username || '')

  function hasPerm(code: string | string[]): boolean {
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((c) => permissions.value.includes(c))
  }

  /** 任一权限满足即可 */
  function hasAnyPerm(code: string[]): boolean {
    return code.some((c) => permissions.value.includes(c))
  }

  function applyAuth(res: {
    token: string
    userId: number
    username: string
    realName: string
    roleCode: RoleCode
    roleName: string
    mustChangePassword: boolean
    permissions: string[]
  }): void {
    token.value = res.token
    setToken(res.token)
    // 登录接口不返回 department/phone 等字段，先占位，随后 fetchMe 补齐
    user.value = {
      userId: res.userId,
      username: res.username,
      realName: res.realName,
      roleCode: res.roleCode,
      roleName: res.roleName,
      department: null,
      phone: null,
      email: null,
      mustChangePassword: res.mustChangePassword,
      lastLoginTime: null,
      permissions: res.permissions,
    }
    writeUserSnapshot(user.value)
    loaded.value = true
  }

  async function login(payload: LoginRequest): Promise<MeResponse | null> {
    const res = await authApi.login(payload)
    applyAuth(res)
    // 补齐部门/邮箱等展示字段；失败不影响登录本身
    try {
      return await fetchMe()
    } catch {
      return user.value
    }
  }

  async function fetchMe(): Promise<MeResponse> {
    const me = await authApi.me()
    user.value = me
    writeUserSnapshot(me)
    loaded.value = true
    return me
  }

  async function changePassword(payload: ChangePasswordRequest): Promise<void> {
    const res = await authApi.changePassword(payload)
    // 后端已让旧令牌失效，这里换上新令牌继续使用
    applyAuth(res)
    await fetchMe()
  }

  async function updateProfile(payload: UpdateProfileRequest): Promise<void> {
    const me = await authApi.updateProfile(payload)
    user.value = me
    writeUserSnapshot(me)
  }

  function clear(): void {
    token.value = ''
    user.value = null
    loaded.value = false
    clearToken()
    clearUserSnapshot()
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 令牌已失效也照样清理本地状态
    } finally {
      clear()
    }
  }

  return {
    token,
    user,
    loaded,
    isLoggedIn,
    permissions,
    roleCode,
    mustChangePassword,
    isAdmin,
    displayName,
    hasPerm,
    hasAnyPerm,
    applyAuth,
    login,
    fetchMe,
    changePassword,
    updateProfile,
    clear,
    logout,
  }
})
