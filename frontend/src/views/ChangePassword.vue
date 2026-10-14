<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validatePassword = (rule, value, callback) => {
  if (!value) return callback(new Error('请输入新密码'))
  if (value.length < 8) return callback(new Error('密码长度至少 8 位'))
  if (!/[a-zA-Z]/.test(value) || !/\d/.test(value)) {
    return callback(new Error('密码需同时包含字母和数字'))
  }
  if (value === form.oldPassword) return callback(new Error('新密码不能与原密码相同'))
  callback()
}

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ validator: validatePassword, trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.newPassword) return callback(new Error('两次输入的密码不一致'))
        callback()
      },
      trigger: 'blur'
    }
  ]
}

async function handleSubmit() {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await auth.changePassword({
        oldPassword: form.oldPassword,
        newPassword: form.newPassword,
        confirmPassword: form.confirmPassword
      })
      ElMessage.success('密码修改成功')
      formRef.value.resetFields()
    } catch {
      // 错误由拦截器统一提示
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="page">
    <el-card class="card" shadow="never">
      <div class="head">
        <div class="head-icon">🔒</div>
        <h3>修改密码</h3>
        <p>定期更换密码，保护账号安全</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleSubmit"
      >
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入原密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 8 位，含字母和数字" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>

        <el-button type="primary" size="large" class="submit" :loading="loading" @click="handleSubmit">
          保存新密码
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  justify-content: center;
  padding-top: 28px;
}
.card {
  width: 460px;
  max-width: 100%;
  border: none;
  border-radius: var(--radius-lg);
  box-shadow: var(--card-shadow);
}
.head {
  text-align: center;
  padding: 8px 0 18px;
}
.head-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 16px;
  background: var(--brand-grad);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  box-shadow: 0 8px 20px rgba(76, 111, 255, 0.35);
}
.head h3 {
  margin: 0;
  font-size: 18px;
  color: var(--text-1);
}
.head p {
  margin: 6px 0 0;
  color: var(--text-3);
  font-size: 13px;
}
.submit {
  width: 100%;
  margin-top: 8px;
  border-radius: 10px;
  height: 46px;
  background: var(--brand-grad);
  border: none;
  font-weight: 600;
}
</style>
