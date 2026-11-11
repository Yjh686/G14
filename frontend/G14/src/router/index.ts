import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'

/** 路由元信息约定 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 免登录可访问 */
    public?: boolean
    /** 需要的权限码，数组表示任一满足；用于菜单与守卫 */
    perm?: string | string[]
    /** 页面标题 */
    title?: string
    /** 侧边栏与顶部面包屑是否显示该菜单 */
    hidden?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { public: true, title: '注册账号' },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/auth/ChangePasswordView.vue'),
    meta: { title: '修改密码' },
  },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/user/ProfileView.vue'),
        meta: { title: '个人资料' },
      },

      /* -------------------- 问卷管理（发布者视角） -------------------- */
      {
        path: 'surveys',
        name: 'surveys',
        component: () => import('@/views/survey/SurveyListView.vue'),
        meta: { title: '我的问卷', perm: ['survey:list:self', 'survey:list:all'] },
      },
      {
        path: 'surveys/new',
        name: 'survey-create',
        component: () => import('@/views/survey/SurveyEditView.vue'),
        meta: { title: '新建问卷', perm: 'survey:create' },
      },
      {
        path: 'surveys/:id/edit',
        name: 'survey-edit',
        // 同一组件复用于新建与编辑，靠 route.params.id 是否存在区分
        component: () => import('@/views/survey/SurveyEditView.vue'),
        meta: { title: '编辑问卷', perm: ['survey:update:self', 'survey:update:all'] },
      },
      {
        path: 'surveys/:id/preview',
        name: 'survey-preview',
        component: () => import('@/views/survey/SurveyPreviewView.vue'),
        meta: { title: '问卷预览', perm: 'survey:preview' },
      },
      {
        path: 'surveys/:id/answers',
        name: 'survey-answers',
        component: () => import('@/views/survey/SurveyAnswersView.vue'),
        meta: {
          title: '答卷管理',
          perm: ['answer:read:survey:self', 'answer:read:survey:all'],
        },
      },
      {
        path: 'surveys/:id/stats',
        name: 'survey-stats',
        component: () => import('@/views/survey/SurveyStatsView.vue'),
        meta: {
          title: '统计分析',
          perm: ['stat:read:self', 'stat:read:all'],
        },
      },
      {
        path: 'surveys/answers/:sheetId',
        name: 'answer-detail',
        component: () => import('@/views/survey/AnswerDetailView.vue'),
        meta: {
          title: '答卷详情',
          perm: ['answer:read:survey:self', 'answer:read:survey:all'],
        },
      },

      /* -------------------- 填写（学生视角） -------------------- */
      {
        path: 'fill',
        name: 'survey-hall',
        component: () => import('@/views/survey/SurveyHallView.vue'),
        meta: { title: '填写问卷', perm: 'survey:fill' },
      },
      {
        // :key 既可以是问卷 id，也可以是 8 位分享码，
        // 分享链接 /fill/Q3PTC5RC 与大厅跳转 /fill/1 共用一个页面
        path: 'fill/:key',
        name: 'survey-fill',
        component: () => import('@/views/survey/SurveyFillView.vue'),
        meta: { title: '填写问卷', perm: 'survey:fill', hidden: true },
      },
      {
        path: 'my-answers',
        name: 'my-answers',
        component: () => import('@/views/survey/MyAnswersView.vue'),
        meta: { title: '我的填写记录', perm: 'answer:read:self' },
      },
      {
        path: 'my-answers/:sheetId',
        name: 'my-answer-detail',
        component: () => import('@/views/survey/MyAnswerDetailView.vue'),
        meta: { title: '答卷详情', perm: 'answer:read:self', hidden: true },
      },

      /* -------------------- 模板中心 -------------------- */
      {
        path: 'templates',
        name: 'templates',
        component: () => import('@/views/template/TemplateCenterView.vue'),
        // 三个视角都要能进来：浏览 / 我共享的 / 审核
        meta: { title: '模板中心', perm: ['template:browse', 'template:share', 'template:audit'] },
      },
      {
        path: 'templates/:id',
        name: 'template-detail',
        component: () => import('@/views/template/TemplateDetailView.vue'),
        meta: {
          title: '模板详情',
          perm: ['template:browse', 'template:share', 'template:audit'],
          hidden: true,
        },
      },
      {
        path: 'my-templates',
        name: 'my-templates',
        component: () => import('@/views/template/MyTemplatesView.vue'),
        meta: { title: '我共享的模板', perm: 'template:share' },
      },
      {
        path: 'admin/template-audit',
        name: 'admin-template-audit',
        component: () => import('@/views/template/TemplateAuditView.vue'),
        meta: { title: '模板审核', perm: 'template:audit' },
      },

      /* -------------------- 系统管理 -------------------- */
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserListView.vue'),
        meta: { title: '用户管理', perm: 'user:list' },
      },
      {
        path: 'admin/audit',
        name: 'admin-audit',
        component: () => import('@/views/admin/AuditListView.vue'),
        meta: { title: '注册审核', perm: 'user:audit' },
      },
      {
        path: 'admin/permissions',
        name: 'admin-permissions',
        component: () => import('@/views/admin/PermissionView.vue'),
        meta: { title: '权限配置', perm: 'user:grant' },
      },
      {
        path: 'admin/config',
        name: 'admin-config',
        component: () => import('@/views/admin/ConfigView.vue'),
        meta: { title: '系统配置', perm: 'config:manage' },
      },
      {
        // 登录日志与操作日志在同一页面用 Tab 切换，权限是「任一即可」
        path: 'admin/logs',
        name: 'admin-logs',
        component: () => import('@/views/admin/LogView.vue'),
        meta: { title: '日志查询', perm: ['log:login:read', 'log:operation:read'] },
      },

      {
        path: 'forbidden',
        name: 'forbidden',
        component: () => import('@/views/error/ForbiddenView.vue'),
        meta: { title: '无权访问', hidden: true },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在', hidden: true },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 0. 公开配置：站点名要出现在浏览器标题与登录页上，免登录也要能读。
  //    load() 内部做了缓存与并发合并，除首次外都是空转；失败不阻塞导航。
  await useConfigStore().load()

  // 1. 免登录页面直接放行（已登录访问登录页则回首页）
  if (to.meta.public) {
    if (to.name === 'login' && auth.isLoggedIn) {
      return { name: 'home' }
    }
    return true
  }

  // 2. 未登录 → 登录页，并记住来路
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 3. 刷新页面后 token 还在但内存里没用户信息，先确认一次
  if (!auth.loaded) {
    try {
      await auth.fetchMe()
    } catch {
      auth.clear()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  // 4. 必须改密的账号只能访问改密页（后端同样会拦截，这里提前拦是为了不给用户看报错）
  if (auth.mustChangePassword && to.name !== 'change-password') {
    return { name: 'change-password' }
  }

  // 5. 权限校验：只有前端做了隐显，真正的判定仍在后端
  const perm = to.meta.perm
  if (perm) {
    const need = Array.isArray(perm) ? perm : [perm]
    if (!auth.hasAnyPerm(need)) {
      ElMessage.error('您没有访问该页面的权限')
      return { name: 'forbidden' }
    }
  }

  return true
})

router.afterEach((to) => {
  // 站点名来自系统配置（FR-CFG-01），管理员改完标题立即跟着变；
  // 配置还没加载时用兜底值，所以这里不 await
  const siteName = useConfigStore().siteName
  document.title = to.meta.title ? `${to.meta.title} - ${siteName}` : siteName
})

export default router
