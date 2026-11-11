import { del, get, post, put } from './http'
import type { PageResult } from '@/types/api'
import type { SurveyDetailVO } from '@/types/survey'
import type {
  AuditTemplateRequest,
  ShareTemplateRequest,
  TemplateQuery,
  TemplateVO,
} from '@/types/template'

/**
 * 模板中心接口。
 * 后端路径 /api/templates/**，权限码见各方法注释。
 *
 * 注意所有列表接口共用同一个 GET /templates，靠 scope 区分视角：
 * browse（模板中心，强制只看已通过）/ mine（我共享的）/ audit（审核队列）。
 * 服务端会再校验一次对应权限 —— 前端传 scope 不代表有权限。
 */
export const templateApi = {
  /** template:browse | template:share | template:audit */
  page: (query: TemplateQuery) => get<PageResult<TemplateVO>>('/templates', { ...query }),

  /** 详情含 structure；未过审的模板对非本人返回 404 */
  detail: (id: number) => get<TemplateVO>(`/templates/${id}`),

  /** template:share —— 把自己的问卷共享为模板 */
  share: (data: ShareTemplateRequest) => post<TemplateVO>('/templates', data),

  /** template:share —— 重新提交被驳回的模板 */
  resubmit: (id: number) => post<TemplateVO>(`/templates/${id}/resubmit`),

  /** template:offline */
  offline: (id: number) => post<TemplateVO>(`/templates/${id}/offline`),

  /** template:share —— 只能删已驳回或已下架的 */
  remove: (id: number) => del<null>(`/templates/${id}`),

  /** template:audit —— 驳回时 auditRemark 必填 */
  audit: (id: number, data: AuditTemplateRequest) =>
    put<TemplateVO>(`/templates/${id}/audit`, data),

  /** template:use —— 复制为我的新问卷草稿，返回新问卷详情 */
  use: (id: number) => post<SurveyDetailVO>(`/templates/${id}/use`),

  /** template:rate —— 1~5 星，一人一票，重复调用即改分 */
  rate: (id: number, score: number) =>
    put<TemplateVO>(`/templates/${id}/rating`, null, { params: { score } }),
}
