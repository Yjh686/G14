<script setup>
import { computed, onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { ROLE_LABEL, ROLE_TAG } from '../utils/role'

const auth = useAuthStore()

const roleLabel = computed(() => ROLE_LABEL[auth.role] || '未知')
const roleTag = computed(() => ROLE_TAG[auth.role] || 'info')

onMounted(async () => {
  try {
    await auth.fetchUser()
  } catch {
    // 会话失效时拦截器会统一跳转登录页
  }
})
</script>

<template>
  <el-card class="profile-card" shadow="never">
    <!-- 顶部渐变身份区 -->
    <div class="hero">
      <el-avatar :size="84" class="avatar">{{ auth.user?.name?.slice(0, 1) }}</el-avatar>
      <div class="hero-name">{{ auth.user?.name }}</div>
      <div class="hero-meta">
        <el-tag :type="roleTag" effect="dark" round>{{ roleLabel }}</el-tag>
        <span class="account">{{ auth.user?.account }}</span>
      </div>
    </div>

    <!-- 详细信息 -->
    <el-descriptions :column="1" border class="desc">
      <el-descriptions-item label="账号">
        {{ auth.user?.account }}
      </el-descriptions-item>
      <el-descriptions-item label="姓名">
        {{ auth.user?.name }}
      </el-descriptions-item>
      <el-descriptions-item label="角色">
        <el-tag :type="roleTag" effect="light" round>{{ roleLabel }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="手机号">
        {{ auth.user?.phone || '未填写' }}
      </el-descriptions-item>
      <el-descriptions-item label="账号状态">
        <el-tag :type="auth.user?.status === 1 ? 'success' : 'danger'" effect="light" round>
          {{ auth.user?.status === 1 ? '正常' : '已禁用' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="创建时间">
        {{ auth.user?.createdAt || '—' }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.profile-card {
  max-width: 620px;
  margin: 0 auto;
  border: none;
  border-radius: var(--radius-lg);
  box-shadow: var(--card-shadow);
  overflow: hidden;
}
.profile-card :deep(.el-card__body) {
  padding: 0;
}

.hero {
  padding: 36px 24px 28px;
  text-align: center;
  background: var(--brand-grad);
  color: #fff;
}
.avatar {
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  font-size: 32px;
  font-weight: 700;
  border: 3px solid rgba(255, 255, 255, 0.35);
}
.hero-name {
  margin-top: 14px;
  font-size: 20px;
  font-weight: 700;
}
.hero-meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.hero-meta .account {
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
}

.desc {
  padding: 22px 24px 26px;
  border: none;
}
.desc :deep(.el-descriptions__label) {
  font-weight: 600;
  color: var(--text-2);
}
.desc :deep(.el-descriptions__cell) {
  background: #fafbfd;
}
</style>
