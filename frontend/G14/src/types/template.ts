import type { PageResult } from './api'


/** 模板审核状态，与后端 TemplateStatus 一致 */
export type TemplateStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'OFFLINE'

/**
 * 模板里的一道题。
 *
 * 与 `types/survey.ts` 的 `QuestionVO` 有个关键区别：
 * 这里**没有 ID**，选项也只是文本数组。
 * 模板是问卷的一份快照（R-TPL-1），刻意不保留任何指向来源问卷的引用，
 * 否则来源问卷改了删了，模板就会指向不存在的题目与选项。
 */
export interface TemplateQuestionVO {
  questionType: string
  title: string
  description?: string | null
  /** Integer(1/0)，不是 boolean */
  isRequired?: number | null
  maxLength?: number | null
  ratingMax?: number | null
  sortNo: number
  /** 选项文本，不是 {id, content} 对象 */
  options?: string[] | null
}

export interface TemplateVO {
  id: number
  title: string
  description?: string | null
  category: string
  categoryLabel?: string | null
  sourceSurveyId?: number | null
  questionCount: number
  /** Integer(1/0) */
  anonymousDefault?: number | null
  limitOnceDefault?: number | null
  status: TemplateStatus
  statusLabel?: string | null
  auditRemark?: string | null
  auditBy?: number | null
  auditByName?: string | null
  auditTime?: string | null
  submitTime?: string | null
  ratingCount: number
  /** 无人评分时为 null，不是 0 —— 前端要显示「暂无评分」 */
  ratingAvg?: number | null
  /** 当前用户已给出的评分，没评过为 null */
  myScore?: number | null
  useCount: number
  createBy?: number | null
  createByName?: string | null
  createTime?: string | null
  updateTime?: string | null
  /** 仅详情接口返回，列表接口为 null */
  structure?: TemplateQuestionVO[] | null
}

export type TemplatePage = PageResult<TemplateVO>

export interface ShareTemplateRequest {
  sourceSurveyId: number
  title: string
  description?: string | null
  category?: string | null
  anonymousDefault?: number | null
  limitOnceDefault?: number | null
}

export interface AuditTemplateRequest {
  auditStatus: 'APPROVED' | 'REJECTED'
  auditRemark?: string | null
}

export interface TemplateQuery {
  keyword?: string
  category?: string
  status?: string
  /** browse（默认）/ mine / audit */
  scope?: 'browse' | 'mine' | 'audit'
  /** rating（默认）/ latest / used */
  order?: string
  page?: number
  size?: number
}
