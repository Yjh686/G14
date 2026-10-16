import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import router from '../router'

// ============================================================================
// axios 实例 + 全局拦截器
// 后端契约：HTTP 状态码与业务码对齐，响应体恒为 { code, message, data, timestamp }。
//   200 → 成功（code=200）
//   400 → 40000 参数错误 / 40001 密码不合规 / 40002 原密码错误 / 40003 新旧密码相同
//   401 → 40100~40104 未登录、令牌无效、令牌已失效、账号或密码错误
//   403 → 40300 权限不足 / 40301 账号禁用 / 40302 必须改密 / 40303 账号锁定
//   404 → 40400 资源不存在 / 40401 用户不存在
//   409 → 40901 账号已存在 / 40902 手机号占用
// ============================================================================

// 后端 403 业务码：这几个不是「越权」，不能一律按无权限处理
const CODE_MUST_CHANGE_PASSWORD = 40302
const CODE_ACCOUNT_DISABLED = 40301
const CODE_ACCOUNT_LOCKED = 40303

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000
})

// 请求拦截：自动携带 token（config.skipAuth 为 true 时跳过，如登录接口）
request.interceptors.request.use((config) => {
  if (config.skipAuth) return config
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

/** 401 统一处理：清空登录态并跳转登录页 */
function handleUnauthorized(msg) {
  const auth = useAuthStore()
  auth.logout()
  if (router.currentRoute.value.name !== 'Login') {
    router.replace({ name: 'Login' })
  }
  if (msg) ElMessage.error(msg)
}

/**
 * 把后端的错误响应统一成一个带 code 的 Error，
 * 页面只要 `catch (e) { e.code }` 就能区分「原密码错」和「密码不合规」。
 */
function toError(payload, fallbackMessage) {
  const message = payload?.message || fallbackMessage || '请求失败'
  const error = new Error(message)
  error.code = payload?.code
  error.data = payload?.data
  return error
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 非标准响应（如文件流）原样返回
    if (!res || typeof res !== 'object' || !('code' in res)) return res
    if (res.code === 200) return res
    // 理论上后端失败会走 HTTP 错误分支，这里兜底「HTTP 200 但业务码非 200」的实现
    return Promise.reject(toError(res))
  },
  (error) => {
    const status = error.response?.status
    const payload = error.response?.data
    const code = payload?.code

    if (status === 401) {
      // 登录接口自己会提示「账号或密码错误」，不能触发全局登出跳转
      if (error.config?.url?.includes('/auth/login')) {
        return Promise.reject(toError(payload, '账号或密码错误'))
      }
      handleUnauthorized(payload?.message || '登录已过期，请重新登录')
      return Promise.reject(toError(payload, '登录已过期'))
    }

    if (status === 403) {
      // 强制改密：路由守卫需要把用户送到改密页，而不是弹「无权限」
      if (code === CODE_MUST_CHANGE_PASSWORD) {
        if (router.currentRoute.value.name !== 'ForcePassword') {
          router.replace({ name: 'ForcePassword' })
        }
        return Promise.reject(toError(payload, '请先修改初始密码'))
      }
      // 账号禁用 / 锁定：提示后送回登录页（令牌已无意义）
      if (code === CODE_ACCOUNT_DISABLED || code === CODE_ACCOUNT_LOCKED) {
        ElMessage.error(payload?.message || '账号不可用')
        const auth = useAuthStore()
        auth.logout()
        if (router.currentRoute.value.name !== 'Login') {
          router.replace({ name: 'Login' })
        }
        return Promise.reject(toError(payload, '账号不可用'))
      }
      // 真正的越权：只提示，不强跳（当前页可能只是探测了一个无权接口）
      ElMessage.error(payload?.message || '无权限访问该资源')
      return Promise.reject(toError(payload, '无权限访问该资源'))
    }

    // 其余 4xx/5xx：交给调用方决定是否展示，这里统一弹一次提示
    if (status) {
      ElMessage.error(payload?.message || error.message || '请求失败')
    } else {
      ElMessage.error('网络异常，请检查后端服务是否已启动')
    }
    return Promise.reject(toError(payload, error.message))
  }
)

export default request
