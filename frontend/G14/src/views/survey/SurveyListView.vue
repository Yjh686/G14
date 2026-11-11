<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { surveyApi } from '@/api/survey'
import { useAuthStore } from '@/stores/auth'
import { categoryLabel, SURVEY_CATEGORIES, SURVEY_STATUS_OPTIONS, surveyStatusLabel, surveyStatusType } from '@/utils/dict'
import type { SurveyVO } from '@/types/survey'

const router = useRouter()
const auth = useAuthStore()

/** 只有具备 list:all 的账号才显示「全部问卷」开关 */
const canSeeAll = computed(() => auth.hasPerm('survey:list:all'))
const canCreate = computed(() => auth.hasPerm('survey:create'))

const query = reactive({
  keyword: '',
  status: '',
  category: '',
  scope: 'mine' as 'mine' | 'all',
  page: 1,
  size: 10,
})

const loading = ref(false)
const records = ref<SurveyVO[]>([])
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await surveyApi.page({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      category: query.category || undefined,
      scope: query.scope,
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

function edit(row: SurveyVO) {
  router.push({ name: 'survey-edit', params: { id: row.id } })
}

function viewAnswers(row: SurveyVO) {
  router.push({ name: 'survey-answers', params: { id: row.id } })
}

function viewStats(row: SurveyVO) {
  router.push({ name: 'survey-stats', params: { id: row.id } })
}

async function createSurvey() {
  router.push({ name: 'survey-create' })
}

async function copySurvey(row: SurveyVO) {
  try {
    const copy = await surveyApi.copy(row.id)
    ElMessage.success('已复制为新草稿')
    router.push({ name: 'survey-edit', params: { id: copy.id } })
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function publish(row: SurveyVO) {
  if (row.questionCount === 0) {
    ElMessage.warning('问卷至少需要 1 道题才能发布')
    return
  }
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
    await surveyApi.publish(row.id)
    ElMessage.success('问卷已发布')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function pause(row: SurveyVO) {
  try {
    await surveyApi.pause(row.id)
    ElMessage.success('已暂停回收')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function resume(row: SurveyVO) {
  try {
    await surveyApi.resume(row.id)
    ElMessage.success('已恢复回收')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function close(row: SurveyVO) {
  try {
    await ElMessageBox.confirm(
      '结束后无法再恢复回收，只能延长截止时间。确定结束吗？',
      '结束确认',
      { type: 'warning', confirmButtonText: '结束', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await surveyApi.close(row.id)
    ElMessage.success('问卷已结束')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(row: SurveyVO) {
  const hasAnswers = row.answerCount > 0
  try {
    await ElMessageBox.confirm(
      hasAnswers
        ? `该问卷已回收 ${row.answerCount} 份答卷，删除后数据仍保留但列表不可见。确定删除吗？`
        : '删除后不可恢复，确定删除吗？',
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await surveyApi.remove(row.id, hasAnswers)
    ElMessage.success('问卷已删除')
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
        <h2 class="page-title">我的问卷</h2>
        <p class="page-desc">管理你创建的问卷：编辑题目、发布回收、查看答卷</p>
      </div>
      <el-button v-if="canCreate" type="primary" @click="createSurvey">新建问卷</el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索问卷标题"
          clearable
          style="width: 220px"
          @keyup.enter="resetAndLoad"
          @clear="resetAndLoad"
        />
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 130px"
          @change="resetAndLoad"
        >
          <el-option
            v-for="o in SURVEY_STATUS_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-select
          v-model="query.category"
          placeholder="全部分类"
          clearable
          style="width: 130px"
          @change="resetAndLoad"
        >
          <el-option
            v-for="o in SURVEY_CATEGORIES"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-radio-group v-if="canSeeAll" v-model="query.scope" @change="resetAndLoad">
          <el-radio-button value="mine">我的</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
        <el-button @click="resetAndLoad">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="records" stripe empty-text="还没有问卷">
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="title-cell">{{ row.title }}</span>
            <el-tag v-if="row.anonymous === 1" size="small" type="info" class="ml-6">匿名</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="categoryLabel" label="分类" width="100">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="surveyStatusType(row.status)" size="small">
              {{ surveyStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="questionCount" label="题量" width="70" align="center" />
        <el-table-column prop="answerCount" label="有效答卷" width="90" align="center" />
        <el-table-column v-if="query.scope === 'all'" prop="createByName" label="创建人" width="100">
          <template #default="{ row }">{{ row.createByName || '-' }}</template>
        </el-table-column>
        <el-table-column label="分享码" width="110">
          <template #default="{ row }">
            <span v-if="row.shareCode" class="code-cell">{{ row.shareCode }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="edit(row)">
              {{ row.status === 'DRAFT' ? '编辑' : '查看' }}
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              link
              type="success"
              size="small"
              @click="publish(row)"
            >
              发布
            </el-button>
            <el-button
              v-if="row.status === 'PUBLISHED'"
              link
              type="warning"
              size="small"
              @click="pause(row)"
            >
              暂停
            </el-button>
            <el-button
              v-if="row.status === 'PAUSED'"
              link
              type="success"
              size="small"
              @click="resume(row)"
            >
              恢复
            </el-button>
            <el-button
              v-if="row.status === 'PUBLISHED' || row.status === 'PAUSED'"
              link
              type="danger"
              size="small"
              @click="close(row)"
            >
              结束
            </el-button>
            <el-dropdown trigger="click" @command="(cmd: string) => {
              if (cmd === 'answers') viewAnswers(row)
              if (cmd === 'stats') viewStats(row)
              if (cmd === 'copy') copySurvey(row)
              if (cmd === 'delete') remove(row)
            }">
              <el-button link type="primary" size="small">更多</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-if="row.status !== 'DRAFT'"
                    command="answers"
                  >
                    查看答卷
                  </el-dropdown-item>
                  <el-dropdown-item command="stats">统计分析</el-dropdown-item>
                  <el-dropdown-item command="copy">复制为新草稿</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
          @current-change="load"
          @size-change="resetAndLoad"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.title-cell {
  font-weight: 500;
}

.code-cell {
  font-family: Consolas, Monaco, monospace;
  letter-spacing: 1px;
  color: #2f7fd1;
}

.muted {
  color: #c0c4cc;
}

.ml-6 {
  margin-left: 6px;
}
</style>
