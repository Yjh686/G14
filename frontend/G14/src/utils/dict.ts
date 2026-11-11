import type { RoleCode } from '@/types/api'

/** 角色显示名 */
export const ROLE_LABEL: Record<string, string> = {
  ROLE_ADMIN: '系统管理员',
  ROLE_TEACHER: '教师',
  ROLE_STUDENT: '学生',
}

/** 注册时可选的公开身份（管理员不能自助注册） */
export type RegisterRole = 'STUDENT' | 'TEACHER'

export const REGISTER_ROLES: { value: RegisterRole; label: string }[] = [
  { value: 'STUDENT', label: '学生' },
  { value: 'TEACHER', label: '教师' },
]

/** 新建用户时可选的完整角色 */
export const ALL_ROLES: { value: RoleCode; label: string }[] = [
  { value: 'ROLE_ADMIN', label: '系统管理员' },
  { value: 'ROLE_TEACHER', label: '教师' },
  { value: 'ROLE_STUDENT', label: '学生' },
]

/** 审核状态 */
export const AUDIT_STATUS_OPTIONS = [
  { value: 0, label: '待审核', type: 'warning' as const },
  { value: 1, label: '已通过', type: 'success' as const },
  { value: 2, label: '已驳回', type: 'danger' as const },
]

export function auditStatusLabel(status: number): string {
  return AUDIT_STATUS_OPTIONS.find((o) => o.value === status)?.label ?? '未知'
}

export function auditStatusType(status: number): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 1) return 'success'
  if (status === 2) return 'danger'
  if (status === 0) return 'warning'
  return 'info'
}

export function roleLabel(code: string | null | undefined): string {
  return code ? (ROLE_LABEL[code] ?? code) : '-'
}

/** 账号状态：1 启用 / 0 禁用 */
export function statusLabel(status: number): string {
  return status === 1 ? '正常' : '已禁用'
}

export function statusType(status: number): 'success' | 'danger' {
  return status === 1 ? 'success' : 'danger'
}

/**
 * 密码规则提示的兜底文案。
 *
 * 实际文案由 `useConfigStore().passwordRuleText` 按系统配置生成（FR-CFG-02），
 * 这里只是配置还没加载出来时的默认值，与后端 PasswordRules.DEFAULT 一致。
 */
export const PASSWORD_RULE_TEXT = '8~32 位，不含空格，且必须同时包含字母和数字'

/** 前端预检用的密码规则，与后端 PasswordRules 同构 */
export interface PasswordRule {
  minLength: number
  maxLength: number
  requireLetter: boolean
  requireDigit: boolean
  requireSpecial: boolean
}

/** 默认规则，与建表脚本里的内置配置项一致 */
export const DEFAULT_PASSWORD_RULE: PasswordRule = {
  minLength: 8,
  maxLength: 32,
  requireLetter: true,
  requireDigit: true,
  requireSpecial: false
}

/**
 * 与后端一致的密码校验，用于表单前端预检（后端仍会再校验一次）。
 *
 * 规则必须从调用方传进来：管理员可以在系统配置里改长度和复杂度，
 * 前端写死 8~32 的话，改动后表单会先把合规密码拦下来。
 */
export function validatePassword(
  value: string,
  rule: PasswordRule = DEFAULT_PASSWORD_RULE,
): string | null {
  if (!value) return '请输入密码'
  if (value.length < rule.minLength || value.length > rule.maxLength) {
    return `密码长度需为 ${rule.minLength}~${rule.maxLength} 位`
  }
  if (/\s/.test(value)) return '密码不能包含空格'
  const hasLetter = /[A-Za-z]/.test(value)
  const hasDigit = /\d/.test(value)
  const hasSpecial = /[^A-Za-z0-9]/.test(value)
  if (rule.requireLetter && !hasLetter) {
    return rule.requireDigit ? '密码必须同时包含字母和数字' : '密码必须包含字母'
  }
  if (rule.requireDigit && !hasDigit) {
    return rule.requireLetter ? '密码必须同时包含字母和数字' : '密码必须包含数字'
  }
  if (rule.requireSpecial && !hasSpecial) return '密码必须包含特殊字符（如 !@#$%）'
  return null
}

/** 手机号：可空，非空时校验格式 */
export function validatePhone(value: string): string | null {
  if (!value) return null
  return /^1[3-9]\d{9}$/.test(value) ? null : '手机号格式不正确'
}

/** 学号/工号：4~64 位字母数字下划线点短横线 */
export function validateUsername(value: string): string | null {
  if (!value) return '请输入学号/工号'
  if (value.length < 4 || value.length > 64) return '账号长度需为 4~64 位'
  return /^[A-Za-z0-9_.-]+$/.test(value) ? null : '账号只能包含字母、数字、下划线、点或短横线'
}

/* ========================= 问卷与答题 ========================= */

