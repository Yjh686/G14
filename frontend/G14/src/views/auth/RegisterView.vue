<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { authApi } from '@/api/auth'
import { ApiError } from '@/api/http'
import { REGISTER_ROLES, validatePhone, validatePassword } from '@/utils/dict'
import { useConfigStore } from '@/stores/config'

const router = useRouter()
const config = useConfigStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const success = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  realName: '',
  role: 'STUDENT' as 'STUDENT' | 'TEACHER',
  department: '',
  phone: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入学号/工号', trigger: 'blur' },
    { min: 4, max: 64, message: '账号长度需为 4~64 位', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]+$/,
      message: '账号只能包含字母、数字、下划线、点或短横线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const err = validatePassword(value, config.passwordRules)
        callback(err ? new Error(err) : undefined)
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        callback(value === form.password ? undefined : new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择身份', trigger: 'change' }],
  phone: [
    {
      validator: (_rule, value: string, callback) => {
        const err = validatePhone(value)
        callback(err ? new Error(err) : undefined)
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
    await authApi.register({
      username: form.username.trim(),
      password: form.password,
      realName: form.realName.trim(),
      role: form.role,
      department: form.department.trim() || undefined,
      phone: form.phone.trim() || undefined,
    })
    success.value = true
    ElMessage.success('注册申请已提交')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '注册失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-wrapper">
    <el-card class="auth-card auth-card-wide" shadow="always">
      <div class="auth-brand">注册账号</div>
      <p class="auth-subtitle">提交后需等待管理员审核通过才能登录</p>

      <el-result
        v-if="success"
        icon="success"
        title="注册申请已提交"
        sub-title="管理员审核通过后即可使用该账号登录，请耐心等候"
      >
        <template #extra>
          <el-button type="primary" @click="router.replace({ name: 'login' })">返回登录</el-button>
        </template>
      </el-result>

      <template v-else>
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-width="88px"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="学号/工号" prop="username">
            <el-input
              v-model="form.username"
              placeholder="4~64 位字母、数字"
              clearable
              autocomplete="username"
            />
          </el-form-item>

          <el-form-item label="真实姓名" prop="realName">
            <el-input v-model="form.realName" placeholder="用于审核与教师查看" clearable />
          </el-form-item>

          <el-form-item label="身份" prop="role">
            <el-radio-group v-model="form.role">
              <el-radio v-for="r in REGISTER_ROLES" :key="r.value" :value="r.value">
                {{ r.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="院系/班级" prop="department">
            <el-input v-model="form.department" placeholder="选填，如 计算机学院2023级1班" clearable />
          </el-form-item>

          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="选填，用于找回账号" clearable />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入密码"
              autocomplete="new-password"
            />
            <div class="field-tip">{{ config.passwordRuleText }}</div>
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              show-password
              placeholder="请再次输入密码"
              autocomplete="new-password"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="handleSubmit">
              提交注册申请
            </el-button>
            <el-button @click="router.push({ name: 'login' })">返回登录</el-button>
          </el-form-item>
        </el-form>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.field-tip {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
