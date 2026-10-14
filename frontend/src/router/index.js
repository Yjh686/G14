import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ROLE } from '../utils/role'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('../views/Profile.vue'),
        meta: { title: '个人中心' }
      },
      {
        path: 'change-password',
        name: 'ChangePassword',
        component: () => import('../views/ChangePassword.vue'),
        meta: { title: '修改密码' }
      },
      {
        // 首次登录强制改密页
        path: 'force-password',
        name: 'ForcePassword',
        component: () => import('../views/ForcePassword.vue'),
        meta: { title: '修改初始密码' }
      },
      {
        path: 'admin/users',
        name: 'AdminUsers',
        component: () => import('../views/admin/UserManage.vue'),
        meta: { title: '账号管理', roles: [ROLE.ADMIN] }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFound.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 全局前置守卫：登录校验 → 会话恢复 → 首次改密 → 角色守卫
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 公开页（登录页 / 404）：已登录访问登录页则跳首页
  if (to.meta.public) {
    if (to.name === 'Login' && auth.isLoggedIn) return { name: 'Dashboard' }
    return true
  }

  // 未登录 → 登录页，并记录来源以便登录后回跳
  if (!auth.isLoggedIn) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }

  // 有 token 但无用户信息（如刷新页面）：尝试恢复会话
  if (!auth.user && auth.token) {
    try {
      await auth.fetchUser()
    } catch (e) {
      auth.logout()
      return { name: 'Login' }
    }
  }

  // 首次登录强制修改初始密码
  if (auth.mustChangePassword && to.name !== 'ForcePassword') {
    return { name: 'ForcePassword' }
  }
  if (to.name === 'ForcePassword' && !auth.mustChangePassword) {
    return { name: 'Dashboard' }
  }

  // 角色守卫：路由 meta.roles 未包含当前角色则拒绝
  const roles = to.meta.roles
  if (roles && !roles.includes(auth.role)) {
    return { name: 'Dashboard' }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 班级问卷系统` : '班级问卷系统'
})

export default router
