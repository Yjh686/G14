<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
// Element Plus 的图标是独立包，不会随 ElementPlus 插件全局注册，
// 模板里直接写 <ArrowDown /> 只会渲染成一个未知元素（控制台警告 + 图标缺失）。
import { ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { ROLE_LABEL, ROLE_TAG } from '../utils/role'
import { logout } from '../api/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const roleLabel = computed(() => ROLE_LABEL[auth.role] || '用户')
const roleTag = computed(() => ROLE_TAG[auth.role] || 'info')

// 按角色渲染侧边导航
const menus = computed(() => {
  const base = [
    { index: '/dashboard', icon: '🏠', title: '首页' },
    { index: '/profile', icon: '👤', title: '个人中心' },
    { index: '/change-password', icon: '🔒', title: '修改密码' }
  ]
  if (auth.isAdmin) {
    base.push({ index: '/admin/users', icon: '👥', title: '账号管理' })
  }
  return base
})

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await logout()
  } catch {
    // 忽略登出接口失败，仍清理本地状态
  }
  auth.logout()
  ElMessage.success('已退出登录')
  router.replace({ name: 'Login' })
}
</script>

<template>
  <el-container class="layout">
    <!-- 侧边导航 -->
    <el-aside width="232px" class="aside">
      <div class="side-brand" @click="router.push('/dashboard')">
        <div class="brand-logo">📋</div>
        <div class="brand-text">
          <span class="brand-title">班级问卷</span>
          <span class="brand-sub">Class Survey</span>
        </div>
      </div>

      <el-menu :default-active="route.path" router class="side-menu">
        <el-menu-item v-for="m in menus" :key="m.index" :index="m.index">
          <span class="menu-icon">{{ m.icon }}</span>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>

      <div class="side-foot">
        <div class="foot-avatar">{{ auth.user?.name?.slice(0, 1) }}</div>
        <div class="foot-info">
          <span class="foot-name">{{ auth.user?.name }}</span>
          <span class="foot-role">{{ roleLabel }}</span>
        </div>
      </div>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="page-title">{{ route.meta.title || '' }}</div>
        <div class="right">
          <el-tag :type="roleTag" size="small" effect="dark" round>{{ roleLabel }}</el-tag>
          <el-dropdown trigger="click">
            <span class="user-trigger">
              <el-avatar :size="32" class="avatar">{{ auth.user?.name?.slice(0, 1) }}</el-avatar>
              <span class="name">{{ auth.user?.name }}</span>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/profile')">个人中心</el-dropdown-item>
                <el-dropdown-item @click="router.push('/change-password')">修改密码</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <div class="page-enter">
          <router-view />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

/* ---------- 侧边栏 ---------- */
.aside {
  background: var(--sidebar-grad);
  display: flex;
  flex-direction: column;
  color: #fff;
  position: relative;
}

.side-brand {
  height: 68px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  cursor: pointer;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.brand-logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  box-shadow: 0 4px 12px rgba(76, 111, 255, 0.4);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.brand-title {
  font-size: 16px;
  font-weight: 700;
}
.brand-sub {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
  letter-spacing: 0.5px;
}

.side-menu {
  flex: 1;
  border-right: none;
  background: transparent;
  padding: 14px 12px;
}
.side-menu :deep(.el-menu-item) {
  height: 46px;
  line-height: 46px;
  border-radius: 10px;
  margin-bottom: 6px;
  color: rgba(255, 255, 255, 0.72);
  transition: all 0.2s;
}
.side-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.side-menu :deep(.el-menu-item.is-active) {
  background: var(--brand-grad);
  color: #fff;
  box-shadow: 0 6px 16px rgba(76, 111, 255, 0.4);
}
.menu-icon {
  margin-right: 10px;
  font-size: 16px;
}

.side-foot {
  margin: 0 12px 16px;
  padding: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  gap: 10px;
}
.foot-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
}
.foot-info {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.foot-name {
  font-size: 13px;
  font-weight: 600;
}
.foot-role {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.55);
}

/* ---------- 顶栏 ---------- */
.header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid #e7ebf3;
}
.page-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-1);
}
.right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
  padding: 4px 10px;
  border-radius: 20px;
  transition: background 0.2s;
}
.user-trigger:hover {
  background: #f2f4fa;
}
.avatar {
  background: var(--brand-grad);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}
.name {
  font-size: 14px;
  color: var(--text-1);
  font-weight: 600;
}
.caret {
  color: var(--text-3);
}

/* ---------- 内容区 ---------- */
.main {
  background: var(--app-bg);
  padding: 22px 24px;
  overflow: auto;
}
</style>
