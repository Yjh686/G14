import { defineStore } from 'pinia'
import { login as apiLogin, getMe, changePassword as apiChangePassword } from '../api/auth'
import { ROLE } from '../utils/role'

const TOKEN_KEY = 'access_token'
const USER_KEY = 'user_info'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // 从 localStorage 恢复会话，刷新页面不丢登录态
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  }),

  getters: {
    isLoggedIn: (s) => !!s.token,
    role: (s) => s.user?.role || '',
    isStudent: (s) => s.user?.role === ROLE.STUDENT,
    isTeacher: (s) => s.user?.role === ROLE.TEACHER,
    isAdmin: (s) => s.user?.role === ROLE.ADMIN,
    mustChangePassword: (s) => !!s.user?.mustChangePassword
  },

  actions: {
    // 登录成功：保存 token 与用户信息
    async login(payload) {
      const res = await apiLogin(payload)
      const { token, user } = res.data
      this.token = token
      this.user = user
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
      return user
    },

    // 拉取最新用户信息（刷新页面 / 校验会话时使用）
    async fetchUser() {
      const res = await getMe()
      this.user = res.data
      localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      return this.user
    },

    // 修改密码；改密成功后服务端会清 mustChangePassword，本地同步
    async changePassword(payload) {
      const res = await apiChangePassword(payload)
      if (this.user) {
        this.user.mustChangePassword = false
        localStorage.setItem(USER_KEY, JSON.stringify(this.user))
      }
      return res
    },

    // 登出：清空本地状态
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})
