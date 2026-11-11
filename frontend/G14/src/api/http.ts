import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type { ApiResult } from '@/types/api'
import { clearToken, clearUserSnapshot, getToken } from '@/utils/token'

/**
 * 统一的请求实例。
 *
 * 约定：业务代码拿到的就是 `data` 本身，不需要再 `.data.data`。
 * 非 200 的 code 一律抛 ApiError，由调用方 catch 或交给全局提示。
 */

export class ApiError extends Error {
  readonly code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' },
})

/**
 * 未登录 / 令牌失效时的回调。
 * 由 auth store 注册，避免 http ←→ store ←→ router 的循环依赖。
 */
type UnauthorizedHandler = (message: string) => void
let unauthorizedHandler: UnauthorizedHandler | null = null

export function onUnauthorized(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult
    // 文件下载等非 JSON 响应直接透传
    if (body == null || typeof body !== 'object' || !('code' in body)) {
      return response as unknown as typeof response
    }
    if (body.code === 200) {
      // 这里刻意把 data 当作返回值，配合下方 get/post 的类型断言，
      // 让业务代码直接拿到 data，不必再解一层 .data.data
      return body.data as unknown as typeof response
    }
    throw new ApiError(body.code, body.msg || '请求失败')
  },
  (error) => {
    // 后端异常也带统一结构，优先用它的 msg
    const body = error.response?.data as ApiResult | undefined
    const code: number = body?.code ?? error.response?.status ?? -1
    let msg: string = body?.msg ?? ''

    if (!msg) {
      if (error.code === 'ECONNABORTED') {
        msg = '请求超时，请检查网络后重试'
      } else if (code === -1) {
        msg = '无法连接服务器，请确认后端服务已启动'
      } else {
        msg = `请求失败（${code}）`
      }
    }

    if (code === 401) {
      clearToken()
      clearUserSnapshot()
      unauthorizedHandler?.(msg)
    }

    return Promise.reject(new ApiError(code, msg))
  },
)

/* 便捷方法：类型参数即 data 的类型 */
export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return http.get(url, { params }) as unknown as Promise<T>
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.post(url, data, config) as unknown as Promise<T>
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return http.put(url, data, config) as unknown as Promise<T>
}

export function del<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return http.delete(url, { params }) as unknown as Promise<T>
}

export default http
