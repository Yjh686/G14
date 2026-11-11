<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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

/**
 * 窄屏（手机）判定。
 *
 * 为什么需要：侧边栏是 210px 的固定宽度，手机视口只有 390px 左右，
 * 留在文档流里会把正文挤成 180px —— 中文在这个宽度下会退化成「一个字一行」，
 * 看起来像排版坏了。窄屏下必须把侧边栏改成浮层抽屉（fixed + transform），
 * 正文才能拿到整屏宽度。
 */
const isNarrow = ref(false)
const drawerOpen = ref(false)

/** 窄屏用抽屉，宽屏用折叠，两者语义不同不能共用一个开关 */
function toggleNav() {
  if (isNarrow.value) {
    drawerOpen.value = !drawerOpen.value
    return
  }
  collapsed.value = !collapsed.value
}

/** 抽屉里点任意菜单项就关掉，否则点完还挡着内容 */
watch(() => route.fullPath, () => {
  drawerOpen.value = false
})

let mq: MediaQueryList | null = null
const onMq = (e: MediaQueryListEvent | MediaQueryList) => {
  isNarrow.value = e.matches
  // 从窄变宽时抽屉状态要清掉，否则回到桌面会残留一个不可见的打开态
  if (!e.matches) drawerOpen.value = false
}

onMounted(() => {
  mq = window.matchMedia('(max-width: 768px)')
  onMq(mq)
  mq.addEventListener('change', onMq)
})

onBeforeUnmount(() => {
  mq?.removeEventListener('change', onMq)
})

const asideWidth = computed(() => (isNarrow.value ? '210px' : collapsed.value ? '64px' : '210px'))

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
    <!-- 抽屉打开时的遮罩：点空白处收起，符合手机端习惯 -->
    <div v-if="isNarrow && drawerOpen" class="nav-mask" @click="drawerOpen = false" />

    <el-aside
      :width="asideWidth"
      class="layout-aside"
      :class="{ 'is-drawer': isNarrow, 'is-open': drawerOpen }"
    >
      <div class="brand">
        <el-icon :size="22"><Document /></el-icon>
        <span v-show="!collapsed || isNarrow" class="brand-text">{{ config.siteName }}</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed && !isNarrow"
        router
        class="layout-menu"
      >
        <el-menu-item v-for="item in menus" :key="item.index" :index="item.index">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="18" @click="toggleNav">
            <component :is="isNarrow || collapsed ? 'Expand' : 'Fold'" />
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
  /* 100vh 在移动浏览器里会被地址栏算进去，导致底部被裁；
     有 dvh 就用 dvh，没有则回落到 vh。 */
  height: 100vh;
  height: 100dvh;
}

.layout-aside {
  background-color: #1f2d3d;
  transition: width 0.2s;
  overflow-x: hidden;
}

/* 窄屏：侧边栏脱离文档流变成左侧浮层抽屉，正文才能拿满整屏宽度 */
.layout-aside.is-drawer {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 2002;
  transform: translateX(-100%);
  transition: transform 0.22s ease;
  box-shadow: 0 0 16px rgb(0 0 0 / 30%);
}

.layout-aside.is-drawer.is-open {
  transform: translateX(0);
}

.nav-mask {
  position: fixed;
  inset: 0;
  z-index: 2001;
  background-color: rgb(0 0 0 / 45%);
}

/* 手机上表头空间紧张：用户名和角色标签先让位，头像和菜单按钮留着 */
@media (max-width: 768px) {
  .layout-header {
    padding: 0 10px;
  }

  .header-left {
    gap: 8px;
  }

  .user-name {
    display: none;
  }

  .user-info :deep(.el-tag) {
    display: none;
  }
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
