<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ROLE_LABEL } from '../utils/role'

const auth = useAuthStore()
const router = useRouter()

const roleLabel = computed(() => ROLE_LABEL[auth.role] || '用户')

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

// 快捷入口（真实路由）
const quickActions = computed(() => {
  const list = [
    { name: 'Profile', icon: '👤', title: '个人中心', desc: '查看账号信息', color: '#4c6fff', bg: '#eaf0ff' },
    { name: 'ChangePassword', icon: '🔒', title: '修改密码', desc: '定期更换密码更安全', color: '#f59e0b', bg: '#fef3e2' }
  ]
  if (auth.isAdmin) {
    list.push({ name: 'AdminUsers', icon: '👥', title: '账号管理', desc: '开通账号、重置密码', color: '#10b981', bg: '#e2f7ef' })
  }
  return list
})

// 待接入模块
const plannedModules = computed(() => {
  if (auth.isStudent) {
    return [
      { icon: '📚', title: '我的班级', desc: '通过邀请码加入班级' },
      { icon: '📝', title: '待填问卷', desc: '填写所在班级发布的问卷' }
    ]
  }
  if (auth.isTeacher) {
    return [
      { icon: '🏫', title: '我的班级', desc: '创建并管理自己负责的班级' },
      { icon: '📋', title: '问卷管理', desc: '发布并查看自己班级的问卷' }
    ]
  }
  return [
    { icon: '🏫', title: '全部班级', desc: '查看系统内所有班级' },
    { icon: '📋', title: '全部问卷', desc: '查看系统内所有问卷' }
  ]
})
</script>

<template>
  <div>
    <!-- 欢迎横幅 -->
    <el-card class="banner" shadow="never">
      <div class="banner-inner">
        <div class="banner-text">
          <div class="hello">{{ greeting }}，{{ auth.user?.name }}</div>
          <div class="role-line">
            <el-tag effect="dark" round class="role-tag">{{ roleLabel }}</el-tag>
            <span v-if="auth.user?.account" class="account">账号：{{ auth.user.account }}</span>
          </div>
          <p class="sub">欢迎使用班级问卷系统，请从下方入口开始今天的工作。</p>
        </div>
        <div class="banner-icon">📋</div>
      </div>
    </el-card>

    <!-- 快捷入口 -->
    <div class="section-title">快捷入口</div>
    <el-row :gutter="16" class="entries">
      <el-col v-for="a in quickActions" :key="a.name" :xs="24" :sm="12" :md="8">
        <el-card class="entry" shadow="hover" @click="router.push({ name: a.name })">
          <div class="entry-icon" :style="{ background: a.bg, color: a.color }">{{ a.icon }}</div>
          <div class="entry-body">
            <div class="entry-title">{{ a.title }}</div>
            <div class="entry-desc">{{ a.desc }}</div>
          </div>
          <el-icon class="arrow"><ArrowRight /></el-icon>
        </el-card>
      </el-col>
    </el-row>

    <!-- 待接入模块 -->
    <div class="section-title">班级 / 问卷模块</div>
    <el-row :gutter="16" class="entries">
      <el-col v-for="m in plannedModules" :key="m.title" :xs="24" :sm="12" :md="8">
        <el-card class="entry planned" shadow="hover">
          <div class="entry-icon dim">{{ m.icon }}</div>
          <div class="entry-body">
            <div class="entry-title">{{ m.title }}</div>
            <div class="entry-desc">{{ m.desc }}</div>
          </div>
          <el-tag size="small" type="info" effect="plain" class="tag">待接入</el-tag>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
/* 欢迎横幅 */
.banner {
  border: none;
  border-radius: var(--radius-lg);
  background: var(--brand-grad);
  margin-bottom: 22px;
  overflow: hidden;
}
.banner-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
}
.banner-text .hello {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
}
.role-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.role-tag {
  border: none;
}
.account {
  color: rgba(255, 255, 255, 0.82);
  font-size: 13px;
}
.banner-text .sub {
  margin: 10px 0 0;
  color: rgba(255, 255, 255, 0.78);
  font-size: 13px;
}
.banner-icon {
  font-size: 72px;
  opacity: 0.28;
  margin-right: 12px;
  user-select: none;
}

.section-title {
  margin: 22px 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
}

/* 入口卡片 */
.entries .entry {
  border: none;
  border-radius: var(--radius-md);
  box-shadow: var(--card-shadow);
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px;
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
}
.entries .entry:hover {
  transform: translateY(-3px);
  box-shadow: var(--card-shadow-hover);
}
.entry.planned {
  cursor: default;
}
.entry.planned:hover {
  transform: none;
  box-shadow: var(--card-shadow);
}
.entry-icon {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  flex-shrink: 0;
}
.entry-icon.dim {
  background: #f0f2f7;
  color: #98a1b3;
}
.entry-body {
  flex: 1;
  min-width: 0;
}
.entry-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
}
.entry-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 2px;
}
.arrow {
  color: var(--text-3);
}
.entry .tag {
  flex-shrink: 0;
}
</style>
