// 角色常量与文案。
// 后端 Role 枚举以**字符串**存储与返回（STUDENT / TEACHER / ADMIN），
// 不是 TINYINT —— 见 backend security/Role.java 与 sys_user.role VARCHAR(16)。
// 因此这里的值必须与后端枚举名逐字一致，否则角色判断与路由守卫会失效。
export const ROLE = {
  STUDENT: 'STUDENT',
  TEACHER: 'TEACHER',
  ADMIN: 'ADMIN'
}

export const ROLE_LABEL = {
  [ROLE.STUDENT]: '学生',
  [ROLE.TEACHER]: '老师',
  [ROLE.ADMIN]: '管理员'
}

// Element Plus tag 类型，用于页面展示角色徽标
export const ROLE_TAG = {
  [ROLE.STUDENT]: 'primary',
  [ROLE.TEACHER]: 'warning',
  [ROLE.ADMIN]: 'danger'
}

export function roleLabel(role) {
  return ROLE_LABEL[role] || '未知'
}
