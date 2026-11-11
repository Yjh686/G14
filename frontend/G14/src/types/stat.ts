/**
 * 统计与导出的类型定义。
 *
 * 与后端 module/stat/dto 一一对应，改后端 DTO 时这里必须同步。
 *
 * 三个口径必须记住（UI 上也要写清楚，否则用户会以为算错了）：
 * 1. 所有数字只统计「有效答卷」，无效答卷不计入；
 * 2. 选择题占比的分母是「该题的有效作答人数」，不是回收总数，
 *    所以多选题各选项占比相加会超过 100%；
 * 3. 没人作答时返回 0 而不是 NaN，前端直接展示「暂无数据」。
 */

import type { QuestionType, SurveyCategory, SurveyStatus } from './survey'

/** 单个选项的统计 */
export interface OptionStatsVO {
  optionId: number
  content: string
  count: number
  /** 0~1，分母是该题有效作答人数 */
  ratio: number
}

/** 一条文本作答（不含填写人） */
export interface TextAnswerVO {
  text: string
  submitTime: string | null
}

export interface WordFreqVO {
  word: string
  count: number
}

export interface TextStatsVO {
  answeredCount: number
  answers: TextAnswerVO[]
  /** 超过上限只回前 200 条，提示「完整内容请导出」 */
  truncated: boolean
  words: WordFreqVO[]
}

export interface RatingBucketVO {
  star: number
  count: number
  ratio: number
}

export interface RatingStatsVO {
  answeredCount: number
  average: number
  ratingMax: number
  /** 按 1..ratingMax 全档给出，没被选过的档 count=0 */
  distribution: RatingBucketVO[]
}

/** 单题统计：按 questionType 决定 options / text / rating 哪个有值 */
export interface QuestionStatsVO {
  questionId: number
  sortNo: number
  questionType: QuestionType
  title: string
  description: string | null
  answeredCount: number
  validSheetCount: number
  options: OptionStatsVO[] | null
  text: TextStatsVO | null
  rating: RatingStatsVO | null
}

/** 整卷统计 */
export interface SurveyStatsVO {
  surveyId: number
  surveyTitle: string
  category: SurveyCategory
  categoryLabel: string
  status: SurveyStatus
  anonymous: boolean
  totalSheetCount: number
  validSheetCount: number
  publishedTime: string | null
  generatedAt: string
  questions: QuestionStatsVO[]
}
