<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { surveyApi } from '@/api/survey'
import QuestionRenderer from '@/components/QuestionRenderer.vue'
import { categoryLabel } from '@/utils/dict'
import type { AnswerInput, SurveyDetailVO } from '@/types/survey'

/**
 * 预览页：与填写页共用 QuestionRenderer，但全部只读。
 * 预览走 /surveys/{id}/preview 而不是 /surveys/{id}，
 * 因为它只要求 survey:preview，不必是问卷拥有者。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(true)
const survey = ref<SurveyDetailVO | null>(null)
/** 预览也需要一个 AnswerInput 绑定，否则组件里的 v-model 无处落脚 */
const answers = ref<Record<number, AnswerInput>>({})

async function load() {
  loading.value = true
  try {
    const detail = await surveyApi.preview(Number(route.params.id))
    survey.value = detail
    const init: Record<number, AnswerInput> = {}
    for (const q of detail.questions ?? []) {
      init[q.id] = { questionId: q.id, optionValues: [], textValue: null, numberValue: null }
    }
    answers.value = init
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.back()
  } finally {
    loading.value = false
  }
}

/** 预览也需要给子组件一个确定的 AnswerInput 才好在 readonly 下渲染 */
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

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page preview-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">问卷预览</h2>
        <p class="page-desc">这是填写者看到的样子，预览中的作答不会被保存</p>
      </div>
      <el-button @click="router.back()">返回编辑</el-button>
    </div>

    <el-card v-if="survey" shadow="never" class="head-card">
      <h2 class="preview-title">{{ survey.title }}</h2>
      <p v-if="survey.description" class="preview-desc">{{ survey.description }}</p>
      <div class="preview-meta">
        <el-tag size="small" type="info">{{ categoryLabel(survey.category) }}</el-tag>
        <el-tag v-if="survey.anonymous === 1" size="small" effect="plain">匿名</el-tag>
        <el-tag size="small" effect="plain">共 {{ survey.questions?.length ?? 0 }} 题</el-tag>
        <el-tag v-if="survey.limitOnce === 1" size="small" effect="plain">每人限填一次</el-tag>
      </div>
    </el-card>

    <el-card v-if="survey" shadow="never">
      <el-empty v-if="!survey.questions || survey.questions.length === 0" description="还没有题目" />
      <QuestionRenderer
        v-for="(q, qi) in survey.questions"
        :key="q.id"
        :model-value="answerFor(q.id)"
        :question="q"
        :index="qi"
        readonly
      />
    </el-card>
  </div>
</template>

<style scoped>
.preview-page {
  max-width: 880px;
  margin: 0 auto;
}

.head-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.preview-title {
  margin: 0 0 8px;
  font-size: 20px;
}

.preview-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
  white-space: pre-wrap;
}

.preview-meta {
  display: flex;
  gap: 8px;
}
</style>
