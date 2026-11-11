<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { surveyApi } from '@/api/survey'
import { templateApi } from '@/api/template'
import { categoryLabel, SURVEY_CATEGORIES, surveyStatusLabel } from '@/utils/dict'
import type { SurveyVO } from '@/types/survey'

/**
 * 共享为模板：选一份自己的问卷，后端会把当前题目结构拍成快照存进模板。
 * 题目结构不由前端上传，避免"前端传的结构"和"问卷真实结构"两份数据打架。
 */
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ shared: [] }>()

const submitting = ref(false)
const loadingSurveys = ref(false)
const surveys = ref<SurveyVO[]>([])

const form = reactive({
  sourceSurveyId: null as number | null,
  title: '',
  description: '',
  category: '',
  anonymousDefault: 1,
  limitOnceDefault: 1,
})

const selected = computed(() => surveys.value.find((s) => s.id === form.sourceSurveyId) ?? null)

const canSubmit = computed(
  () => form.sourceSurveyId != null && form.title.trim().length > 0 && !submitting.value,
)

async function loadSurveys() {
  loadingSurveys.value = true
  try {
    const res = await surveyApi.page({ scope: 'mine', page: 1, size: 100 })
    // 没有题目的问卷不能共享（后端也会拦），这里先过滤掉，少让用户白点一次
    surveys.value = res.records.filter((s) => (s.questionCount ?? 0) > 0)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loadingSurveys.value = false
  }
}

/** 选中问卷后，自动带出标题与继承的默认设置，用户还能改 */
function onSurveyChange(id: number | null) {
  const s = surveys.value.find((x) => x.id === id)
  if (!s) return
  form.title = s.title
  form.description = s.description ?? ''
  form.category = s.category ?? ''
  form.anonymousDefault = s.anonymous === 1 ? 1 : 0
  form.limitOnceDefault = s.limitOnce === 1 ? 1 : 0
}

async function submit() {
  if (form.sourceSurveyId == null) {
    ElMessage.warning('请先选择要共享的问卷')
    return
  }
  if (!form.title.trim()) {
    ElMessage.warning('模板名称不能为空')
    return
  }
  submitting.value = true
  try {
    await templateApi.share({
      sourceSurveyId: form.sourceSurveyId,
      title: form.title.trim(),
      description: form.description || null,
      category: form.category || null,
      anonymousDefault: form.anonymousDefault,
      limitOnceDefault: form.limitOnceDefault,
    })
    ElMessage.success('已提交审核，请等待管理员审核')
    emit('shared')
    visible.value = false
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

function reset() {
  form.sourceSurveyId = null
  form.title = ''
  form.description = ''
  form.category = ''
  form.anonymousDefault = 1
  form.limitOnceDefault = 1
}

watch(visible, (v) => {
  if (v) {
    reset()
    loadSurveys()
  }
})
</script>

<template>
  <el-dialog v-model="visible" title="共享为模板" width="600px" :close-on-click-modal="false">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="共享的是当前问卷的一份快照，之后你再改问卷不会影响已共享的模板。"
      class="tip"
    />

    <el-form label-width="100px">
      <el-form-item label="来源问卷" required>
        <el-select
          v-model="form.sourceSurveyId"
          :loading="loadingSurveys"
          placeholder="选择一份自己的问卷"
          filterable
          class="w-full"
          @change="onSurveyChange"
        >
          <el-option v-for="s in surveys" :key="s.id" :label="s.title" :value="s.id">
            <span class="opt-title">{{ s.title }}</span>
            <span class="opt-meta">
              {{ s.questionCount }} 题 · {{ surveyStatusLabel(s.status) }}
            </span>
          </el-option>
        </el-select>
        <div v-if="!loadingSurveys && surveys.length === 0" class="hint">
          你还没有带题目的问卷，先去创建一份再来共享
        </div>
      </el-form-item>

      <el-form-item label="模板名称" required>
        <el-input v-model="form.title" maxlength="120" show-word-limit placeholder="例如：课程满意度调查模板" />
      </el-form-item>

      <el-form-item label="模板说明">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="说明这个模板适合什么场景，别人会看到它"
        />
      </el-form-item>

      <el-form-item label="分类">
        <el-select v-model="form.category" clearable placeholder="继承来源问卷" class="w-full">
          <el-option
            v-for="c in SURVEY_CATEGORIES"
            :key="c.value"
            :label="c.label"
            :value="c.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="默认设置">
        <div class="switches">
          <span class="switch-item">
            <el-switch
              :model-value="form.anonymousDefault === 1"
              @update:model-value="(v: unknown) => (form.anonymousDefault = v === true || v === 1 ? 1 : 0)"
            />
            <span>使用者的问卷默认匿名</span>
          </span>
          <span class="switch-item">
            <el-switch
              :model-value="form.limitOnceDefault === 1"
              @update:model-value="(v: unknown) => (form.limitOnceDefault = v === true || v === 1 ? 1 : 0)"
            />
            <span>使用者的问卷默认限填一次</span>
          </span>
        </div>
      </el-form-item>

      <el-form-item v-if="selected" label="预览">
        <div class="preview">
          将共享《{{ selected.title }}》的当前 {{ selected.questionCount }} 道题
          <span v-if="form.category">，分类：{{ categoryLabel(form.category) }}</span>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
        提交审核
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-bottom: 16px;
}

.opt-title {
  margin-right: 8px;
}

.opt-meta {
  font-size: 12px;
  color: #a8abb2;
}

.hint {
  margin-top: 6px;
  font-size: 12px;
  color: #e6a23c;
}

.switches {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.switch-item {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  color: #606266;
}

.preview {
  font-size: 13px;
  color: #909399;
}
</style>
