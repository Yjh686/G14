<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

function goHome() {
  router.replace({ name: 'Dashboard' })
}
function goLogin() {
  auth.logout()
  router.replace({ name: 'Login' })
}
</script>

<template>
  <div class="nf">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <div class="nf-title">页面不存在</div>
      <div class="nf-sub">抱歉，您访问的页面不存在或已被移除</div>
      <div class="nf-actions">
        <el-button v-if="auth.isLoggedIn" type="primary" round @click="goHome">返回首页</el-button>
        <el-button v-else type="primary" round @click="goLogin">去登录</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.nf {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(150deg, #eef2fb 0%, #e7ecf8 100%);
}
.nf-card {
  text-align: center;
  padding: 24px;
}
.nf-code {
  font-size: 96px;
  font-weight: 800;
  line-height: 1;
  background: var(--brand-grad);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  margin-bottom: 16px;
}
.nf-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
}
.nf-sub {
  margin-top: 8px;
  color: var(--text-3);
  font-size: 14px;
}
.nf-actions {
  margin-top: 24px;
}
</style>
