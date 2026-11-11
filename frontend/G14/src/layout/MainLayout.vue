<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { ROLE_LABEL } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()

const collapsed = ref(false)

const menus = computed(() => {
  const items: { index: string; title: string; icon: string }[] = [
    { index: '/', title: '工作台', icon: 'HomeFilled' },
  ]
  if (auth.hasAnyPerm(['survey:list:self', 'survey:list:all'])) {
    items.push({ index: '/surveys', title: '我的问卷', icon: 'Tickets' })
  }
  if (auth.hasPerm('survey:fill')) {
    items.push({ index: '/fill', title: '填写问卷', icon: 'EditPen' })
  }
  if (auth.hasPerm('answer:read:self')) {
    items.push({ index: '/my-answers', title: '我的填写记录', icon: 'Document' })
  }
  if (auth.hasAnyPerm(['template:browse', 'template:share', 'template:audit'])) {
    items.push({ index: '/templates', title: '模板中心', icon: 'Files' })
  }
  if (auth.hasPerm('template:share')) {
    items.push({ index: '/my-templates', title: '我共享的模板', icon: 'Share' })
  }
  if (auth.hasPerm('user:list')) {
    items.push({ index: '/admin/users', title: '用户管理', icon: 'User' })
  }
  if (auth.hasPerm('user:audit')) {
    items.push({ index: '/admin/audit', title: '注册审核', icon: 'CircleCheck' })
  }
  if (auth.hasPerm('user:grant')) {
    items.push({ index: '/admin/permissions', title: '权限配置', icon: 'Key' })
  }
  if (auth.hasPerm('template:audit')) {
    items.push({ index: '/admin/template-audit', title: '模板审核', icon: 'Stamp' })
  }
  if (auth.hasPerm('config:manage')) {
    items.push({ index: '/admin/config', title: '系统配置', icon: 'Tools' })
  }
  if (auth.hasAnyPerm(['log:login:read', 'log:operation:read'])) {
    items.push({ index: '/admin/logs', title: '日志查询', icon: 'Memo' })
  }
  items.push({ index: '/profile', title: '个人资料', icon: 'Setting' })
  return items
})

const activeMenu = computed(() => (route.path === '/' ? '/' : route.path))
const currentTitle = computed(() => (route.meta.title as string) || '')

const roleLabel = computed(() => ROLE_LABEL[auth.user?.roleCode ?? ''] ?? '')

async function handleCommand(command: string) {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
    await auth.logout()
    ElMessage.success('已退出登录')
    router.replace({ name: 'login' })
    return
  }
  if (command === 'profile') router.push({ name: 'profile' })
  if (command === 'password') router.push({ name: 'change-password' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="layout-aside">
      <div class="brand">
        <el-icon :size="22"><Document /></el-icon>
        <span v-show="!collapsed" class="brand-text">{{ config.siteName }}</span>
      </div>
      <el-menu :default-active="activeMenu" :collapse="collapsed" router class="layout-menu">
        <el-menu-item v-for="item in menus" :key="item.index" :index="item.index">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="18" @click="collapsed = !collapsed">
            <component :is="collapsed ? 'Expand' : 'Fold'" />
          </el-icon>
          <span class="header-title">{{ currentTitle }}</span>
        </div>

        <el-dropdown trigger="click" @command="handleCommand">
          <span class="user-info">
            <el-avatar :size="30">{{ auth.displayName.charAt(0) || 'U' }}</el-avatar>
            <span class="user-name">{{ auth.displayName }}</span>
            <el-tag v-if="roleLabel" size="small" type="info">{{ roleLabel }}</el-tag>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人资料</el-dropdown-item>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.layout-aside {
  background-color: #1f2d3d;
  transition: width 0.2s;
  overflow-x: hidden;
}

.brand {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  height: 56px;
  color: #fff;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
}

.brand-text {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.layout-menu {
  border-right: none;
  background-color: #1f2d3d;
}

.layout-menu :deep(.el-menu-item) {
  color: #bfcbd9;
}

.layout-menu :deep(.el-menu-item:hover) {
  background-color: #2b3a4d;
}

.layout-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background-color: #2f7fd1;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.header-left {
  display: flex;
  gap: 12px;
  align-items: center;
}

.collapse-btn {
  cursor: pointer;
  color: #606266;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
}

.user-info {
  display: flex;
  gap: 8px;
  align-items: center;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 14px;
  color: #303133;
}

.layout-main {
  padding: 0;
  overflow-y: auto;
  background-color: #f5f7fa;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
