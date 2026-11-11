import { get, post, put, del } from './http'
import type { ConfigSaveRequest, ConfigVO, PublicConfig } from '@/types/config'

/**
 * 系统配置接口（FR-CFG-01/02/03）。
 *
 * `publicConfig()` 与其余三个刻意不放在同一个对象里：
 * 前者是不需要登录的公开读，后者全部要求 config:manage，
 * 混在一起容易在代码里误用。
 */
export const configApi = {
  /** 全量配置，按分组归拢（config:manage） */
  list: () => get<Record<string, ConfigVO[]>>('/config'),

  /** 批量保存（config:manage）；返回真正发生变更的项数 */
  save: (items: ConfigSaveRequest[]) => put<number>('/config', items),

  /** 新增自定义配置项（config:manage） */
  create: (body: ConfigSaveRequest) => post<ConfigVO>('/config', body),

  /** 删除自定义配置项（config:manage，内置项不可删） */
  remove: (id: number) => del<void>(`/config/${id}`),
}

/**
 * 公开配置：**无需登录**。
 * 登录页在用户还没有令牌时就要渲染站点名、公告与密码规则文案。
 */
export const publicConfigApi = {
  get: () => get<PublicConfig>('/config/public'),
}
