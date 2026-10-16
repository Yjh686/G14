// 账号格式辅助校验
//
// 设计前提：**角色由后端查库自动识别**（见后端 LoginRequest —— 请求体只有
// account + password，没有 role 字段）。登录页的身份 Tab 只是输入引导，
// 不参与鉴权。因此这里的校验必须是**宽松的**：
//   - 只拦「明显不可能」的输入（空值、超长、非法字符）；
//   - 绝不能按角色限制字符集，否则会把合法账号挡在请求之前。
//
// 历史坑：旧实现要求「老师 = 纯数字工号或手机号」，而本系统真实的老师工号
// 形如 T1001 / T1002（后端 CreateUserRequest 的 account 正则是 ^[A-Za-z0-9_-]+$），
// 于是老师在登录页选「老师」身份后被前端校验直接拦死，根本发不出请求。

const ACCOUNT_RE = /^[A-Za-z0-9_-]{2,32}$/
const DIGITS_RE = /^\d+$/
const PHONE_RE = /^1[3-9]\d{9}$/

export function isStudentAccount(account) {
  return DIGITS_RE.test(account) && account.length >= 4
}

/** 工号（字母数字混合，如 T1001）或手机号 */
export function isTeacherAccount(account) {
  return ACCOUNT_RE.test(account) || PHONE_RE.test(account)
}

/**
 * 根据所选身份返回 el-form 校验器。
 * 只做「格式合法性」校验，不做「角色归属」校验 —— 后者是后端的职责，
 * 前端猜错了会误伤合法账号。
 */
export function accountValidatorByRole(role) {
  return (rule, value, callback) => {
    const v = (value || '').trim()
    if (!v) return callback(new Error('请输入登录账号'))
    if (v.length < 2 || v.length > 32) {
      return callback(new Error('账号长度为 2~32 位'))
    }
    if (!ACCOUNT_RE.test(v)) {
      return callback(new Error('账号只能包含字母、数字、下划线和中划线'))
    }
    // 注意：这里刻意**不**按 role 限制字符集。
    // 角色由后端查库识别，身份 Tab 只是输入引导；一旦前端按角色卡格式，
    // 合法账号（如工号 T1001）就会在发出请求前被拦死。
    callback()
  }
}
