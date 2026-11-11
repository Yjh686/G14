<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { templateApi } from '@/api/template'
import { useAuthStore } from '@/stores/auth'
import {
  categoryLabel,
  ratingText,
  SURVEY_CATEGORIES,
  TEMPLATE_ORDERS,
} from '@/utils/dict'
import type { TemplateVO } from '@/types/template'

const router = useRouter()
const auth = useAuthStore()

/** 只有持有 template:use 的账号（教师/管理员）才显示「使用模板」 */
const canUse = computed(() => auth.hasPerm('template:use'))
/** 只有学生能评分 */
const canRate = computed(() => auth.hasPerm('template:rate'))
const canShare = computed(() => auth.hasPerm('template:share'))

const query = reactive({
  keyword: '',
  category: '',
  order: 'rating',
  page: 1,
  size: 9,
})

const loading = ref(false)
const records = ref<TemplateVO[]>([])
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await templateApi.page({
      keyword: query.keyword || undefined,
      category: query.category || undefined,
      order: query.order,
      scope: 'browse',
      page: query.page,
      size: query.size,
    })
    records.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

function resetAndLoad() {
  query.page = 1
  load()
}

function openDetail(row: TemplateVO) {
  router.push({ name: 'template-detail', params: { id: row.id } })
}

/**
 * 使用模板：后端会复制成当前账号自己的新问卷草稿。
 * 成功直接跳到编辑器，让用户能立刻改名再发布 ——
 * 停在模板中心等用户自己去找「我的问卷」，会让人以为没成功。
 */
async function useTemplate(row: TemplateVO) {
  try {
    await ElMessageBox.confirm(
      `将根据《${row.title}》创建一份属于你的新问卷草稿，共 ${row.questionCount} 道题。继续吗？`,
      '使用模板',
      { type: 'info', confirmButtonText: '创建草稿', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const survey = await templateApi.use(row.id)
    ElMessage.success('已创建问卷草稿，可以继续修改后再发布')
    router.push({ name: 'survey-edit', params: { id: survey.id } })
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function rate(row: TemplateVO, score: number) {
  // el-rate 再点一次已选中的星会归零，而后端只接受 1~5，直接忽略
  if (!score) return
  try {
    await templateApi.rate(row.id, score)
    ElMessage.success('评分已提交')
    row.myScore = score
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">模板中心</h2>
        <p class="page-desc">来自其他老师的问卷模板，可直接复制成自己的问卷</p>
      </div>
      <div class="header-actions">
        <el-button v-if="canShare" @click="router.push({ name: 'my-templates' })">
          我共享的模板
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索模板名称"
          clearable
          style="width: 220px"
          @keyup.enter="resetAndLoad"
          @clear="resetAndLoad"
        />
        <el-select
          v-model="query.category"
          placeholder="全部分类"
          clearable
          style="width: 140px"
          @change="resetAndLoad"
        >
          <el-option
            v-for="c in SURVEY_CATEGORIES"
            :key="c.value"
            :label="c.label"
            :value="c.value"
          />
        </el-select>
        <el-select v-model="query.order" style="width: 130px" @change="resetAndLoad">
          <el-option v-for="o in TEMPLATE_ORDERS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button @click="resetAndLoad">查询</el-button>
      </div>

      <el-empty
        v-if="!loading && records.length === 0"
        description="模板中心还没有已通过审核的模板"
      />

      <el-row v-else v-loading="loading" :gutter="16">
        <el-col v-for="row in records" :key="row.id" :xs="24" :sm="12" :md="8">
          <el-card shadow="hover" class="tpl-card">
            <div class="card-top">
              <el-tag size="small" type="info">{{ categoryLabel(row.category) }}</el-tag>
              <el-tag v-if="row.anonymousDefault === 1" size="small" effect="plain">匿名</el-tag>
            </div>

            <h3 class="card-title" :title="row.title" @click="openDetail(row)">
              {{ row.title }}
            </h3>
            <p class="card-desc">{{ row.description || '暂无说明' }}</p>

            <div class="card-rating">
              <!-- 交互态不能开 allow-half：后端评分是 1~5 的整数，4.5 会被拒 -->
              <el-rate
                v-if="canRate"
                :model-value="row.myScore ?? 0"
                :max="5"
                @change="(v: number) => rate(row, v)"
              />
              <el-rate v-else :model-value="row.ratingAvg ?? 0" disabled allow-half :max="5" />
              <span class="rating-text">{{ ratingText(row.ratingAvg, row.ratingCount) }}</span>
            </div>

            <div class="card-meta">
              <span>{{ row.questionCount }} 题</span>
              <span class="dot">·</span>
              <span>被使用 {{ row.useCount }} 次</span>
              <span class="dot">·</span>
              <span>{{ row.createByName || '未知' }}</span>
            </div>

            <div class="card-actions">
              <el-button size="small" @click="openDetail(row)">查看结构</el-button>
              <el-button v-if="canUse" type="primary" size="small" @click="useTemplate(row)">
                使用模板
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.tpl-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.card-top {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 10px;
}

.card-title {
  margin: 0 0 8px;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.card-title:hover {
  color: #2f7fd1;
}

.card-desc {
  height: 40px;
  margin: 0 0 10px;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.55;
  color: #909399;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.card-rating {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}

.rating-text {
  font-size: 12px;
  color: #909399;
}

.card-meta {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 12px;
  color: #a8abb2;
}

.dot {
  color: #dcdfe6;
}

.card-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
