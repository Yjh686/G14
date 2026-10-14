<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { accountValidatorByRole } from '../utils/validate'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref()
const loading = ref(false)

const REMEMBER_KEY = 'remembered_account'

const roles = [
  { key: 'STUDENT', label: '学生' },
  { key: 'TEACHER', label: '老师' },
  { key: 'ADMIN', label: '管理员' }
]
const selectedRole = ref('STUDENT')

const form = reactive({ account: '', password: '' })
const remember = ref(false)

const accountPlaceholder = computed(() => {
  if (selectedRole.value === 'STUDENT') return '请输入学号'
  if (selectedRole.value === 'TEACHER') return '请输入工号或手机号'
  return '请输入管理员账号'
})

const rules = computed(() => ({
  account: [{ validator: accountValidatorByRole(selectedRole.value), trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}))

// 品牌面板特性
const features = [
  '学生 · 学号登录',
  '老师 · 工号 / 手机号登录',
  '管理员 · 全权限管理',
  '首次登录强制修改初始密码'
]

onMounted(() => {
  const saved = localStorage.getItem(REMEMBER_KEY)
  if (saved) {
    form.account = saved
    remember.value = true
  }
})

function switchRole(role) {
  selectedRole.value = role
  form.account = ''
}

async function handleSubmit() {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      if (remember.value) {
        localStorage.setItem(REMEMBER_KEY, form.account)
      } else {
        localStorage.removeItem(REMEMBER_KEY)
      }
      const user = await auth.login({ account: form.account, password: form.password })
      ElMessage.success('登录成功')
      if (user.mustChangePassword) {
        router.replace({ name: 'ForcePassword' })
      } else {
        router.replace(route.query.redirect || { name: 'Dashboard' })
      }
    } catch {
      // 错误提示已由 axios 拦截器统一处理
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="login-page">
    <div class="blob blob-a"></div>
    <div class="blob blob-b"></div>

    <el-card class="login-card" shadow="never">
      <!-- 品牌面板 -->
      <div class="brand-panel">
        <div class="brand-logo">📋</div>
        <h1 class="brand-title">班级问卷系统</h1>
        <p class="brand-desc">让问卷发布与填写更简单</p>
        <ul class="feature-list">
          <li v-for="f in features" :key="f">
            <span class="check">✓</span>{{ f }}
          </li>
        </ul>
        <div class="brand-foot">Class Survey · Auth &amp; Role</div>
      </div>

      <!-- 表单面板 -->
      <div class="form-panel">
        <div class="form-head">
          <h2>欢迎回来</h2>
          <p>请选择身份并登录，系统将自动识别</p>
        </div>

        <el-radio-group v-model="selectedRole" class="role-tabs" size="large" @change="switchRole">
          <el-radio-button v-for="r in roles" :key="r.key" :value="r.key">
            {{ r.label }}
          </el-radio-button>
        </el-radio-group>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @keyup.enter="handleSubmit"
        >
          <el-form-item label="账号" prop="account">
            <el-input v-model="form.account" :placeholder="accountPlaceholder" clearable>
              <template #prefix><span class="input-icon">👤</span></template>
            </el-input>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password>
              <template #prefix><span class="input-icon">🔒</span></template>
            </el-input>
          </el-form-item>

          <div class="row-between">
            <el-checkbox v-model="remember">记住账号</el-checkbox>
          </div>

          <el-button type="primary" class="submit" size="large" :loading="loading" @click="handleSubmit">
            登 录
          </el-button>
        </el-form>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(150deg, #eef2fb 0%, #e7ecf8 100%);
  overflow: hidden;
}

/* 装饰光斑 */
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.55;
}
.blob-a {
  width: 360px;
  height: 360px;
  background: #a7c0ff;
  top: -80px;
  left: -60px;
}
.blob-b {
  width: 400px;
  height: 400px;
  background: #c9b8ff;
  bottom: -120px;
  right: -80px;
}

.login-card {
  position: relative;
  z-index: 1;
  width: 880px;
  max-width: 100%;
  border-radius: 22px;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(31, 42, 80, 0.16);
  border: none;
}
/* 关键：el-card 内容被包在 .el-card__body 里，需让 body 成为左右 flex 容器 */
.login-card :deep(.el-card__body) {
  display: flex;
  padding: 0;
  min-height: 520px;
}

/* 品牌面板 */
.brand-panel {
  width: 40%;
  padding: 44px 36px;
  background: var(--brand-grad);
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.brand-logo {
  width: 52px;
  height: 52px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  margin-bottom: 20px;
}
.brand-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.3;
}
.brand-desc {
  margin: 8px 0 26px;
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
}
.feature-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.feature-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.92);
}
.check {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}
.brand-foot {
  margin-top: 34px;
  font-size: 11px;
  letter-spacing: 0.6px;
  color: rgba(255, 255, 255, 0.5);
}

/* 表单面板 */
.form-panel {
  flex: 1;
  padding: 44px 44px 36px;
  background: #fff;
}
.form-head h2 {
  margin: 0 0 4px;
  font-size: 22px;
  color: var(--text-1);
}
.form-head p {
  margin: 0 0 22px;
  color: var(--text-3);
  font-size: 13px;
}

.role-tabs {
  display: flex;
  margin-bottom: 22px;
}
.role-tabs :deep(.el-radio-button) {
  flex: 1;
}
.role-tabs :deep(.el-radio-button__inner) {
  width: 100%;
  font-weight: 500;
}

.input-icon {
  font-size: 15px;
}
.row-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.submit {
  width: 100%;
  margin-top: 8px;
  font-weight: 600;
  letter-spacing: 4px;
  border-radius: 10px;
  height: 46px;
  background: var(--brand-grad);
  border: none;
}
.submit:hover {
  opacity: 0.92;
}

@media (max-width: 720px) {
  .brand-panel {
    display: none;
  }
  .login-card {
    width: 420px;
  }
  .form-panel {
    padding: 32px 26px;
  }
}
</style>
