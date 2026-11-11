<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { validatePassword } from '@/utils/dict'
import { useConfigStore } from '@/stores/config'

const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 被强制跳转过来的（首次登录/被重置密码）不允许直接离开 */
const forced = computed(() => auth.mustChangePassword)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const err = validatePassword(value, config.passwordRules)
        if (err) return callback(new Error(err))
        if (value === form.oldPassword) return callback(new Error('新密码不能与原密码相同'))
        callback()
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        callback(value === form.newPassword ? undefined : new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    ElMessage.success('密码修改成功')
    await router.replace('/')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '修改失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

async function handleLogout() {
  await auth.logout()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="auth-wrapper">
    <el-card class="auth-card" shadow="always">
      <div class="auth-brand">修改密码</div>
      <p class="auth-subtitle">
        {{ forced ? '首次登录或密码已被重置，请修改密码后继续使用' : '定期更换密码可以提升账号安全' }}
      </p>

      <el-alert
        v-if="forced"
        title="在此完成改密前，其它功能暂不可用"
        type="warning"
        :closable="false"
        show-icon
        class="force-tip"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            placeholder="请输入当前密码"
            autocomplete="current-password"
          />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            placeholder="请输入新密码"
            autocomplete="new-password"
          />
          <div class="field-tip">{{ config.passwordRuleText }}</div>
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            placeholder="请再次输入新密码"
            autocomplete="new-password"
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
            确认修改
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-footer">
        <el-button v-if="!forced" link type="primary" @click="router.back()">返回</el-button>
        <el-button link type="info" @click="handleLogout">退出登录</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.force-tip {
  margin-bottom: 16px;
}

.field-tip {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
