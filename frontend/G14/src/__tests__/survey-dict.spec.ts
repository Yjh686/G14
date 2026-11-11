import { describe, expect, it } from 'vitest'
import {
  categoryLabel,
  fillReasonText,
  isTextType,
  myStatusLabel,
  myStatusType,
  needOptions,
  questionTypeLabel,
  surveyStatusLabel,
  surveyStatusType,
} from '@/utils/dict'

describe('问卷状态字典', () => {
  it('已知状态返回中文名与类型', () => {
    expect(surveyStatusLabel('DRAFT')).toBe('草稿')
    expect(surveyStatusLabel('PUBLISHED')).toBe('收集中')
    expect(surveyStatusLabel('PAUSED')).toBe('已暂停')
    expect(surveyStatusLabel('CLOSED')).toBe('已结束')
    expect(surveyStatusType('PUBLISHED')).toBe('success')
    expect(surveyStatusType('CLOSED')).toBe('danger')
  })

  it('未知状态回退到原值，不抛错', () => {
    expect(surveyStatusLabel('WHATEVER')).toBe('WHATEVER')
    expect(surveyStatusLabel(null)).toBe('-')
    expect(surveyStatusType('WHATEVER')).toBe('info')
  })
})

describe('问卷分类字典', () => {
  it('按后端枚举返回中文', () => {
    expect(categoryLabel('TEACHING')).toBe('教学反馈')
    expect(categoryLabel('SATISFACTION')).toBe('满意度')
  })

  it('未知分类回退为「其他」', () => {
    expect(categoryLabel('NOPE')).toBe('NOPE')
    expect(categoryLabel(null)).toBe('其他')
  })
})

describe('题型字典', () => {
  it('中文名对应正确', () => {
    expect(questionTypeLabel('SINGLE')).toBe('单选题')
    expect(questionTypeLabel('RATING')).toBe('评分题')
  })

  it('只有单选多选需要选项', () => {
    expect(needOptions('SINGLE')).toBe(true)
    expect(needOptions('MULTIPLE')).toBe(true)
    expect(needOptions('TEXT')).toBe(false)
    expect(needOptions('RATING')).toBe(false)
    expect(needOptions('UNKNOWN')).toBe(false)
  })

  it('只有填空与多行文本算文本题', () => {
    expect(isTextType('TEXT')).toBe(true)
    expect(isTextType('TEXTAREA')).toBe(true)
    expect(isTextType('SINGLE')).toBe(false)
    expect(isTextType('RATING')).toBe(false)
  })
})

describe('大厅卡片状态', () => {
  it('三种 myStatus 都有中文与类型', () => {
    expect(myStatusLabel('NOT_STARTED')).toBe('未填写')
    expect(myStatusLabel('DRAFT')).toBe('草稿')
    expect(myStatusLabel('SUBMITTED')).toBe('已提交')
    expect(myStatusType('SUBMITTED')).toBe('success')
    expect(myStatusType('DRAFT')).toBe('warning')
  })
})

describe('填写失败原因机器码', () => {
  it('每个码都有对应文案', () => {
    expect(fillReasonText('NOT_STARTED')).toBe('问卷尚未开始')
    expect(fillReasonText('PAUSED')).toBe('问卷已暂停回收')
    expect(fillReasonText('CLOSED')).toBe('问卷已结束')
    expect(fillReasonText('EXPIRED')).toBe('问卷已过截止时间')
    expect(fillReasonText('ALREADY_SUBMITTED')).toBe('你已提交过该问卷')
  })

  it('空值返回空串，表示可以填写', () => {
    expect(fillReasonText('')).toBe('')
    expect(fillReasonText(null)).toBe('')
  })
})
