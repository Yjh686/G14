// 角色常量与文案，与后端 TINYINT(1/2/3) 对应的可读字符串一一映射
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
