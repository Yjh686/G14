<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { surveyApi } from '@/api/survey'
import {
  isTextType,
  needOptions,
  QUESTION_TYPES,
  questionTypeLabel,
  SURVEY_CATEGORIES,
  surveyStatusLabel,
  surveyStatusType,
} from '@/utils/dict'
import type {
  QuestionSaveRequest,
  QuestionVO,
  SurveyDetailVO,
  SurveySaveRequest,
} from '@/types/survey'

const route = useRoute()
const router = useRouter()

/** 有 id 表示编辑已有问卷，无 id 表示新建 */
const surveyId = computed(() => {
  const raw = route.params.id
  return raw ? Number(raw) : null
})
const isNew = computed(() => surveyId.value === null)

const loading = ref(false)
const saving = ref(false)
const survey = ref<SurveyDetailVO | null>(null)

/** 只有草稿可编辑题目与设置，已发布只能改标题/简介/截止时间（R-SUR-2） */
const structureEditable = computed(() => !survey.value || survey.value.status === 'DRAFT')

/** 题目编辑用本地副本，保存时整卷提交（后端做差量） */
const questions = ref<QuestionSaveRequest[]>([])

const meta = reactive<SurveySaveRequest>({
  title: '',
  description: '',
  category: 'TEACHING',
  anonymous: 0,
  limitOnce: 1,
  startTime: null,
  endTime: null,
})

/** el-date-picker 需要 Date 对象，单独维护避免与字符串格式互转时抖动 */
const startTimeDate = ref<Date | null>(null)
const endTimeDate = ref<Date | null>(null)

