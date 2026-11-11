<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { answerApi, surveyApi } from '@/api/survey'
import { exportAnswers } from '@/api/stat'
import { useAuthStore } from '@/stores/auth'
import { surveyStatusLabel, surveyStatusType } from '@/utils/dict'
import type { AnswerSheetVO, AnswerSummaryVO, SurveyDetailVO } from '@/types/survey'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const surveyId = computed(() => Number(route.params.id))
const canInvalidate = computed(() => auth.hasPerm('answer:invalidate'))
const canDelete = computed(() => auth.hasPerm('answer:delete'))
/** 导出权限是 export:self / export:all，与「看答卷」的权限码不同 */
const canExport = computed(
  () => auth.hasPerm('export:self') || auth.hasPerm('export:all'),
)

const exporting = ref(false)
const loading = ref(true)
const survey = ref<SurveyDetailVO | null>(null)
const summary = ref<AnswerSummaryVO | null>(null)

const query = reactive({
  isValid: undefined as number | undefined,
  order: 'submit_desc' as 'submit_desc' | 'submit_asc',
  page: 1,
  size: 10,
})

const records = ref<AnswerSheetVO[]>([])
const total = ref(0)

async function loadSummary() {
  try {
    summary.value = await answerApi.summary(surveyId.value)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function loadAnswers() {
  try {
    const res = await answerApi.page({
      surveyId: surveyId.value,
      isValid: query.isValid,
      order: query.order,
      page: query.page,
      size: query.size,
    })
    records.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function load() {
  loading.value = true
  try {
    survey.value = await surveyApi.detail(surveyId.value)
    await Promise.all([loadSummary(), loadAnswers()])
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.replace({ name: 'surveys' })
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  query.page = 1
  loadAnswers()
}

function viewDetail(row: AnswerSheetVO) {
  router.push({ name: 'answer-detail', params: { sheetId: row.id } })
}

async function invalidate(row: AnswerSheetVO) {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      '标记为无效后该答卷不计入统计，可随时恢复。请填写原因（选填）',
      '标记无效',
      {
        confirmButtonText: '标记无效',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：重复提交、明显刷单',
        inputValue: '',
      },
    )
    reason = res.value ?? ''
  } catch {
    return
  }
  try {
    await answerApi.invalidate(row.id, reason || undefined)
    ElMessage.success('已标记为无效')
    await Promise.all([loadSummary(), loadAnswers()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function restore(row: AnswerSheetVO) {
  try {
    await answerApi.restore(row.id)
    ElMessage.success('已恢复为有效')
    await Promise.all([loadSummary(), loadAnswers()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(row: AnswerSheetVO) {
  try {
    await ElMessageBox.confirm(
      '删除后该答卷不再出现在列表中（软删除），确定删除吗？',
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await answerApi.remove(row.id)
    ElMessage.success('已删除')
    await Promise.all([loadSummary(), loadAnswers()])
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function doExport() {
  exporting.value = true
  try {
    const name = await exportAnswers(surveyId.value)
    ElMessage.success(`已导出：${name}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">
          答卷管理
          <el-tag
            v-if="survey"
            :type="surveyStatusType(survey.status)"
            size="small"
            class="ml-8"
          >
            {{ surveyStatusLabel(survey.status) }}
          </el-tag>
        </h2>
        <p class="page-desc">{{ survey?.title }}</p>
      </div>
      <div class="header-actions">
        <el-button @click="router.push({ name: 'surveys' })">返回列表</el-button>
        <el-button
          @click="router.push({ name: 'survey-stats', params: { id: surveyId } })"
        >
          统计分析
        </el-button>
        <el-button
          v-if="canExport"
          type="primary"
          :loading="exporting"
          @click="doExport"
        >
          导出 Excel
        </el-button>
      </div>
    </div>

    <!-- 回收概览（FR-ANS-05） -->
    <el-row v-if="summary" :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">已回收</div>
          <div class="stat-value">{{ summary.total }}</div>
          <div class="stat-hint">含无效答卷</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">有效答卷</div>
          <div class="stat-value is-primary">{{ summary.validCount }}</div>
          <div class="stat-hint">统计口径</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">无效答卷</div>
          <div class="stat-value is-danger">{{ summary.invalidCount }}</div>
          <div class="stat-hint">可恢复</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">完成率</div>
          <div class="stat-value is-success">{{ (summary.completionRate * 100).toFixed(2) }}%</div>
          <div class="stat-hint">应填 {{ summary.expectedCount }} 人</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-radio-group v-model="query.isValid" @change="resetAndLoad">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="1">有效</el-radio-button>
          <el-radio-button :value="0">无效</el-radio-button>
        </el-radio-group>
        <el-select v-model="query.order" style="width: 150px" @change="resetAndLoad">
          <el-option label="最新提交在前" value="submit_desc" />
          <el-option label="最早提交在前" value="submit_asc" />
        </el-select>
        <el-tag v-if="survey?.anonymous === 1" type="info" size="small">
          匿名问卷：不显示填写人
        </el-tag>
      </div>

      <el-table :data="records" stripe empty-text="还没有收到答卷">
        <el-table-column prop="id" label="编号" width="80">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="填写人" width="140">
          <template #default="{ row }">
            <span v-if="row.fillerName">{{ row.fillerName }}</span>
            <span v-else class="muted">匿名</span>
          </template>
        </el-table-column>
        <el-table-column label="学号/工号" width="140">
          <template #default="{ row }">
            <span v-if="row.fillerUsername">{{ row.fillerUsername }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="submitTime" label="提交时间" width="170" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.isValid === 1 ? 'success' : 'danger'" size="small">
              {{ row.isValid === 1 ? '有效' : '无效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="无效原因" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.invalidReason">{{ row.invalidReason }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewDetail((row as AnswerSheetVO))">查看</el-button>
            <el-button
              v-if="canInvalidate && row.isValid === 1"
              link
              type="warning"
              size="small"
              @click="invalidate((row as AnswerSheetVO))"
            >
              标记无效
            </el-button>
            <el-button
              v-if="canInvalidate && row.isValid === 0"
              link
              type="success"
              size="small"
              @click="restore((row as AnswerSheetVO))"
            >
              恢复
            </el-button>
            <el-button
              v-if="canDelete"
              link
              type="danger"
              size="small"
              @click="remove((row as AnswerSheetVO))"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadAnswers"
          @size-change="resetAndLoad"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.stat-row {
  margin-bottom: 4px;
}

.stat-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.stat-label {
  margin-bottom: 6px;
  font-size: 13px;
  color: #909399;
}

.stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  color: #303133;
}

.stat-value.is-primary {
  color: #2f7fd1;
}

.stat-value.is-danger {
  color: #f56c6c;
}

.stat-value.is-success {
  color: #67c23a;
}

.stat-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.ml-8 {
  margin-left: 8px;
}

.muted {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
