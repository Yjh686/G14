import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import router from '../router'

// 后端统一返回 { code, message, data }，code=200 视为成功
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000
})

// 请求拦截：自动携带 token
request.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// 401 统一处理：清空登录态并跳转登录页（避免在每个接口重复写）
function handleUnauthorized() {
  const auth = useAuthStore()
  auth.logout()
  if (router.currentRoute.value.name !== 'Login') {
    router.replace({ name: 'Login' })
  }
}

// 403 越权处理：提示无权限，并回到有权限的首页
function handleForbidden(msg) {
  ElMessage.error(msg || '无权限访问该资源')
  if (router.currentRoute.value.name !== 'Dashboard') {
    router.replace({ name: 'Dashboard' })
  }
}

// 响应拦截：拆解业务 code 与 HTTP 错误
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && typeof res === 'object' && 'code' in res) {
      if (res.code === 200) return res
      if (res.code === 401) {
        handleUnauthorized()
        return Promise.reject(new Error(res.message || '登录已过期'))
      }
      if (res.code === 403) {
        handleForbidden(res.message)
        return Promise.reject(new Error(res.message || '无权限访问该资源'))
      }
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    const status = error.response?.status
    const msg = error.response?.data?.message || error.message || '网络异常'
    if (status === 401) {
      handleUnauthorized()
    } else if (status === 403) {
      handleForbidden(msg)
    } else if (status) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default request
