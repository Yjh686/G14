<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hallApi } from '@/api/survey'
import { questionTypeLabel } from '@/utils/dict'
import type { AnswerSheetDetailVO } from '@/types/survey'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const detail = ref<AnswerSheetDetailVO | null>(null)

/** 匿名卷后端把 items / questions 都置为 null，这里分三种情况渲染 */
const anonymousHidden = computed(
  () => detail.value != null && (detail.value.items == null || detail.value.items.length === 0),
)

async function load() {
  loading.value = true
  try {
    detail.value = await hallApi.myDetail(Number(route.params.sheetId))
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.replace({ name: 'my-answers' })
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">答卷详情</h2>
        <p class="page-desc" v-if="detail">
          {{ detail.surveyTitle }} · 提交于 {{ detail.submitTime }}
        </p>
      </div>
      <el-button @click="router.push({ name: 'my-answers' })">返回记录</el-button>
    </div>

    <el-card v-if="detail" shadow="never">
      <el-alert
        v-if="anonymousHidden"
        type="info"
        :closable="false"
        show-icon
        title="该问卷为匿名问卷"
        description="为防止通过回显反推身份，提交后不再显示作答内容；如需修改请联系问卷发布者"
        class="anon-alert"
      />

      <template v-else>
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
              <el-tag
                v-for="(t, ti) in item.optionTexts"
                :key="ti"
                size="small"
                class="opt-tag"
              >
                {{ t }}
              </el-tag>
            </template>
            <template v-else-if="item.textValue">{{ item.textValue }}</template>
            <template v-else-if="item.numberValue != null">
              <el-rate :model-value="item.numberValue" disabled />
            </template>
            <span v-else class="muted">未作答</span>
          </div>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.anon-alert {
  margin-bottom: 8px;
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
  color: #303133;
}

.opt-tag {
  margin-right: 6px;
}

.muted {
  font-size: 13px;
  color: #c0c4cc;
}
</style>
