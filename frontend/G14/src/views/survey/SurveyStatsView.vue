<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BaseChart from '@/components/BaseChart.vue'
import { statApi, exportAnswers } from '@/api/stat'
import { surveyApi } from '@/api/survey'
import { useAuthStore } from '@/stores/auth'
import { categoryLabel, questionTypeLabel, surveyStatusLabel, surveyStatusType } from '@/utils/dict'
import { barOption, percent, pieOption, ratingOption, wordOption } from '@/utils/chart'
import type { QuestionStatsVO, SurveyStatsVO } from '@/types/stat'
import type { SurveyDetailVO } from '@/types/survey'

/**
 * 统计与分析页（FR-STAT）。
 *
 * 设计取舍：
 * - 一次性取整卷统计再本地切题，不做「点一题拉一次」：
 *     课级问卷最多 100 题、单份统计 JSON 通常几十 KB，一次拉完切题零延迟；
 *     真要优化也是后端加缓存，而不是把等待转嫁给每次点击。
 * - 单选给柱状图 + 饼图，多选只给柱状图：
 *     多选题各选项占比相加超过 100%，饼图的扇形在数学上不成立，
 *     画出来会让人以为分母是选项总数。
 * - 文本题默认只显示前 20 条，完整内容引导去导出。
 */

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const surveyId = computed(() => Number(route.params.id))
const canExport = computed(
  () => auth.hasPerm('export:self') || auth.hasPerm('export:all'),
)

const loading = ref(true)
const exporting = ref(false)
const survey = ref<SurveyDetailVO | null>(null)
const stats = ref<SurveyStatsVO | null>(null)
const activeId = ref<number | null>(null)
/** 文本题展开全部作答 */
const expandText = ref<Record<number, boolean>>({})

const questions = computed<QuestionStatsVO[]>(() => stats.value?.questions ?? [])

const active = computed<QuestionStatsVO | null>(() => {
  if (!questions.value.length) return null
  const found = questions.value.find((q) => q.questionId === activeId.value)
  return found ?? questions.value[0]!
})

/** 当前题是否「一道都没答」，用于统一显示空状态 */
const activeEmpty = computed(() => (active.value?.answeredCount ?? 0) === 0)

const optionTotal = computed(() => active.value?.answeredCount ?? 0)

