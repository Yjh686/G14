<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { ApiError } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
/** 登录失败提示（含锁定剩余次数等，直接用后端 msg） */
const errorText = ref('')

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入学号/工号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 演示账号，仅用于课程演示，点击即填充 */
const demoAccounts = [
  { label: '管理员', username: 'admin', password: 'Admin@123' },
  { label: '教师', username: 'teacher', password: 'Teacher@123' },
  { label: '学生', username: 'student', password: 'Student@123' },
]

function fillDemo(item: { username: string; password: string }) {
  form.username = item.username
  form.password = item.password
  errorText.value = ''
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  errorText.value = ''
  try {
    await auth.login({ username: form.username.trim(), password: form.password })

    // 强制改密优先于 redirect，否则会先跳到没权限的页面
    if (auth.mustChangePassword) {
      ElMessage.warning('首次登录或密码已被重置，请先修改密码')
      await router.replace({ name: 'change-password' })
      return
    }

    ElMessage.success(`欢迎回来，${auth.displayName}`)
    const redirect = route.query.redirect as string | undefined
    await router.replace(redirect || '/')
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-wrapper">
    <el-card class="auth-card" shadow="always">
      <div class="auth-brand">{{ config.siteName }}</div>
      <p class="auth-subtitle">校园问卷信息调查管理系统</p>

      <!-- 公告由管理员在系统配置里维护，为空时不显示整块 -->
      <el-alert
        v-if="config.announcement"
        :title="config.announcement"
        type="info"
        :closable="false"
        show-icon
        class="login-notice"
      />

      <el-alert
        v-if="errorText"
        :title="errorText"
        type="error"
        :closable="false"
        show-icon
        class="login-error"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="学号 / 工号" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入学号或工号"
            prefix-icon="User"
            clearable
            autocomplete="username"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="w-full"
            size="large"
            :loading="submitting"
            @click="handleSubmit"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        还没有账号？
        <el-link type="primary" :underline="false" @click="router.push({ name: 'register' })">
          注册账号
        </el-link>
      </div>

      <el-divider content-position="center">
        <span class="demo-title">演示账号</span>
      </el-divider>
      <div class="demo-list">
        <el-button
          v-for="item in demoAccounts"
          :key="item.username"
          size="small"
          plain
          @click="fillDemo(item)"
        >
          {{ item.label }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-notice {
  margin-bottom: 18px;
}

.login-error {
  margin-bottom: 16px;
}

.demo-title {
  font-size: 12px;
  color: #909399;
}

.demo-list {
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>
