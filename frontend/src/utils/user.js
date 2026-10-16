// 后端 UserVO 字段名是 realName，而页面模板历史上写的是 name。
// 统一在这一处补齐两个字段，避免每个组件都写 `user.name || user.realName`。
export function normalizeUser(raw) {
  if (!raw) return null
  const name = raw.name ?? raw.realName ?? ''
  return { ...raw, name, realName: raw.realName ?? raw.name ?? '' }
}

export function normalizeUsers(list) {
  return Array.isArray(list) ? list.map(normalizeUser) : []
}
