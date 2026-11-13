import { get, post, put } from './http'

/**
 * 密保问题与密码找回。
 *
 * <p>分成两个对象是刻意的：
 * <ul>
 *   <li>{@link recoveryPublicApi} —— 两步都<b>免登录</b>，忘记密码的人用它。</li>
 *   <li>{@link recoveryApi} —— 设置/查看自己的密保，<b>需登录</b>。</li>
 * </ul>
 * 混在一个对象里，很容易在登录页误引到"设置密保"（必须带 token），
 * 报 401 之后还要排查一轮才明白是调错了接口。
 */
export interface RecoveryQuestions {
  username: string
  question1: string
  question2: string
}

export interface RecoveryLookupRequest {
  username: string
}

export interface RecoveryResetRequest {
  username: string
  answer1: string
  answer2: string
  newPassword: string
}

export interface RecoverySetupItem {
  question: string
  answer: string
}

export interface RecoverySetupRequest {
  items: RecoverySetupItem[]
}

/** 免登录：用账号换密保问题、答题后重设密码 */
export const recoveryPublicApi = {
  lookup: (data: RecoveryLookupRequest) =>
    post<RecoveryQuestions>('/auth/recovery/lookup', data),

  reset: (data: RecoveryResetRequest) => post<null>('/auth/recovery/reset', data),
}

/** 需登录：我是否设置过、查看题面、设置/修改 */
export const recoveryApi = {
  /** 是否已设置密保问题（决定个人资料页显示「设置」还是「修改」） */
  mine: () => get<boolean>('/auth/recovery/mine'),

  /** 我设置的两个问题题面（不含答案） */
  myQuestions: () => get<RecoveryQuestions>('/auth/recovery/my-questions'),

  setup: (data: RecoverySetupRequest) => put<null>('/auth/recovery/setup', data),
}
