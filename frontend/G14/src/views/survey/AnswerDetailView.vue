<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { answerApi } from '@/api/survey'
import { questionTypeLabel } from '@/utils/dict'
import type { AnswerSheetDetailVO } from '@/types/survey'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const detail = ref<AnswerSheetDetailVO | null>(null)

async function load() {
  loading.value = true
  try {
    detail.value = await answerApi.detail(Number(route.params.sheetId))
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.back()
  } finally {
    loading.value = false
  }
}

function back() {
  if (detail.value) {
    router.push({ name: 'survey-answers', params: { id: detail.value.surveyId } })
  } else {
    router.back()
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">答卷 #{{ detail?.id }}</h2>
        <p class="page-desc" v-if="detail">
          {{ detail.surveyTitle }} · 提交于 {{ detail.submitTime }}
        </p>
      </div>
      <el-button @click="back">返回答卷列表</el-button>
    </div>

    <el-card v-if="detail" shadow="never">
      <div class="meta-bar">
        <div class="meta-item">
          <span class="meta-label">填写人</span>
          <span v-if="detail.fillerName">{{ detail.fillerName }}</span>
          <span v-else class="muted">匿名（问卷设置为匿名）</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">学号/工号</span>
          <span v-if="detail.fillerUsername">{{ detail.fillerUsername }}</span>
          <span v-else class="muted">-</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">答卷状态</span>
          <el-tag :type="detail.isValid === 1 ? 'success' : 'danger'" size="small">
            {{ detail.isValid === 1 ? '有效' : '无效' }}
          </el-tag>
          <span v-if="detail.invalidReason" class="reason">（{{ detail.invalidReason }}）</span>
        </div>
      </div>

      <el-divider />

      <div v-for="(item, i) in detail.items" :key="item.questionId" class="answer-item">
        <div class="answer-title">
          <span class="no">{{ i + 1 }}.</span>
          <span>{{ item.questionTitle }}</span>
          <el-tag size="small" type="info" effect="plain">
            {{ questionTypeLabel(item.questionType) }}
          </el-tag>
        </div>
        <div class="answer-value">
          <template v-if="item.optionTexts && item.optionTexts.length">
            <el-tag v-for="(t, ti) in item.optionTexts" :key="ti" size="small" class="opt-tag">
              {{ t }}
            </el-tag>
          </template>
          <template v-else-if="item.textValue">{{ item.textValue }}</template>
          <template v-else-if="item.numberValue != null">
            <el-rate :model-value="item.numberValue" disabled show-score />
          </template>
          <span v-else class="muted">未作答</span>
        </div>
      </div>

      <el-empty v-if="!detail.items || detail.items.length === 0" description="没有作答明细" />
    </el-card>
  </div>
</template>

<style scoped>
.meta-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 32px;
}

.meta-item {
  font-size: 13px;
}

.meta-label {
  margin-right: 8px;
  color: #909399;
}

.reason {
  margin-left: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.answer-item {
  padding: 14px 0;
  border-bottom: 1px solid #f0f2f5;
}

.answer-item:last-child {
  border-bottom: none;
}

.answer-title {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 500;
}

.no {
  color: #2f7fd1;
}

.answer-value {
  padding-left: 22px;
  font-size: 14px;
  line-height: 1.7;
}

.opt-tag {
  margin-right: 6px;
}

.muted {
  font-size: 13px;
  color: #c0c4cc;
}
</style>
