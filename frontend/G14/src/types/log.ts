/**
 * 日志查询相关类型（FR-LOG-01/02/03）。
 *
 * 后端 `/api/logs/**` 只有 GET，没有 PUT/DELETE —— 日志只读是需求约束，
 * 不是接口没写完。所以这里也不提供任何「删除日志」的方法。
 */

/** 登录日志行。userId 可能为 null（账号不存在的失败尝试），但 username 一定有 */
export interface LoginLogVO {
  id: number
  userId: number | null
  username: string
  /** 1=成功 0=失败 */
  result: number
  /** 成功时是「登录成功」，失败时是具体原因 */
  reason: string | null
  ip: string | null
  userAgent: string | null
  createTime: string
}

/** 操作日志行。userId 直接暴露是刻意的：审计需要知道「谁做了什么」 */
export interface OperationLogVO {
  id: number
  userId: number | null
  username: string | null
  /** 模块，如「问卷管理」「用户管理」「数据导出」 */
  module: string | null
  /** 动作，如「发布问卷」「重置密码」 */
  action: string | null
  targetType: string | null
  targetId: number | null
  detail: string | null
  /** 1=成功 0=失败 */
  result: number
  ip: string | null
  createTime: string
}

/** 查询条件，登录日志与操作日志共用 */
export interface LogQuery {
  keyword?: string
  /** 1=成功 0=失败 */
  result?: number
  /** 操作日志专用 */
  module?: string
  /** 操作日志专用 */
  action?: string
  /** yyyy-MM-dd HH:mm:ss，含边界 */
  startTime?: string
  endTime?: string
  page?: number
  size?: number
}