async function load() {
  loading.value = true
  try {
    survey.value = await surveyApi.detail(surveyId.value)
    stats.value = await statApi.survey(surveyId.value)
    if (questions.value.length && activeId.value == null) {
      activeId.value = questions.value[0]!.questionId
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
    router.replace({ name: 'surveys' })
  } finally {
    loading.value = false
  }
}

async function refresh() {
  try {
    stats.value = await statApi.survey(surveyId.value)
    ElMessage.success('已刷新')
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

/** 文本作答列表：默认 20 条，展开后全给（后端最多 200） */
function visibleAnswers(q: QuestionStatsVO) {
  const all = q.text?.answers ?? []
  return expandText.value[q.questionId] ? all : all.slice(0, 20)
}

function toggleText(q: QuestionStatsVO) {
  expandText.value[q.questionId] = !expandText.value[q.questionId]
}

function jumpFill(key: string | null) {
  if (!key) return
  router.push({ name: 'survey-fill', params: { key } })
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">
          统计分析
          <el-tag
            v-if="stats"
            :type="surveyStatusType(stats.status)"
            size="small"
            class="ml-8"
          >
            {{ surveyStatusLabel(stats.status) }}
          </el-tag>
          <el-tag v-if="stats?.anonymous" type="info" size="small" class="ml-6">匿名</el-tag>
        </h2>
        <p class="page-desc">
          {{ stats?.surveyTitle }}
          <span v-if="stats?.categoryLabel || stats?.category" class="ml-8 muted">
            · {{ categoryLabel(stats?.category) }}
          </span>
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="router.push({ name: 'survey-answers', params: { id: surveyId } })">
          答卷列表
        </el-button>
        <el-button :loading="exporting" @click="refresh">刷新</el-button>
        <el-button v-if="canExport" type="primary" :loading="exporting" @click="doExport">
          导出 Excel
        </el-button>
      </div>
    </div>

    <!-- 概览：口径写在卡片上，避免「为什么数字和答卷数不一样」的追问 -->
    <el-row v-if="stats" :gutter="16" class="overview">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">有效答卷</div>
          <div class="stat-value is-primary">{{ stats.validSheetCount }}</div>
          <div class="stat-hint">以下所有数字都只算这些</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">回收总数</div>
          <div class="stat-value">{{ stats.totalSheetCount }}</div>
          <div class="stat-hint">含已被标记无效的</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">题目数</div>
          <div class="stat-value">{{ stats.questions.length }}</div>
          <div class="stat-hint">发布后结构锁定</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">统计时间</div>
          <div class="stat-time">{{ stats.generatedAt }}</div>
          <div class="stat-hint">
            <el-link
              v-if="stats.status === 'PUBLISHED'"
              type="primary"
              :underline="false"
              @click="jumpFill(survey?.shareCode ?? String(surveyId))"
            >
              去填写页
            </el-link>
            <span v-else>按需查询，未做预聚合</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-alert
      v-if="stats && stats.validSheetCount === 0"
      type="info"
      show-icon
      :closable="false"
      class="tip"
      title="还没有有效答卷"
      description="问卷发布后收到有效答卷才会产生统计数据；已被标记为无效的答卷不计入。"
    />

    <el-card v-if="questions.length" shadow="never" class="main-card">
      <!-- 题目切换：竖向 tabs，题干通常较长，横排会被挤断 -->
      <div class="stat-layout">
        <div class="q-nav">
          <button
            v-for="q in questions"
            :key="q.questionId"
            class="q-nav-item"
            :class="{ active: q.questionId === active?.questionId }"
            type="button"
            @click="activeId = q.questionId"
          >
            <span class="q-no">第 {{ q.sortNo }} 题</span>
            <span class="q-title">{{ q.title }}</span>
            <span class="q-meta">
              {{ questionTypeLabel(q.questionType) }} · {{ q.answeredCount }} 人作答
            </span>
          </button>
        </div>

        <div v-if="active" class="q-body">
          <div class="q-head">
            <h3 class="q-head-title">
              第 {{ active.sortNo }} 题 · {{ active.title }}
            </h3>
            <div class="q-head-tags">
              <el-tag size="small" type="info">{{ questionTypeLabel(active.questionType) }}</el-tag>
              <el-tag size="small">{{ active.answeredCount }} 人作答</el-tag>
              <el-tag v-if="active.answeredCount < (stats?.validSheetCount ?? 0)" size="small" type="warning">
                有 {{ (stats?.validSheetCount ?? 0) - active.answeredCount }} 人未答
              </el-tag>
            </div>
          </div>
          <p v-if="active.description" class="q-desc">{{ active.description }}</p>

          <!-- 单选 / 多选 -->
          <template v-if="active.options">
            <el-alert
              v-if="active.questionType === 'MULTIPLE'"
              type="info"
              :closable="false"
              show-icon
              class="tip"
              title="多选题占比之和会超过 100%"
              description="分母是「该题有效作答人数」，不是选项总数，本页不画饼图也是这个原因。"
            />
            <el-row :gutter="16">
              <el-col :xs="24" :md="active.questionType === 'SINGLE' ? 14 : 24">
                <BaseChart
                  :option="barOption(active.options, active.answeredCount)"
                  :empty="activeEmpty"
                  height="300px"
                />
              </el-col>
              <el-col v-if="active.questionType === 'SINGLE'" :xs="24" :md="10">
                <BaseChart :option="pieOption(active.options)" :empty="activeEmpty" height="300px" />
              </el-col>
            </el-row>

            <el-table :data="active.options" size="small" class="detail-table" stripe>
              <el-table-column type="index" label="#" width="50" align="center" />
              <el-table-column prop="content" label="选项" min-width="200" />
              <el-table-column label="人数" width="90" align="center">
                <template #default="{ row }">{{ row.count }}</template>
              </el-table-column>
              <el-table-column label="占比" width="150">
                <template #default="{ row }">
                  <div class="ratio-cell">
                    <el-progress
                      :percentage="Math.round((row.ratio ?? 0) * 1000) / 10"
                      :show-text="false"
                      :stroke-width="8"
                    />
                    <span class="ratio-text">{{ percent(row.ratio) }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <p class="foot-note">
              占比分母 = 该题有效作答人数 {{ optionTotal }} 人
              <span v-if="active.questionType === 'MULTIPLE'">（多选，故合计大于 100%）</span>
            </p>
          </template>

          <!-- 评分 -->
          <template v-else-if="active.rating">
            <div v-if="!activeEmpty" class="rating-head">
              <div class="rating-avg">
                <!-- 验收标准 2.3 要求平均分保留 1 位小数；接口 JSON 仍给精确值，只在展示层取整 -->
                <span class="rating-num">{{ active.rating.average.toFixed(1) }}</span>
                <span class="rating-unit">/ {{ active.rating.ratingMax }}</span>
              </div>
              <div class="rating-meta">
                <el-rate
                  :model-value="Math.round(active.rating.average)"
                  :max="active.rating.ratingMax"
                  disabled
                  show-score
                  :score-template="`平均 ${active.rating.average.toFixed(1)} 分`"
                />
                <div class="muted">参评 {{ active.rating.answeredCount }} 人</div>
              </div>
            </div>
            <BaseChart
              :option="ratingOption(active.rating)"
              :empty="activeEmpty"
              height="280px"
            />
            <el-table :data="active.rating.distribution" size="small" stripe class="detail-table">
              <el-table-column label="星级" width="120">
                <template #default="{ row }">{{ row.star }} 星</template>
              </el-table-column>
              <el-table-column label="人数" width="100" align="center">
                <template #default="{ row }">{{ row.count }}</template>
              </el-table-column>
              <el-table-column label="占比">
                <template #default="{ row }">{{ percent(row.ratio) }}</template>
              </el-table-column>
            </el-table>
          </template>

          <!-- 文本 -->
          <template v-else-if="active.text">
            <div class="text-head">
              <span>共 <b>{{ active.text.answeredCount }}</b> 条作答</span>
              <el-tag v-if="active.text.truncated" type="warning" size="small">
                仅统计前 200 条，完整内容请导出
              </el-tag>
            </div>

            <el-row v-if="active.text.words.length" :gutter="16">
              <el-col :span="24">
                <div class="sub-title">高频词（按出现次数）</div>
                <BaseChart
                  :option="wordOption(active.text.words)"
                  height="240px"
                />
                <div class="word-tags">
                  <el-tag
                    v-for="w in active.text.words"
                    :key="w.word"
                    size="small"
                    type="info"
                    class="word-tag"
                  >
                    {{ w.word }} · {{ w.count }}
                  </el-tag>
                </div>
                <p class="foot-note">
                  词频用两字滑窗切分统计，只保留出现 ≥ 2 次的词，已过滤常见虚词；
                  用于快速看倾向，不替代人工阅读。
                </p>
              </el-col>
            </el-row>

            <div class="sub-title">作答内容</div>
            <el-empty
              v-if="!active.text.answers.length"
              :image-size="60"
              description="暂无作答"
            />
            <template v-else>
              <div
                v-for="(a, i) in visibleAnswers(active)"
                :key="`${active.questionId}-${i}`"
                class="text-item"
              >
                <span class="text-index">{{ i + 1 }}</span>
                <span class="text-value">{{ a.text }}</span>
                <span v-if="a.submitTime" class="text-time">{{ a.submitTime }}</span>
              </div>
              <div v-if="active.text.answers.length > 20" class="text-more">
                <el-button link type="primary" @click="toggleText(active)">
                  {{ expandText[active.questionId] ? '收起' : `展开全部 ${active.text.answers.length} 条` }}
                </el-button>
              </div>
            </template>
            <p class="foot-note">
              统计页不展示填写人：匿名问卷不记身份，实名问卷的作答明细也在答卷详情里看。
            </p>
          </template>

          <el-empty v-else description="暂不支持该题型的统计" />
        </div>
      </div>
    </el-card>

    <el-empty v-else-if="!loading" description="这份问卷还没有题目" />
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.ml-6 {
  margin-left: 6px;
}

.ml-8 {
  margin-left: 8px;
}

.muted {
  color: #909399;
}

.overview {
  margin-bottom: 0;
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

.stat-time {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  line-height: 1.6;
}

.stat-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

.tip {
  margin-bottom: 16px;
}

.main-card {
  border-radius: 8px;
}

.stat-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.q-nav {
  display: flex;
  flex-direction: column;
  flex: 0 0 240px;
  gap: 6px;
  max-height: 72vh;
  overflow-y: auto;
  padding-right: 4px;
}

.q-nav-item {
  display: block;
  width: 100%;
  padding: 10px 12px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  transition: all 0.15s;
}

.q-nav-item:hover {
  border-color: #c6e2ff;
  background: #f4f9ff;
}

.q-nav-item.active {
  background: #ecf5ff;
  border-color: #2f7fd1;
}

.q-no {
  display: block;
  font-size: 12px;
  color: #909399;
}

.q-title {
  display: block;
  margin: 2px 0 4px;
  overflow: hidden;
  font-size: 13px;
  color: #303133;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.q-meta {
  font-size: 12px;
  color: #c0c4cc;
}

.q-body {
  flex: 1;
  min-width: 0;
}

.q-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.q-head-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.q-head-tags {
  display: flex;
  gap: 6px;
}

.q-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: #909399;
}

.detail-table {
  margin-top: 8px;
}

.ratio-cell {
  display: flex;
  gap: 8px;
  align-items: center;
}

.ratio-cell :deep(.el-progress) {
  flex: 1;
}

.ratio-text {
  flex: 0 0 52px;
  font-size: 12px;
  color: #606266;
  text-align: right;
}

.foot-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: #c0c4cc;
}

.rating-head {
  display: flex;
  gap: 24px;
  align-items: baseline;
  margin-bottom: 4px;
}

.rating-avg {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.rating-num {
  font-size: 40px;
  font-weight: 600;
  color: #e6a23c;
  line-height: 1;
}

.rating-unit {
  font-size: 14px;
  color: #909399;
}

.rating-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.text-head {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 14px;
  color: #606266;
}

.sub-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.word-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.word-tag {
  font-family: inherit;
}

.text-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 8px 10px;
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1.6;
  background: #fafafa;
  border-radius: 6px;
}

.text-index {
  flex: 0 0 22px;
  color: #c0c4cc;
  text-align: right;
}

.text-value {
  flex: 1;
  color: #303133;
  word-break: break-all;
  white-space: pre-wrap;
}

.text-time {
  flex: 0 0 auto;
  font-size: 12px;
  color: #c0c4cc;
}

.text-more {
  padding: 4px 0 0 32px;
}

@media (max-width: 768px) {
  .stat-layout {
    flex-direction: column;
  }

  .q-nav {
    flex: none;
    width: 100%;
    max-height: 200px;
  }
}
</style>
