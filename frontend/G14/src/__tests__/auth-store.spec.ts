import { beforeEach, describe, expect, it } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

/** 造一个最小可用的登录响应 */
function loginPayload(perms: string[], roleCode = 'ROLE_STUDENT' as const) {
  return {
    token: 'fake-token',
    expiresIn: 43200,
    userId: 9,
    username: '20230001',
    realName: '李同学',
    roleCode,
    roleName: '学生',
    mustChangePassword: false,
    permissions: perms,
  }
}

describe('useAuthStore 权限判定', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('登录后暴露权限与角色', () => {
    const auth = useAuthStore()
    auth.applyAuth(loginPayload(['survey:fill', 'auth:login']))

    expect(auth.isLoggedIn).toBe(true)
    expect(auth.displayName).toBe('李同学')
    expect(auth.roleCode).toBe('ROLE_STUDENT')
    expect(auth.permissions).toHaveLength(2)
  })

  it('权限判定只有单项与任一匹配两种语义', () => {
    const auth = useAuthStore()
    auth.applyAuth(loginPayload(['survey:fill', 'draft:save:self']))

    expect(auth.hasPerm('survey:fill')).toBe(true)
    expect(auth.hasPerm('survey:create')).toBe(false)
    expect(auth.hasAnyPerm(['survey:create', 'survey:fill'])).toBe(true)
    expect(auth.hasAnyPerm(['survey:create', 'user:list'])).toBe(false)
  })

  it('角色不是权限：学生追加 survey:create 后角色仍是学生', () => {
    const auth = useAuthStore()
    auth.applyAuth(loginPayload(['survey:fill', 'survey:create']))

    expect(auth.roleCode).toBe('ROLE_STUDENT')
    expect(auth.isAdmin).toBe(false)
    expect(auth.hasPerm('survey:create')).toBe(true)
  })

  it('clear 会清掉内存与本地存储', () => {
    const auth = useAuthStore()
    auth.applyAuth(loginPayload(['survey:fill']))
    expect(localStorage.getItem('g14_token')).toBe('fake-token')

    auth.clear()
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.user).toBeNull()
    expect(auth.permissions).toHaveLength(0)
    expect(localStorage.getItem('g14_token')).toBeNull()
  })
})
