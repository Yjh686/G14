/**
 * 问卷与答题相关的类型定义。
 *
 * 字段与后端 DTO 一一对应，改后端 DTO 时这里必须同步。
 * 注意后端的 anonymous / limitOnce / isRequired 是 Integer（1/0）而非 boolean，
 * 这是为了和 MySQL 的 TINYINT 对齐；前端判断时统一用 `=== 1`。
 */

export type SurveyStatus = 'DRAFT' | 'PUBLISHED' | 'PAUSED' | 'CLOSED'

export type SurveyCategory = 'TEACHING' | 'ACTIVITY' | 'SATISFACTION' | 'OTHER'

export type QuestionType = 'SINGLE' | 'MULTIPLE' | 'TEXT' | 'TEXTAREA' | 'RATING'

/** 大厅卡片上「我和这份问卷的关系」 */
export type MyStatus = 'NOT_STARTED' | 'DRAFT' | 'SUBMITTED'

/* ------------------------- 问卷结构 ------------------------- */

export interface OptionVO {
  id: number
  content: string
  sortNo: number
}

export interface QuestionVO {
  id: number
  questionType: QuestionType
  title: string
  description: string | null
  isRequired: number
  maxLength: number | null
  ratingMax: number | null
  sortNo: number
  options: OptionVO[]
}

export interface SurveyVO {
  id: number
  title: string
  description: string | null
  category: SurveyCategory
  categoryLabel: string
  anonymous: number
  status: SurveyStatus
  shareCode: string | null
  startTime: string | null
  endTime: string | null
  limitOnce: number
  questionCount: number
  answerCount: number
  publishedTime: string | null
  closedTime: string | null
  createBy: number
  createByName: string | null
  createTime: string
  updateTime: string
  /** 后端算好的「现在能不能填」：status=PUBLISHED 且当前时间在 [startTime, endTime) 内 */
  fillable: boolean
}

export interface SurveyDetailVO extends SurveyVO {
  questions: QuestionVO[]
}

/* ------------------------- 问卷请求体 ------------------------- */

export interface SurveyQuery {
  keyword?: string
  status?: string
  category?: string
  /** mine=只看自己的（默认）；all=全量，需要 survey:list:all */
  scope?: 'mine' | 'all'
  page?: number
  size?: number
}

export interface OptionSaveRequest {
  content: string
}

export interface QuestionSaveRequest {
  /** 有 id 表示更新已有题目，无 id 表示新增 */
  id?: number
  questionType: string
  title: string
  description?: string
  isRequired?: number
  maxLength?: number
  ratingMax?: number
  options?: OptionSaveRequest[]
}

export interface SurveySaveRequest {
  title: string
  description?: string
  category?: string
  anonymous?: number
  limitOnce?: number
  /** 格式 yyyy-MM-dd HH:mm:ss，null 表示立即开始 / 长期有效 */
  startTime?: string | null
  endTime?: string | null
}

/* ------------------------- 答题 ------------------------- */

/** 一道题的作答，提交与草稿共用 */
export interface AnswerInput {
  questionId: number
  optionValues?: number[] | null
  textValue?: string | null
  numberValue?: number | null
}

export interface HallSurveyVO extends SurveyVO {
  myStatus: MyStatus
  draftTime: string | null
  submitTime: string | null
}

/**
 * 进入填写页前的状态。
 * reason 是机器码，前端按码决定文案与按钮，不要解析中文。
 */
export interface FillStateVO {
  surveyId: number
  surveyTitle: string
  fillable: boolean
  /** "" | NOT_STARTED | PAUSED | CLOSED | EXPIRED | ALREADY_SUBMITTED */
  reason: string
  submitted: boolean
  sheetId: number | null
  submitTime: string | null
  draftAnswers: AnswerInput[] | null
  draftTime: string | null
}

export interface AnswerSheetVO {
  id: number
  surveyId: number
  surveyTitle: string
  anonymous: boolean
  submitTime: string
  isValid: number
  invalidReason: string | null
  /** 匿名问卷恒为 null，后端裁剪，不是前端隐藏 */
  userId: number | null
  fillerName: string | null
  fillerUsername: string | null
}

export interface QuestionAnswerVO {
  questionId: number
  questionType: QuestionType
  questionTitle: string
  optionValues: number[] | null
  /** 选项文本已由后端翻译好，前端不用再查字典 */
  optionTexts: string[] | null
  textValue: string | null
  numberValue: number | null
  answered: boolean
}

export interface AnswerSheetDetailVO extends AnswerSheetVO {
  items: QuestionAnswerVO[] | null
  questions: QuestionVO[] | null
}

export interface MyAnswerVO {
  sheetId: number
  surveyId: number
  surveyTitle: string
  surveyStatus: SurveyStatus
  submitTime: string
  isValid: number
  /** 匿名卷为 false，前端据此隐藏「查看详情」 */
  contentVisible: boolean
}

export interface AnswerQuery {
  surveyId: number
  isValid?: number
  order?: 'submit_desc' | 'submit_asc'
  page?: number
  size?: number
}

export interface AnswerSummaryVO {
  surveyId: number
  surveyTitle: string
  /** 已回收答卷数（含无效） */
  total: number
  /** 有效答卷数，= survey.answer_count */
  validCount: number
  invalidCount: number
  /** 有效数 ÷ 应填人数，保留 4 位小数；分母为 0 时返回 0 */
  completionRate: number
  /** 分母（启用状态的学生账号总数，近似口径） */
  expectedCount: number
  anonymous: boolean
}
