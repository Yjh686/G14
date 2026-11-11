/**
 * 令牌与「当前用户」快照的本地存储。
 *
 * 单独抽出来而不是写在 store 里，是为了让 axios 拦截器可以直接读到 token，
 * 不必反向依赖 Pinia（避免 store ← api ← store 的循环引用）。
 */

const TOKEN_KEY = 'g14_token'
const USER_KEY = 'g14_user'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 用户信息快照，仅用于刷新页面时先渲染出昵称，随后由 /auth/me 覆盖 */
export function readUserSnapshot<T>(): T | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function writeUserSnapshot(user: unknown): void {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearUserSnapshot(): void {
  localStorage.removeItem(USER_KEY)
}
