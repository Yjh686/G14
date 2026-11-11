<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hallApi, surveyApi } from '@/api/survey'
import QuestionRenderer from '@/components/QuestionRenderer.vue'
import { fillReasonText, isTextType, needOptions } from '@/utils/dict'
import type { AnswerInput, SurveyDetailVO } from '@/types/survey'

const route = useRoute()
const router = useRouter()

/**
 * 路由参数既可以是问卷 id，也可以是 8 位分享码。
 * 分享链接 /fill/Q3PTC5RC 与大厅跳转 /fill/1 走同一个页面，
 * 避免维护两套几乎相同的填写页。
 */
const key = computed(() => String(route.params.key ?? ''))
const isCode = computed(() => !/^\d+$/.test(key.value))

const loading = ref(true)
const submitting = ref(false)
const savingDraft = ref(false)

const survey = ref<SurveyDetailVO | null>(null)
/** 进入填写页时的状态快照，决定展示表单还是提示页 */
const state = ref<{
  fillable: boolean
  reason: string
  submitted: boolean
  sheetId: number | null
  submitTime: string | null
} | null>(null)

const answers = ref<Record<number, AnswerInput>>({})
const invalidIds = ref<number[]>([])
const draftTime = ref<string | null>(null)

/** 提交成功后的回执页数据 */
const receipt = ref<{ sheetId: number; submitTime: string; anonymous: boolean } | null>(null)

const questions = computed(() => survey.value?.questions ?? [])

const submittedAndLocked = computed(
  () => state.value?.submitted === true && survey.value?.limitOnce === 1,
)

const blockedReason = computed(() => {
  if (!state.value) return ''
  if (state.value.fillable) return ''
  if (state.value.submitted) return fillReasonText('ALREADY_SUBMITTED')
  return fillReasonText(state.value.reason)
})

/* ------------------------- 草稿自动保存 ------------------------- */

/** 5s 防抖：用户连续作答时不会每敲一个字就发一次请求（FR-FILL-04） */
let draftTimer: ReturnType<typeof setTimeout> | null = null
const DRAFT_DELAY = 5000

function scheduleDraftSave() {
  if (!state.value?.fillable || submittedAndLocked.value) return
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => {
    saveDraft()
  }, DRAFT_DELAY)
}

async function saveDraft(silent = true) {
  if (!survey.value || !state.value?.fillable) return
  const list = toAnswerList()
  if (list.length === 0) return
  savingDraft.value = true
  try {
    const res = await hallApi.saveDraft(survey.value.id, list)
    draftTime.value = res.savedAt
    if (!silent) ElMessage.success('草稿已保存')
  } catch (e) {
    // 草稿保存失败不打断作答，只在控制台留痕；提交时才是硬失败
    if (!silent) ElMessage.error((e as Error).message)
    else console.warn('[draft] 保存失败：', (e as Error).message)
  } finally {
    savingDraft.value = false
  }
}

/** 只把有内容的题发出去，空题不占草稿体积 */
function toAnswerList(): AnswerInput[] {
  const list: AnswerInput[] = []
  for (const q of questions.value) {
    const a = answers.value[q.id]
    if (!a) continue
    const hasOption = (a.optionValues?.length ?? 0) > 0
    const hasText = !!a.textValue && a.textValue.trim() !== ''
    const hasNumber = a.numberValue != null
    if (!hasOption && !hasText && !hasNumber) continue
    list.push({
      questionId: q.id,
      optionValues: hasOption ? a.optionValues : null,
      textValue: hasText ? a.textValue : null,
      numberValue: hasNumber ? a.numberValue : null,
    })
  }
  return list
}

/* ------------------------- 载入 ------------------------- */

async function load() {
  loading.value = true
  try {
    // 分享码走 /surveys/share/{code}（只需 survey:fill，学生可用）；
    // 纯数字 id 走 /surveys/{id}，只有问卷拥有者/预览权限能用，留给老师自查
    const detail = isCode.value
      ? await surveyApi.byShareCode(key.value.toUpperCase())
      : await surveyApi.detail(Number(key.value))
    survey.value = detail

    const fs = await hallApi.fillState(detail.id)
    state.value = {
      fillable: fs.fillable,
      reason: fs.reason,
      submitted: fs.submitted,
      sheetId: fs.sheetId,
      submitTime: fs.submitTime,
    }
    draftTime.value = fs.draftTime

    // 恢复草稿（断点续答）：把草稿数组摊成 questionId → AnswerInput
    const init: Record<number, AnswerInput> = {}
    for (const q of detail.questions ?? []) {
      init[q.id] = { questionId: q.id, optionValues: [], textValue: null, numberValue: null }
    }
    for (const a of fs.draftAnswers ?? []) {
      if (init[a.questionId]) {
        init[a.questionId] = {
          questionId: a.questionId,
          optionValues: a.optionValues ?? [],
          textValue: a.textValue ?? null,
          numberValue: a.numberValue ?? null,
        }
      }
    }
    answers.value = init
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.replace({ name: 'survey-hall' })
  } finally {
    loading.value = false
  }
}

