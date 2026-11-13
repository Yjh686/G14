<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '@/api/http'
import { recoveryPublicApi } from '@/api/recovery'
import { useConfigStore } from '@/stores/config'
import { validatePassword } from '@/utils/dict'

/**
 * 忘记密码 —— 密保问题自助找回。
 *
 * <p>做成三步而不是一步填完：
 * <ol>
 *   <li>输入学号 → 换回两个密保问题题面。用户得先看见"我当年设的是什么问题"，
 *       否则想不起来答的是哪个字段（宿舍号？还是床位号？）。</li>
 *   <li>回答两个问题 + 设新密码，一次提交。</li>
 *   <li>成功页提示去登录。</li>
 * </ol>
 * 中途不发放"临时票据"：密保问题文本本身就是公开的（任何知道学号的人都能看到），
 * 票据能保护的信息量约等于零，却要多一份存储、过期和防重放逻辑。
 */
const router = useRouter()
const config = useConfigStore()

const step = ref<1 | 2 | 3>(1)
const loading = ref(false)
const errorText = ref('')

const formRef = ref<FormInstance>()

const form = reactive({
  username: '',
  answer1: '',
  answer2: '',
  newPassword: '',
  confirmPassword: '',
})

/** 从 /lookup 拿回来的题面 */
const questions = reactive({
  question1: '',
  question2: '',
})

const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: '请输入学号/工号', trigger: 'blur' }],
  answer1: [{ required: true, message: '请回答密保问题', trigger: 'blur' }],
  answer2: [{ required: true, message: '请回答密保问题', trigger: 'blur' }],
  newPassword: [
    {
      validator: (_rule, value: string, callback) => {
        const err = validatePassword(value, config.passwordRules)
        callback(err ? new Error(err) : undefined)
      },
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    {
      validator: (_rule, value: string, callback) => {
        if (!value) return callback(new Error('请再次输入新密码'))
        if (value !== form.newPassword) return callback(new Error('两次输入的密码不一致'))
        callback()
      },
      trigger: 'blur',
    },
  ],
}))

/** 第一步：用学号换密保问题 */
async function handleLookup() {
  if (!formRef.value) return
  // 只校验 username 这一项，其它字段此刻还是空的
  const valid = await formRef.value.validateField('username').catch(() => false)
  if (!valid) return

  loading.value = true
  errorText.value = ''
  try {
    const q = await recoveryPublicApi.lookup({ username: form.username.trim() })
    questions.question1 = q.question1
    questions.question2 = q.question2
    step.value = 2
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '查询失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 第二步：答题 + 重设密码 */
async function handleReset() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorText.value = ''
  try {
    await recoveryPublicApi.reset({
      username: form.username.trim(),
      answer1: form.answer1.trim(),
      answer2: form.answer2.trim(),
      newPassword: form.newPassword,
    })
    step.value = 3
    ElMessage.success('密码已重置')
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '重置失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

/** 第二步里想换个学号重来 */
function backToStep1() {
  step.value = 1
  form.answer1 = ''
  form.answer2 = ''
  form.newPassword = ''
  form.confirmPassword = ''
  errorText.value = ''
  questions.question1 = ''
  questions.question2 = ''
}
</script>

<template>
  <div class="auth-wrapper">
    <el-card class="auth-card" shadow="always">
      <div class="auth-brand">{{ config.siteName }}</div>
      <p class="auth-subtitle">找回密码</p>

      <el-alert
        v-if="errorText"
        :title="errorText"
        type="error"
        :closable="false"
        show-icon
        class="recover-error"
      />

      <!-- ================= 第一步：输入学号 ================= -->
      <template v-if="step === 1">
        <el-alert
          type="info"
          :closable="false"
          class="recover-tip"
        >
          <template #title>
            输入学号后，我们会把你自己设置的两个密保问题显示出来。
            <br />
            如果从没设置过密保，请联系老师或管理员重置密码。
          </template>
        </el-alert>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleLookup"
        >
          <el-form-item label="学号 / 工号" prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入学号或工号"
              prefix-icon="User"
              clearable
              autocomplete="username"
              @keyup.enter="handleLookup"
            />
          </el-form-item>

          <el-form-item>
            <el-button
              type="primary"
              class="w-full"
              size="large"
              :loading="loading"
              @click="handleLookup"
            >
              下一步
            </el-button>
          </el-form-item>
        </el-form>
      </template>

      <!-- ================= 第二步：答题 + 新密码 ================= -->
      <template v-else-if="step === 2">
        <div class="recover-user">
          正在为 <strong>{{ form.username }}</strong> 重置密码
          <el-link type="primary" :underline="false" class="recover-switch" @click="backToStep1">
            换个账号
          </el-link>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="handleReset"
        >
          <el-form-item :label="questions.question1" prop="answer1">
            <el-input
              v-model="form.answer1"
              placeholder="请输入答案"
              clearable
              autocomplete="off"
            />
          </el-form-item>

          <el-form-item :label="questions.question2" prop="answer2">
            <el-input
              v-model="form.answer2"
              placeholder="请输入答案"
              clearable
              autocomplete="off"
            />
          </el-form-item>

          <el-divider />

          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="form.newPassword"
              type="password"
              placeholder="请设置新密码"
              prefix-icon="Lock"
              show-password
              autocomplete="new-password"
            />
            <!-- 提示文案跟着系统配置走，不能写死 8~32 位 -->
            <div class="field-tip">
              {{ config.passwordRuleText }}
            </div>
          </el-form-item>

          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              prefix-icon="Lock"
              show-password
              autocomplete="new-password"
              @keyup.enter="handleReset"
            />
          </el-form-item>

          <el-form-item>
            <el-button
              type="primary"
              class="w-full"
              size="large"
              :loading="loading"
              @click="handleReset"
            >
              重置密码
            </el-button>
          </el-form-item>
        </el-form>

        <p class="recover-note">
          连续答错会被临时锁定，具体次数与时长由管理员配置。
        </p>
      </template>

      <!-- ================= 第三步：成功 ================= -->
      <template v-else>
        <el-result
          icon="success"
          title="密码已重置"
          sub-title="出于安全考虑，其它设备上的登录状态已全部失效，请用新密码重新登录。"
        >
          <template #extra>
            <el-button type="primary" @click="router.push({ name: 'login' })">
              去登录
            </el-button>
          </template>
        </el-result>
      </template>

      <div v-if="step !== 3" class="auth-footer">
        <el-link type="primary" :underline="false" @click="router.push({ name: 'login' })">
          返回登录
        </el-link>
        <span class="footer-sep">·</span>
        <el-link type="primary" :underline="false" @click="router.push({ name: 'register' })">
          注册账号
        </el-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.recover-error {
  margin-bottom: 16px;
}

/* 与 ChangePasswordView 同款；这类提示在多个页面出现，但各自 scoped
   定义比塞进 global.css 更好维护——它是表单附属物，不是全局组件。 */
.field-tip {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.recover-tip {
  margin-bottom: 18px;
  line-height: 1.7;
}

.recover-user {
  margin-bottom: 16px;
  font-size: 13px;
  color: #606266;
}

.recover-switch {
  margin-left: 8px;
  font-size: 13px;
}

.recover-note {
  margin: 4px 0 16px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}

.footer-sep {
  margin: 0 8px;
  color: #dcdfe6;
}
</style>