function toApiTime(d: Date | null): string | null {
  if (!d) return null
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function toDate(s: string | null | undefined): Date | null {
  if (!s) return null
  // 后端格式 yyyy-MM-dd HH:mm:ss，Safari/Firefox 对空格分隔不认，换成分隔符
  const d = new Date(s.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

function fillFrom(detail: SurveyDetailVO) {
  survey.value = detail
  meta.title = detail.title
  meta.description = detail.description ?? ''
  meta.category = detail.category
  meta.anonymous = detail.anonymous
  meta.limitOnce = detail.limitOnce
  startTimeDate.value = toDate(detail.startTime)
  endTimeDate.value = toDate(detail.endTime)
  questions.value = (detail.questions ?? []).map(toEditable)
}

function toEditable(q: QuestionVO): QuestionSaveRequest {
  return {
    id: q.id,
    questionType: q.questionType,
    title: q.title,
    description: q.description ?? '',
    isRequired: q.isRequired,
    maxLength: q.maxLength ?? undefined,
    ratingMax: q.ratingMax ?? undefined,
    options: (q.options ?? []).map((o) => ({ content: o.content })),
  }
}

async function load() {
  if (isNew.value) {
    addQuestion('SINGLE')
    return
  }
  loading.value = true
  try {
    fillFrom(await surveyApi.detail(surveyId.value!))
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.replace({ name: 'surveys' })
  } finally {
    loading.value = false
  }
}

function addQuestion(type = 'SINGLE') {
  questions.value.push({
    questionType: type,
    title: '',
    description: '',
    isRequired: 1,
    maxLength: isTextType(type) ? (type === 'TEXTAREA' ? 500 : 100) : undefined,
    ratingMax: type === 'RATING' ? 5 : undefined,
    options: needOptions(type)
      ? [{ content: '' }, { content: '' }]
      : undefined,
  })
}

function removeQuestion(index: number) {
  questions.value.splice(index, 1)
}

function moveQuestion(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= questions.value.length) return
  const list = questions.value
  const item = list.splice(index, 1)[0]
  if (!item) return
  list.splice(target, 0, item)
}

/** 题型切换时重置该题专属字段，避免残留 maxLength 之类的脏数据 */
function onTypeChange(q: QuestionSaveRequest) {
  if (needOptions(q.questionType)) {
    if (!q.options || q.options.length < 2) {
      q.options = [{ content: '' }, { content: '' }]
    }
    q.maxLength = undefined
    q.ratingMax = undefined
  } else if (q.questionType === 'RATING') {
    q.options = undefined
    q.maxLength = undefined
    q.ratingMax = q.ratingMax ?? 5
  } else {
    q.options = undefined
    q.ratingMax = undefined
    q.maxLength = q.maxLength ?? (q.questionType === 'TEXTAREA' ? 500 : 100)
  }
}

function addOption(q: QuestionSaveRequest) {
  if (!q.options) q.options = []
  q.options.push({ content: '' })
}

function removeOption(q: QuestionSaveRequest, index: number) {
  q.options?.splice(index, 1)
}

/** 前端预检：与后端校验规则一致，只是为了让用户更早看到问题 */
function validateLocal(): string | null {
  if (!meta.title.trim()) return '请填写问卷标题'
  if (meta.title.length > 120) return '标题不能超过 120 字'
  if (structureEditable.value) {
    if (questions.value.length === 0) return '至少添加 1 道题'
    for (let i = 0; i < questions.value.length; i++) {
      const q = questions.value[i]
      if (!q) continue
      const label = `第 ${i + 1} 题`
      if (!q.title.trim()) return `${label}的题干不能为空`
      if (q.title.length > 255) return `${label}的题干不能超过 255 字`
      if (needOptions(q.questionType)) {
        const opts = (q.options ?? []).filter((o) => o.content.trim())
        if (opts.length < 2) return `${label}至少需要 2 个选项`
        const set = new Set(opts.map((o) => o.content.trim()))
        if (set.size !== opts.length) return `${label}存在重复选项`
      }
    }
  }
  return null
}

async function save(showMessage = true) {
  const err = validateLocal()
  if (err) {
    ElMessage.warning(err)
    return false
  }
  meta.startTime = toApiTime(startTimeDate.value)
  meta.endTime = toApiTime(endTimeDate.value)

  saving.value = true
  try {
    let id = surveyId.value
    if (!id) {
      const created = await surveyApi.create({ ...meta })
      id = created.id
    } else {
      await surveyApi.update(id, { ...meta })
    }
    // 题目只在草稿态能改；已发布问卷不动题目
    if (structureEditable.value) {
      await surveyApi.saveQuestions(id!, questions.value)
    }
    const detail = await surveyApi.detail(id!)
    fillFrom(detail)
    if (showMessage) ElMessage.success('已保存')
    if (isNew.value) {
      router.replace({ name: 'survey-edit', params: { id: id! } })
    }
    return true
  } catch (e) {
    ElMessage.error((e as Error).message)
    return false
  } finally {
    saving.value = false
  }
}

async function publish() {
  const ok = await save(false)
  if (!ok) return
  try {
    await ElMessageBox.confirm(
      '发布后题目结构将被锁定，只能修改标题、简介与截止时间。确定发布吗？',
      '发布确认',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await surveyApi.publish(surveyId.value!)
    ElMessage.success('问卷已发布')
    const detail = await surveyApi.detail(surveyId.value!)
    fillFrom(detail)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function preview() {
  const ok = await save(false)
  if (!ok) return
  router.push({ name: 'survey-preview', params: { id: surveyId.value } })
}

function back() {
  router.push({ name: 'surveys' })
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">
          {{ isNew ? '新建问卷' : '编辑问卷' }}
          <el-tag
            v-if="survey"
            :type="surveyStatusType(survey.status)"
            size="small"
            class="ml-8"
          >
            {{ surveyStatusLabel(survey.status) }}
          </el-tag>
        </h2>
        <p class="page-desc">
          <template v-if="structureEditable">
            草稿状态下可自由修改题目与设置；发布后题目结构会锁定
          </template>
          <template v-else>
            问卷已发布，只能修改标题、简介与截止时间；要改题目请「复制为新草稿」
          </template>
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="back">返回列表</el-button>
        <el-button v-if="survey" @click="preview">预览</el-button>
        <el-button :loading="saving" @click="save()">保存</el-button>
        <el-button
          v-if="survey && survey.status === 'DRAFT'"
          type="success"
          @click="publish"
        >
          发布
        </el-button>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="17">
        <el-card shadow="never" class="card">
          <template #header>
            <div class="card-head">
              <span class="card-title">题目（{{ questions.length }}）</span>
              <div v-if="structureEditable">
                <el-dropdown
                  trigger="click"
                  @command="(t: string) => addQuestion(t)"
                >
                  <el-button type="primary" plain size="small">添加题目</el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-for="t in QUESTION_TYPES"
                        :key="t.value"
                        :command="t.value"
                      >
                        {{ t.label }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </template>

          <el-empty v-if="questions.length === 0" description="还没有题目，先添加一道吧" />

          <div v-for="(q, qi) in questions" :key="qi" class="question-item">
            <div class="question-head">
              <span class="question-no">第 {{ qi + 1 }} 题</span>
              <el-select
                v-model="q.questionType"
                size="small"
                style="width: 120px"
                :disabled="!structureEditable"
                @change="onTypeChange(q)"
              >
                <el-option
                  v-for="t in QUESTION_TYPES"
                  :key="t.value"
                  :label="t.label"
                  :value="t.value"
                />
              </el-select>
              <el-checkbox
                v-if="!isTextType(q.questionType)"
                v-model="q.isRequired"
                :true-value="1"
                :false-value="0"
                :disabled="!structureEditable"
                size="small"
              >
                必答
              </el-checkbox>
              <el-checkbox
                v-else
                v-model="q.isRequired"
                :true-value="1"
                :false-value="0"
                :disabled="!structureEditable"
                size="small"
              >
                必答
              </el-checkbox>
              <div class="spacer" />
              <template v-if="structureEditable">
                <el-button link size="small" :disabled="qi === 0" @click="moveQuestion(qi, -1)">
                  上移
                </el-button>
                <el-button
                  link
                  size="small"
                  :disabled="qi === questions.length - 1"
                  @click="moveQuestion(qi, 1)"
                >
                  下移
                </el-button>
                <el-button link type="danger" size="small" @click="removeQuestion(qi)">
                  删除
                </el-button>
              </template>
            </div>

            <el-input
              v-model="q.title"
              placeholder="请输入题干"
              maxlength="255"
              show-word-limit
              :disabled="!structureEditable"
              class="field"
            />

            <el-input
              v-model="q.description"
              placeholder="补充说明（选填）"
              maxlength="500"
              :disabled="!structureEditable"
              class="field"
            />

            <!-- 选择题：选项编辑 -->
            <div v-if="needOptions(q.questionType)" class="options">
              <div class="options-label">
                {{ q.questionType === 'SINGLE' ? '选项（单选）' : '选项（多选）' }}
                <span class="hint">至少 2 项</span>
              </div>
              <div v-for="(o, oi) in q.options" :key="oi" class="option-row">
                <span class="option-mark">{{ oi + 1 }}</span>
                <el-input
                  v-model="o.content"
                  placeholder="选项内容"
                  maxlength="255"
                  size="small"
                  :disabled="!structureEditable"
                />
                <el-button
                  v-if="structureEditable"
                  link
                  type="danger"
                  size="small"
                  :disabled="(q.options?.length ?? 0) <= 2"
                  @click="removeOption(q, oi)"
                >
                  删除
                </el-button>
              </div>
              <el-button
                v-if="structureEditable"
                link
                type="primary"
                size="small"
                @click="addOption(q)"
              >
                + 添加选项
              </el-button>
            </div>

            <!-- 文本题：字数上限 -->
            <div v-if="isTextType(q.questionType)" class="inline-field">
              <span class="label">字数上限</span>
              <el-input-number
                v-model="q.maxLength"
                :min="1"
                :max="2000"
                size="small"
                :disabled="!structureEditable"
              />
              <span class="hint">1 ~ 2000</span>
            </div>

            <!-- 评分题：分值上限 -->
            <div v-if="q.questionType === 'RATING'" class="inline-field">
              <span class="label">评分上限</span>
              <el-input-number
                v-model="q.ratingMax"
                :min="2"
                :max="10"
                size="small"
                :disabled="!structureEditable"
              />
              <span class="hint">2 ~ 10，缺省 5</span>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="7">
        <el-card shadow="never" class="card">
          <template #header><span class="card-title">问卷设置</span></template>
          <el-form label-position="top">
            <el-form-item label="标题" required>
              <el-input
                v-model="meta.title"
                maxlength="120"
                show-word-limit
                placeholder="例如：2024 秋季教学满意度调查"
              />
            </el-form-item>
            <el-form-item label="简介">
              <el-input
                v-model="meta.description"
                type="textarea"
                :rows="3"
                maxlength="1000"
                show-word-limit
                placeholder="填写说明（选填）"
              />
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="meta.category" :disabled="!structureEditable" class="w-full">
                <el-option
                  v-for="c in SURVEY_CATEGORIES"
                  :key="c.value"
                  :label="c.label"
                  :value="c.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="匿名填写">
              <el-switch
                v-model="meta.anonymous"
                :active-value="1"
                :inactive-value="0"
                :disabled="!structureEditable"
                active-text="匿名"
                inactive-text="实名"
              />
              <div class="form-hint">
                匿名问卷的发布者看不到填写人，后端仅存 user_id 用于限填一次
              </div>
            </el-form-item>
            <el-form-item label="限填一次">
              <el-switch
                v-model="meta.limitOnce"
                :active-value="1"
                :inactive-value="0"
                :disabled="!structureEditable"
                active-text="每人一次"
                inactive-text="可重复"
              />
            </el-form-item>
            <el-form-item label="开始时间">
              <el-date-picker
                v-model="startTimeDate"
                type="datetime"
                placeholder="留空表示立即开始"
                :disabled="!structureEditable"
                class="w-full"
              />
            </el-form-item>
            <el-form-item label="截止时间">
              <el-date-picker
                v-model="endTimeDate"
                type="datetime"
                placeholder="留空表示长期有效"
                class="w-full"
              />
            </el-form-item>
          </el-form>

          <el-alert
            v-if="survey && survey.shareCode"
            type="success"
            :closable="false"
            class="share-alert"
          >
            <div class="share-title">分享码</div>
            <div class="share-code">{{ survey.shareCode }}</div>
            <div class="share-hint">学生可在「填写问卷」页输入分享码，或直接访问 /fill/{{ survey.shareCode }}</div>
          </el-alert>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.ml-8 {
  margin-left: 8px;
}

.question-item {
  padding: 16px 0;
  border-bottom: 1px dashed #e4e7ed;
}

.question-item:last-child {
  border-bottom: none;
}

.question-head {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.question-no {
  font-size: 13px;
  font-weight: 600;
  color: #2f7fd1;
}

.spacer {
  flex: 1;
}

.field {
  margin-bottom: 8px;
}

.options {
  padding: 10px 12px;
  margin-bottom: 8px;
  background-color: #fafbfc;
  border-radius: 6px;
}

.options-label {
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
}

.hint {
  margin-left: 6px;
  font-size: 12px;
  color: #c0c4cc;
}

.option-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}

.option-mark {
  width: 18px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}

.inline-field {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  color: #606266;
}

.label {
  min-width: 60px;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #c0c4cc;
}

.share-alert {
  margin-top: 8px;
}

.share-title {
  font-size: 12px;
}

.share-code {
  margin: 4px 0;
  font-family: Consolas, Monaco, monospace;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 3px;
}

.share-hint {
  font-size: 12px;
  line-height: 1.5;
}
</style>