/* ------------------------- 校验与提交 ------------------------- */

/** 本地校验只负责定位并高亮，真正的判定仍在后端 */
function validate(): { ok: boolean; message: string } {
  const bad: number[] = []
  for (const q of questions.value) {
    const a = answers.value[q.id]
    const required = q.isRequired === 1
    if (!required) continue
    let filled = false
    if (needOptions(q.questionType)) {
      filled = (a?.optionValues?.length ?? 0) > 0
    } else if (isTextType(q.questionType)) {
      filled = !!a?.textValue && a.textValue.trim() !== ''
    } else {
      filled = a?.numberValue != null
    }
    if (!filled) bad.push(q.id)
  }
  invalidIds.value = bad
  if (bad.length === 0) return { ok: true, message: '' }

  const idx = questions.value.findIndex((q) => q.id === bad[0])
  const q = questions.value[idx]
  if (!q) return { ok: false, message: '请完成必答题' }
  const suffix = bad.length > 1 ? `（还有 ${bad.length - 1} 道必答题未填）` : ''
  return { ok: false, message: `请完成第 ${idx + 1} 题「${q.title.slice(0, 14)}」${suffix}` }
}

async function submit() {
  const v = validate()
  if (!v.ok) {
    ElMessage.warning(v.message)
    return
  }
  const list = toAnswerList()
  if (list.length === 0) {
    ElMessage.warning('至少回答一道题才能提交')
    return
  }
  try {
    await ElMessageBox.confirm('提交后将无法修改，确定提交吗？', '提交确认', {
      type: 'warning',
      confirmButtonText: '提交',
      cancelButtonText: '再看看',
    })
  } catch {
    return
  }

  submitting.value = true
  try {
    if (draftTimer) clearTimeout(draftTimer)
    const sheet = await hallApi.submit(survey.value!.id, list)
    receipt.value = {
      sheetId: sheet.id,
      submitTime: sheet.submitTime,
      anonymous: sheet.anonymous,
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

function goHall() {
  router.push({ name: 'survey-hall' })
}

function goMyRecords() {
  router.push({ name: 'my-answers' })
}

/**
 * 子组件需要拿到一个确定的 AnswerInput 才能渲染 v-model，
 * 这里做空值兜底（例如题目刚加载完还没初始化）。
 */
function answerFor(questionId: number): AnswerInput {
  const existing = answers.value[questionId]
  if (existing) return existing
  const blank: AnswerInput = {
    questionId,
    optionValues: [],
    textValue: null,
    numberValue: null,
  }
  answers.value[questionId] = blank
  return blank
}

/** 作答变化后清理该题的错误高亮，并触发草稿防抖保存 */
function setAnswer(questionId: number, value: AnswerInput) {
  answers.value[questionId] = value
  if (invalidIds.value.includes(questionId)) {
    invalidIds.value = invalidIds.value.filter((id) => id !== questionId)
  }
  scheduleDraftSave()
}

watch(
  answers,
  () => {
    scheduleDraftSave()
  },
  { deep: true },
)

onMounted(load)

onBeforeUnmount(() => {
  if (draftTimer) clearTimeout(draftTimer)
  // 离开页面时补一次保存：防抖可能还没到时间
  if (state.value?.fillable && !receipt.value) {
    const list = toAnswerList()
    if (list.length > 0 && survey.value) {
      hallApi.saveDraft(survey.value.id, list).catch(() => {
        /* 离开时的尽力而为，失败不打扰用户 */
      })
    }
  }
})
</script>

<template>
  <div v-loading="loading" class="page fill-page">
    <!-- 提交成功回执（FR-FILL-05） -->
    <el-card v-if="receipt" shadow="never" class="center-card">
      <el-result icon="success" title="提交成功" sub-title="感谢你的参与，答卷已记录">
        <template #extra>
          <div class="receipt">
            <div class="receipt-row">
              <span class="receipt-label">回执编号</span>
              <span class="receipt-value">#{{ receipt.sheetId }}</span>
            </div>
            <div class="receipt-row">
              <span class="receipt-label">提交时间</span>
              <span class="receipt-value">{{ receipt.submitTime }}</span>
            </div>
            <el-alert
              v-if="receipt.anonymous"
              type="info"
              :closable="false"
              show-icon
              title="该问卷为匿名问卷，提交后不再显示作答内容"
              class="receipt-alert"
            />
          </div>
          <div class="receipt-actions">
            <el-button @click="goMyRecords">我的填写记录</el-button>
            <el-button type="primary" @click="goHall">返回问卷列表</el-button>
          </div>
        </template>
      </el-result>
    </el-card>

    <!-- 已提交且限填一次（FR-FILL-06） -->
    <el-card v-else-if="submittedAndLocked" shadow="never" class="center-card">
      <el-result icon="info" title="你已提交过该问卷" :sub-title="blockedReason">
        <template #extra>
          <div v-if="state?.submitTime" class="receipt">
            <div class="receipt-row">
              <span class="receipt-label">提交时间</span>
              <span class="receipt-value">{{ state.submitTime }}</span>
            </div>
          </div>
          <div class="receipt-actions">
            <el-button @click="goMyRecords">我的填写记录</el-button>
            <el-button type="primary" @click="goHall">返回问卷列表</el-button>
          </div>
        </template>
      </el-result>
    </el-card>

    <!-- 不可填写：未开始 / 已暂停 / 已结束 / 过期 -->
    <el-card v-else-if="state && !state.fillable" shadow="never" class="center-card">
      <el-result
        icon="warning"
        :title="blockedReason || '当前无法填写'"
        sub-title="请联系问卷发布者了解详情"
      >
        <template #extra>
          <el-button type="primary" @click="goHall">返回问卷列表</el-button>
        </template>
      </el-result>
    </el-card>

    <!-- 填写表单 -->
    <template v-else-if="survey">
      <el-card shadow="never" class="head-card">
        <h2 class="fill-title">{{ survey.title }}</h2>
        <p v-if="survey.description" class="fill-desc">{{ survey.description }}</p>
        <div class="fill-meta">
          <el-tag v-if="survey.anonymous === 1" size="small" type="info">匿名问卷</el-tag>
          <el-tag size="small" effect="plain">共 {{ questions.length }} 题</el-tag>
          <span v-if="survey.endTime" class="meta-item">截止 {{ survey.endTime }}</span>
          <span class="meta-item push-right">
            <template v-if="savingDraft">草稿保存中…</template>
            <template v-else-if="draftTime">草稿已保存于 {{ draftTime.slice(11) }}</template>
            <template v-else>作答会自动保存为草稿</template>
          </span>
        </div>
      </el-card>

      <el-card shadow="never">
        <QuestionRenderer
          v-for="(q, qi) in questions"
          :key="q.id"
          :model-value="answerFor(q.id)"
          :question="q"
          :index="qi"
          :invalid="invalidIds.includes(q.id)"
          @update:model-value="(v: AnswerInput) => setAnswer(q.id, v)"
        />

        <div class="submit-bar">
          <el-button :loading="savingDraft" @click="saveDraft(false)">保存草稿</el-button>
          <el-button type="primary" :loading="submitting" @click="submit">提交答卷</el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.fill-page {
  max-width: 880px;
  margin: 0 auto;
}

.center-card {
  border-radius: 8px;
}

.head-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.fill-title {
  margin: 0 0 8px;
  font-size: 20px;
  line-height: 1.4;
}

.fill-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
  white-space: pre-wrap;
}

.fill-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: #909399;
}

.meta-item {
  color: #a8abb2;
}

.push-right {
  margin-left: auto;
}

.submit-bar {
  display: flex;
  gap: 12px;
  justify-content: center;
  padding: 24px 0 8px;
}

.receipt {
  min-width: 280px;
  padding: 12px 16px;
  margin-bottom: 16px;
  text-align: left;
  background-color: #f7f9fc;
  border-radius: 8px;
}

.receipt-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
}

.receipt-label {
  color: #909399;
}

.receipt-value {
  font-family: Consolas, Monaco, monospace;
  color: #303133;
}

.receipt-alert {
  margin-top: 8px;
}

.receipt-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
</style>