/** 问卷状态 */
export const SURVEY_STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿', type: 'info' as const },
  { value: 'PUBLISHED', label: '收集中', type: 'success' as const },
  { value: 'PAUSED', label: '已暂停', type: 'warning' as const },
  { value: 'CLOSED', label: '已结束', type: 'danger' as const },
]

export function surveyStatusLabel(status: string | null | undefined): string {
  return SURVEY_STATUS_OPTIONS.find((o) => o.value === status)?.label ?? status ?? '-'
}

export function surveyStatusType(
  status: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' {
  return SURVEY_STATUS_OPTIONS.find((o) => o.value === status)?.type ?? 'info'
}

/** 问卷分类，与后端 SurveyCategory 一致 */
export const SURVEY_CATEGORIES = [
  { value: 'TEACHING', label: '教学反馈' },
  { value: 'ACTIVITY', label: '活动报名' },
  { value: 'SATISFACTION', label: '满意度' },
  { value: 'OTHER', label: '其他' },
]

export function categoryLabel(code: string | null | undefined): string {
  return SURVEY_CATEGORIES.find((o) => o.value === code)?.label ?? (code || '其他')
}

/** 题型，与后端 QuestionType 一致 */
export const QUESTION_TYPES = [
  { value: 'SINGLE', label: '单选题', needOptions: true, isText: false },
  { value: 'MULTIPLE', label: '多选题', needOptions: true, isText: false },
  { value: 'TEXT', label: '填空题', needOptions: false, isText: true },
  { value: 'TEXTAREA', label: '多行文本', needOptions: false, isText: true },
  { value: 'RATING', label: '评分题', needOptions: false, isText: false },
]

export function questionTypeLabel(code: string | null | undefined): string {
  return QUESTION_TYPES.find((o) => o.value === code)?.label ?? (code || '-')
}

export function needOptions(type: string): boolean {
  return QUESTION_TYPES.find((o) => o.value === type)?.needOptions ?? false
}

export function isTextType(type: string): boolean {
  return QUESTION_TYPES.find((o) => o.value === type)?.isText ?? false
}

/** 大厅卡片上的「我和这份问卷的关系」 */
export const MY_STATUS_OPTIONS = [
  { value: 'NOT_STARTED', label: '未填写', type: 'info' as const },
  { value: 'DRAFT', label: '草稿', type: 'warning' as const },
  { value: 'SUBMITTED', label: '已提交', type: 'success' as const },
]

export function myStatusLabel(status: string | null | undefined): string {
  return MY_STATUS_OPTIONS.find((o) => o.value === status)?.label ?? '-'
}

export function myStatusType(
  status: string | null | undefined,
): 'success' | 'warning' | 'info' {
  return MY_STATUS_OPTIONS.find((o) => o.value === status)?.type ?? 'info'
}

/**
 * fill-state 返回的 reason 机器码 → 中文提示。
 * reason 为空串表示可填。
 */
export function fillReasonText(reason: string | null | undefined): string {
  switch (reason) {
    case 'NOT_STARTED':
      return '问卷尚未开始'
    case 'PAUSED':
      return '问卷已暂停回收'
    case 'CLOSED':
      return '问卷已结束'
    case 'EXPIRED':
      return '问卷已过截止时间'
    case 'ALREADY_SUBMITTED':
      return '你已提交过该问卷'
    default:
      return ''
  }
}

/* ------------------------------------------------------------------ */
/* 模板中心                                                            */
/* ------------------------------------------------------------------ */

export const TEMPLATE_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待审核', type: 'warning' as const },
  { value: 'APPROVED', label: '已通过', type: 'success' as const },
  { value: 'REJECTED', label: '已驳回', type: 'danger' as const },
  { value: 'OFFLINE', label: '已下架', type: 'info' as const },
]

export function templateStatusLabel(status: string | null | undefined): string {
  return TEMPLATE_STATUS_OPTIONS.find((o) => o.value === status)?.label ?? (status || '-')
}

export function templateStatusType(
  status: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' {
  return TEMPLATE_STATUS_OPTIONS.find((o) => o.value === status)?.type ?? 'info'
}

/** 模板中心排序方式 */
export const TEMPLATE_ORDERS = [
  { value: 'rating', label: '评分优先' },
  { value: 'latest', label: '最新发布' },
  { value: 'used', label: '最多使用' },
]

/** 无人评分时的展示文案（R-TPL-2） */
export const NO_RATING_TEXT = '暂无评分'

/**
 * 评分平均值文案。
 * 后端在无人评分时返回 null 而不是 0，这里必须区分开：
 * 0 分意味着「所有人都打了最低分」，和「还没人评」是两件事。
 */
export function ratingText(avg: number | null | undefined, count: number | null | undefined): string {
  if (avg === null || avg === undefined) return NO_RATING_TEXT
  const n = count ?? 0
  return `${avg.toFixed(1)} 分（${n} 人）`
}