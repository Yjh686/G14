<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { roleLabel, validatePhone } from '@/utils/dict'

const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  realName: auth.user?.realName ?? '',
  department: auth.user?.department ?? '',
  phone: auth.user?.phone ?? '',
  email: auth.user?.email ?? '',
})

const rules: FormRules = {
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [
    {
      validator: (_rule, value: string, callback) => {
        const err = validatePhone(value)
        callback(err ? new Error(err) : undefined)
      },
      trigger: 'blur',
    },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.updateProfile({
      realName: form.realName.trim(),
      department: form.department.trim(),
      phone: form.phone.trim(),
      email: form.email.trim(),
    })
    ElMessage.success('资料已更新')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '更新失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <span class="card-title">个人资料</span>
      </template>

      <el-descriptions :column="2" border class="readonly-block">
        <el-descriptions-item label="学号/工号">
          {{ auth.user?.username }}
          <el-tag size="small" type="info" class="ml-8">不可修改</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="身份">
          {{ roleLabel(auth.user?.roleCode) }}
          <el-tag size="small" type="info" class="ml-8">由管理员分配</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="上次登录">
          {{ auth.user?.lastLoginTime || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="权限数量">
          {{ auth.permissions.length }} 项
        </el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">可修改信息</el-divider>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        class="profile-form"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="请输入真实姓名" clearable />
        </el-form-item>
        <el-form-item label="院系/班级" prop="department">
          <el-input v-model="form.department" placeholder="如 计算机学院2023级1班" clearable />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="用于找回账号" clearable />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="选填" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            保存修改
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.card-title {
  font-size: 15px;
  font-weight: 600;
}

.readonly-block {
  margin-bottom: 8px;
}

.profile-form {
  max-width: 520px;
}

.ml-8 {
  margin-left: 8px;
}
</style>
