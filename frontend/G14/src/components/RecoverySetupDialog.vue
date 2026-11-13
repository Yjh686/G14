<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiError } from '@/api/http'
import { recoveryApi } from '@/api/recovery'

/**
 * 设置 / 修改密保问题。
 *
 * <p>做成弹窗而不是独立页面：它只在个人资料页被用到，跳走再跳回来
 * 会让用户丢失正在编辑的资料（虽然这里没有未保存的输入，但心理上一样）。
 *
 * <p>回显时只填题面、不填答案 —— 服务端只存答案的 BCrypt 哈希，
 * 连管理员也看不到原文。所以「修改密保」本质上是「重新设一遍」，
 * 界面上直说，别让用户以为原来的答案能被带出来。
 */
const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  (e: 'saved'): void
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const loading = ref(false)
/** 是否已设置过（决定标题用「设置」还是「修改」） */
const hasExisting = ref(false)

const form = reactive({
  question1: '',
  answer1: '',
  question2: '',
  answer2: '',
})

/** 常用问题，点一下直接填，省得每次自己编 */
const presets = [
  '我的宿舍号是多少？',
  '我母亲的姓名是？',
  '我小学班主任姓什么？',
  '我的出生城市是？',
]

function usePreset(index: 1 | 2, text: string) {
  if (index === 1) form.question1 = text
  else form.question2 = text
}

const rules = computed<FormRules>(() => ({
  question1: [{ required: true, message: '请输入密保问题', trigger: 'blur' }],
  answer1: [
    { required: true, message: '请输入答案', trigger: 'blur' },
    { min: 2, message: '答案至少 2 个字符', trigger: 'blur' },
  ],
  question2: [
    { required: true, message: '请输入密保问题', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && value.trim() === form.question1.trim()) {
          return callback(new Error('两个密保问题不能相同'))
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  answer2: [
    { required: true, message: '请输入答案', trigger: 'blur' },
    { min: 2, message: '答案至少 2 个字符', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        // 与后端同口径：答案比较前会 trim + 小写，这里也按同样规则判重
        const a = value.trim().toLowerCase()
        const b = form.answer1.trim().toLowerCase()
        if (a && b && a === b) {
          return callback(new Error('两个密保答案不能相同'))
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}))

/** 每次打开都重新拉一次状态，避免在别处改过之后显示旧的 */
watch(visible, async (open) => {
  if (!open) return
  reset()
  loading.value = true
  try {
    hasExisting.value = await recoveryApi.mine()
    if (hasExisting.value) {
      const q = await recoveryApi.myQuestions()
      // 只回显题面，答案留空（服务端也没有原文可回）
      form.question1 = q.question1
      form.question2 = q.question2
    }
  } catch {
    // 查询失败按「没设置过」处理：让用户能填，提交时后端会判定
    hasExisting.value = false
  } finally {
    loading.value = false
  }
})

function reset() {
  form.question1 = ''
  form.answer1 = ''
  form.question2 = ''
  form.answer2 = ''
  formRef.value?.clearValidate()
}

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await recoveryApi.setup({
      items: [
        { question: form.question1.trim(), answer: form.answer1.trim() },
        { question: form.question2.trim(), answer: form.answer2.trim() },
      ],
    })
    ElMessage.success(hasExisting.value ? '密保问题已更新' : '密保问题已设置')
    visible.value = false
    emit('saved')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="hasExisting ? '修改密保问题' : '设置密保问题'"
    width="560px"
    :close-on-click-modal="false"
  >
    <el-alert type="info" :closable="false" class="setup-tip">
      <template #title>
        密保问题用于忘记密码时自助找回，<strong>请设置只有你自己答得上来的问题</strong>。
        <br />
        答案会加密封存，设置后任何人都无法查看原文，包括管理员。
      </template>
    </el-alert>

    <el-form
      ref="formRef"
      v-loading="loading"
      :model="form"
      :rules="rules"
      label-position="top"
    >
      <el-form-item label="密保问题 1" prop="question1">
        <el-input
          v-model="form.question1"
          placeholder="如 我的宿舍号是多少？"
          maxlength="120"
          show-word-limit
          clearable
        />
        <div class="preset-row">
          <el-tag
            v-for="p in presets"
            :key="p"
            size="small"
            class="preset-tag"
            @click="usePreset(1, p)"
          >
            {{ p }}
          </el-tag>
        </div>
      </el-form-item>

      <el-form-item label="答案 1" prop="answer1">
        <el-input
          v-model="form.answer1"
          placeholder="不区分大小写，忽略首尾空格"
          maxlength="200"
          show-word-limit
          clearable
        />
      </el-form-item>

      <el-divider />

      <el-form-item label="密保问题 2" prop="question2">
        <el-input
          v-model="form.question2"
          placeholder="再设一个不同的问题"
          maxlength="120"
          show-word-limit
          clearable
        />
        <div class="preset-row">
          <el-tag
            v-for="p in presets"
            :key="p"
            size="small"
            class="preset-tag"
            @click="usePreset(2, p)"
          >
            {{ p }}
          </el-tag>
        </div>
      </el-form-item>

      <el-form-item label="答案 2" prop="answer2">
        <el-input
          v-model="form.answer2"
          placeholder="不区分大小写，忽略首尾空格"
          maxlength="200"
          show-word-limit
          clearable
        />
      </el-form-item>
    </el-form>

    <el-alert
      v-if="hasExisting"
      type="warning"
      :closable="false"
      show-icon
      class="setup-warn"
      title="原来的答案无法回显，保存后会完全覆盖之前的密保问题。"
    />

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.setup-tip {
  margin-bottom: 16px;
  line-height: 1.7;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.preset-tag {
  cursor: pointer;
}

.preset-tag:hover {
  color: var(--el-color-primary);
}

.setup-warn {
  margin-top: 4px;
}
</style>
