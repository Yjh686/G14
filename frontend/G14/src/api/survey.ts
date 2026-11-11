import { del, get, post, put } from './http'
import type { PageResult } from '@/types/api'
import type {
  AnswerInput,
  AnswerQuery,
  AnswerSheetDetailVO,
  AnswerSheetVO,
  AnswerSummaryVO,
  FillStateVO,
  HallSurveyVO,
  MyAnswerVO,
  QuestionSaveRequest,
  SurveyDetailVO,
  SurveyQuery,
  SurveySaveRequest,
  SurveyVO,
} from '@/types/survey'

/**
 * 问卷管理接口（发布者视角）。
 * 后端路径 /api/surveys/**，权限码见各方法的注释。
 */
export const surveyApi = {
  /** survey:list:self | survey:list:all */
  page: (query: SurveyQuery) => get<PageResult<SurveyVO>>('/surveys', { ...query }),

  /** survey:update:self | survey:update:all | survey:preview */
  detail: (id: number) => get<SurveyDetailVO>(`/surveys/${id}`),

  /** survey:preview */
  preview: (id: number) => get<SurveyDetailVO>(`/surveys/${id}/preview`),

  /**
   * 凭分享码取卷，需 survey:fill。
   * 已暂停/已结束/未开始也照常返回结构，由前端按 fillable 提示，
   * 直接 404 会让填写者以为链接失效。
   */
  byShareCode: (code: string) => get<SurveyDetailVO>(`/surveys/share/${code}`),

  /** survey:create */
  create: (data: SurveySaveRequest) => post<SurveyDetailVO>('/surveys', data),

  /** survey:update:self | survey:update:all */
  update: (id: number, data: SurveySaveRequest) =>
    put<SurveyDetailVO>(`/surveys/${id}`, data),

  /**
   * survey:delete:self | survey:delete:all
   * 已有答卷时必须传 confirm=true，否则 409。
   */
  remove: (id: number, confirm = false) =>
    del<null>(`/surveys/${id}`, confirm ? { confirm: 'true' } : undefined),

  /** survey:copy */
  copy: (id: number) => post<SurveyDetailVO>(`/surveys/${id}/copy`),

  /** 整卷差量保存题目：有 id 更新 / 无 id 新增 / 库里多余删除 */
  saveQuestions: (id: number, questions: QuestionSaveRequest[]) =>
    put<SurveyDetailVO>(`/surveys/${id}/questions`, { questions }),

  /** survey:publish:self | survey:publish:all */
  publish: (id: number) => post<SurveyVO>(`/surveys/${id}/publish`),

  pause: (id: number) => post<SurveyVO>(`/surveys/${id}/pause`),

  resume: (id: number) => post<SurveyVO>(`/surveys/${id}/resume`),

  close: (id: number) => post<SurveyVO>(`/surveys/${id}/close`),
}

/**
 * 学生视角：找问卷、填报、看自己的记录。
 * 后端路径 /api/hall/**。
 */
export const hallApi = {
  /** survey:fill */
  hall: (params?: { page?: number; size?: number; keyword?: string; category?: string }) =>
    get<PageResult<HallSurveyVO>>('/hall', { ...params }),

  /** survey:fill */
  fillState: (surveyId: number) => get<FillStateVO>(`/hall/surveys/${surveyId}/fill-state`),

  /** draft:save:self */
  getDraft: (surveyId: number) => get<AnswerInput[]>(`/hall/surveys/${surveyId}/draft`),

  /** draft:save:self，返回服务端记录的保存时间 */
  saveDraft: (surveyId: number, answers: AnswerInput[]) =>
    put<{ savedAt: string }>(`/hall/surveys/${surveyId}/draft`, { answers }),

  /** survey:fill */
  submit: (surveyId: number, answers: AnswerInput[]) =>
    post<AnswerSheetVO>(`/hall/surveys/${surveyId}/submit`, { answers }),

  /** answer:read:self */
  myRecords: (params?: { isValid?: number; page?: number; size?: number }) =>
    get<PageResult<MyAnswerVO>>('/hall/my-answers', { ...params }),

  /** answer:read:self —— 匿名卷只返回回执，items/questions 均为 null */
  myDetail: (sheetId: number) => get<AnswerSheetDetailVO>(`/hall/my-answers/${sheetId}`),
}

/**
 * 发布者视角：看别人的答卷、标记、删除。
 * 后端路径 /api/answers/** 与 /api/surveys/{id}/answer-summary。
 * 会碰到他人数据的接口全在这里，评审时一眼能看出风险点。
 */
export const answerApi = {
  /** answer:read:survey:self | answer:read:survey:all */
  page: (query: AnswerQuery) => get<PageResult<AnswerSheetVO>>('/answers', { ...query }),

  detail: (sheetId: number) => get<AnswerSheetDetailVO>(`/answers/${sheetId}`),

  summary: (surveyId: number) =>
    get<AnswerSummaryVO>(`/surveys/${surveyId}/answer-summary`),

  /** answer:invalidate */
  invalidate: (sheetId: number, reason?: string) =>
    put<AnswerSheetVO>(
      `/answers/${sheetId}/invalidate`,
      null,
      reason ? { params: { reason } } : undefined,
    ),

  restore: (sheetId: number) => put<AnswerSheetVO>(`/answers/${sheetId}/restore`),

  /** answer:delete —— 仅 ADMIN */
  remove: (sheetId: number) => del<null>(`/answers/${sheetId}`),
}
