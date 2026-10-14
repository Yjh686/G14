// 账号格式辅助校验（仅用于前端输入引导，最终以"后端自动识别角色"为准）
const DIGITS_RE = /^\d+$/
const PHONE_RE = /^1\d{10}$/ // 大陆手机号

// 学生：学号一般为纯数字（如 8 位）
export function isStudentAccount(account) {
  return DIGITS_RE.test(account) && account.length >= 4
}

// 老师：工号（纯数字）或手机号
export function isTeacherAccount(account) {
  return DIGITS_RE.test(account) || PHONE_RE.test(account)
}

// 校验规则：根据所选登录身份返回 el-form 校验函数
export function accountValidatorByRole(role) {
  return (rule, value, callback) => {
    if (!value) return callback(new Error('请输入登录账号'))
    if (role === 'STUDENT') {
      if (!DIGITS_RE.test(value)) return callback(new Error('学生请使用学号（纯数字）登录'))
    } else if (role === 'TEACHER') {
      if (!DIGITS_RE.test(value) && !PHONE_RE.test(value)) {
        return callback(new Error('老师请使用工号或手机号登录'))
      }
    }
    callback()
  }
}
