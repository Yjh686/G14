import { defineStore } from 'pinia'
import { login as apiLogin, getMe, changePassword as apiChangePassword } from '../api/auth'
import { normalizeUser } from '../utils/user'
import { ROLE } from '../utils/role'

const TOKEN_KEY = 'access_token'
const USER_KEY = 'user_info'

/** 安全地读 localStorage 里的用户 JSON（内容可能被手工改坏） */
function readStoredUser() {
  try {
    return normalizeUser(JSON.parse(localStorage.getItem(USER_KEY) || 'null'))
  } catch {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // 从 localStorage 恢复会话，刷新页面不丢登录态
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readStoredUser(),
    // 后端 LoginVO 顶层也带 mustChangePassword；这里单独存一份，
    // 因为 user 对象在 fetchUser 后可能被服务端数据覆盖
    mustChangePasswordFlag: !!readStoredUser()?.mustChangePassword
  }),

  getters: {
    isLoggedIn: (s) => !!s.token,
    role: (s) => s.user?.role || '',
    isStudent: (s) => s.user?.role === ROLE.STUDENT,
    isTeacher: (s) => s.user?.role === ROLE.TEACHER,
    isAdmin: (s) => s.user?.role === ROLE.ADMIN,
    // 后端在 LoginVO 顶层与 user 里都返回了该标记，取其一即可
    mustChangePassword: (s) => !!(s.mustChangePasswordFlag ?? s.user?.mustChangePassword)
  },

  actions: {
    /** 保存令牌与用户信息（登录 / 改密换发新令牌都走这里） */
    persist(token, user) {
      const normalized = normalizeUser(user)
      if (token) {
        this.token = token
        localStorage.setItem(TOKEN_KEY, token)
      }
      if (normalized) {
        this.user = normalized
        localStorage.setItem(USER_KEY, JSON.stringify(normalized))
      }
      // 与 user.mustChangePassword 同步，供路由守卫读取
      this.mustChangePasswordFlag = !!normalized?.mustChangePassword
    },

    /**
     * 登录成功：保存 token 与用户信息。
     * 后端 LoginVO 的顶层 mustChangePassword 与 user.mustChangePassword 同源，
     * 这里统一落到 user 上，避免两处不一致。
     */
    async login(payload) {
      const res = await apiLogin(payload)
      const { token, user, mustChangePassword } = res.data
      const merged = { ...(user || {}), mustChangePassword: !!mustChangePassword }
      this.persist(token, merged)
      return this.user
    },

    /** 拉取最新用户信息（刷新页面 / 校验会话时使用） */
    async fetchUser() {
      const res = await getMe()
      this.persist(null, res.data)
      return this.user
    },

    /**
     * 修改密码。
     * 后端改密后 tokenVersion+1，旧令牌立即失效，因此响应里带了新令牌，
     * 必须替换本地存储 —— 否则下一次请求就会 40103「令牌已失效」。
     */
    async changePassword(payload) {
      const res = await apiChangePassword(payload)
      const data = res.data
      if (data && data.token) {
        this.persist(data.token, data.user)
      } else if (this.user) {
        // 兜底：后端未回签新令牌时，至少清掉本地强制改密标记
        this.user.mustChangePassword = false
        this.mustChangePasswordFlag = false
        localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      }
      return res
    },

    /** 登出：清空本地状态（服务端黑名单由 MainLayout 调 /auth/logout 写入） */
    logout() {
      this.token = ''
      this.user = null
      this.mustChangePasswordFlag = false
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
