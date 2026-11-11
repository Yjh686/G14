import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PASSWORD_RULE,
  auditStatusLabel,
  auditStatusType,
  roleLabel,
  statusLabel,
  validatePassword,
  validatePhone,
  validateUsername,
} from '@/utils/dict'

describe('validatePassword', () => {
  it('接受字母数字组合且长度合规的密码', () => {
    expect(validatePassword('Admin@123')).toBeNull()
    expect(validatePassword('abc12345')).toBeNull()
  })

  it('拒绝过短或过长的密码', () => {
    expect(validatePassword('a1')).toBe('密码长度需为 8~32 位')
    expect(validatePassword('a1'.repeat(20))).toBe('密码长度需为 8~32 位')
  })

  it('拒绝含空格的密码', () => {
    expect(validatePassword('abc 12345')).toBe('密码不能包含空格')
  })

  it('默认规则要求字母+数字，缺一即提示「同时包含」', () => {
    // 默认规则 requireLetter 与 requireDigit 都为 true，
    // 只说「必须包含字母」会让用户以为补个字母就够了
    expect(validatePassword('12345678')).toBe('密码必须同时包含字母和数字')
    expect(validatePassword('abcdefgh')).toBe('密码必须同时包含字母和数字')
  })

  it('只开单项要求时说单项文案', () => {
    const onlyLetter = { ...DEFAULT_PASSWORD_RULE, requireDigit: false }
    expect(validatePassword('12345678', onlyLetter)).toBe('密码必须包含字母')
    const onlyDigit = { ...DEFAULT_PASSWORD_RULE, requireLetter: false }
    expect(validatePassword('abcdefgh', onlyDigit)).toBe('密码必须包含数字')
  })

  it('规则跟着系统配置走，不再是写死的 8~32', () => {
    const strict = { ...DEFAULT_PASSWORD_RULE, minLength: 12, requireSpecial: true }
    expect(validatePassword('abc12345', strict)).toBe('密码长度需为 12~32 位')
    expect(validatePassword('abcdefgh1234', strict)).toBe('密码必须包含特殊字符（如 !@#$%）')
    expect(validatePassword('abcdefgh1234!', strict)).toBeNull()
  })
})

describe('validateUsername', () => {
  it('接受合法学号工号', () => {
    expect(validateUsername('20230001')).toBeNull()
    expect(validateUsername('t_zhang.1-2')).toBeNull()
  })

  it('拒绝空值与非法字符', () => {
    expect(validateUsername('')).toBe('请输入学号/工号')
    expect(validateUsername('ab')).toBe('账号长度需为 4~64 位')
    expect(validateUsername('张三同学')).toBe('账号只能包含字母、数字、下划线、点或短横线')
  })
})

describe('validatePhone', () => {
  it('允许留空', () => {
    expect(validatePhone('')).toBeNull()
  })

  it('校验手机号格式', () => {
    expect(validatePhone('13800138000')).toBeNull()
    expect(validatePhone('12345')).toBe('手机号格式不正确')
  })
})

describe('字典展示函数', () => {
  it('角色与状态文案', () => {
    expect(roleLabel('ROLE_ADMIN')).toBe('系统管理员')
    expect(roleLabel('ROLE_UNKNOWN')).toBe('ROLE_UNKNOWN')
    expect(roleLabel(null)).toBe('-')
    expect(statusLabel(1)).toBe('正常')
    expect(statusLabel(0)).toBe('已禁用')
  })

  it('审核状态文案与标签色', () => {
    expect(auditStatusLabel(0)).toBe('待审核')
    expect(auditStatusLabel(2)).toBe('已驳回')
    expect(auditStatusType(1)).toBe('success')
    expect(auditStatusType(2)).toBe('danger')
  })
})
