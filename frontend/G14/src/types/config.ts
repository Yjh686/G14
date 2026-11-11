/**
 * 系统配置（FR-CFG）。
 *
 * 两类数据形状不同，用途也不同：
 * - `ConfigVO`：管理员视角，带类型/分组/是否公开/是否内置，密码类值被打码
 * - `PublicConfig`：公开键值对，登录页在无令牌时读它渲染站点名与密码规则
 */

/** STRING / TEXT / NUMBER / BOOLEAN / PASSWORD */
export type ConfigType = 'STRING' | 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'PASSWORD'

/** SITE / SECURITY / OTHER */
export type ConfigGroup = 'SITE' | 'SECURITY' | 'OTHER'

export interface ConfigVO {
  id: number
  configKey: string
  configType: ConfigType
  groupName: ConfigGroup
  description: string | null
  /** PASSWORD 类型返回 `******` 而不是真实值 */
  configValue: string
  /** 值是否被打码；true 时把值原样提交回去 = 不修改 */
  masked: boolean
  publicVisible: boolean
  /** 内置项可改不可删 */
  builtin: boolean
  updateTime: string | null
}

export interface ConfigSaveRequest {
  configKey: string
  configValue?: string
  configType?: ConfigType
  groupName?: ConfigGroup
  description?: string
  publicVisible?: boolean
}

/** GET /api/config/public 的返回：只有 is_public=1 的项 */
export type PublicConfig = Record<string, string>

/** 密码掩码，前后端约定同一个字面量 */
export const PASSWORD_MASK = '******'

/** 分组的中文名 */
export const CONFIG_GROUP_LABEL: Record<ConfigGroup, string> = {
  SITE: '站点信息',
  SECURITY: '安全策略',
  OTHER: '其他'
}
