import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { publicConfigApi } from '@/api/config'

/**
 * 公开配置（FR-CFG-03）。
 *
 * 只承载 `is_public=1` 的键值对：站点名、公告、页脚、密码规则。
 * 默认密码这类私有项后端根本不下发，所以这里也不用担心误存。
 *
 * 为什么用 Pinia 而不是直接在每个页面调接口：
 * 站点名要出现在登录页、侧边栏、浏览器标题三处，各调一次会闪三下，
 * 而且标题在路由守卫里改，那里没有组件实例可用。
 */
export const useConfigStore = defineStore('config', () => {
  const data = ref<Record<string, string>>({})
  const loaded = ref(false)
  let pending: Promise<void> | null = null

  /** 站点名，未加载时的兜底与 index.html 的标题一致 */
  const siteName = computed(() => data.value['site.name'] || '校园问卷系统')

  /** 公告：空串表示不显示公告条 */
  const announcement = computed(() => data.value['site.announcement'] || '')

  const footer = computed(() => data.value['site.footer'] || '')

  const logo = computed(() => data.value['site.logo'] || '')

  /** 密码规则（长度 + 复杂度开关），与后端 PasswordRules 同构 */
  const passwordRules = computed(() => ({
    minLength: toInt(data.value['security.password.min-length'], 8),
    maxLength: toInt(data.value['security.password.max-length'], 32),
    requireLetter: isOn(data.value['security.password.require-letter']),
    requireDigit: isOn(data.value['security.password.require-digit']),
    requireSpecial: isOn(data.value['security.password.require-special'])
  }))

  /** 把规则渲染成一句话，用于表单提示 */
  const passwordRuleText = computed(() => {
    const r = passwordRules.value
    const parts: string[] = []
    if (r.requireLetter && r.requireDigit) parts.push('必须同时包含字母和数字')
    else if (r.requireLetter) parts.push('必须包含字母')
    else if (r.requireDigit) parts.push('必须包含数字')
    if (r.requireSpecial) parts.push('必须包含特殊字符')
    const tail = parts.length ? '，且' + parts.join('、') : ''
    return `${r.minLength}~${r.maxLength} 位，不含空格${tail}`
  })

  /**
   * 加载公开配置。失败时不抛错：登录页不能因为公告接口挂了就打不开，
   * 站点名会退回默认值。`loaded` 保持 false，下次调用还会重试。
   */
  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return
    if (pending) return pending
    pending = publicConfigApi
      .get()
      .then((res) => {
        data.value = res ?? {}
        loaded.value = true
      })
      .catch(() => {
        /* 静默失败：调用方用兜底值 */
      })
      .finally(() => {
        pending = null
      })
    return pending
  }

  /** 管理员保存配置后调用，让各处（标题、侧边栏、规则文案）立即跟上 */
  function apply(next: Record<string, string>): void {
    data.value = next
    loaded.value = true
  }

  return {
    data,
    loaded,
    siteName,
    announcement,
    footer,
    logo,
    passwordRules,
    passwordRuleText,
    load,
    apply
  }
})

function toInt(v: string | undefined, def: number): number {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : def
}

function isOn(v: string | undefined): boolean {
  return v === '1' || v === 'true' || v === 'yes'
}
