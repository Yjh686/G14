<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hallApi } from '@/api/survey'
import {
  categoryLabel,
  myStatusLabel,
  myStatusType,
  SURVEY_CATEGORIES,
} from '@/utils/dict'
import type { HallSurveyVO } from '@/types/survey'

const router = useRouter()

const query = reactive({ keyword: '', category: '', page: 1, size: 9 })
const loading = ref(false)
const records = ref<HallSurveyVO[]>([])
const total = ref(0)

/** 分享码直达：学生拿到一串 8 位码就能进卷，不用在列表里翻 */
const shareCode = ref('')

async function load() {
  loading.value = true
  try {
    const res = await hallApi.hall({
      keyword: query.keyword || undefined,
      category: query.category || undefined,
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

/**
 * 跳转用分享码而不是 id：填写页的取卷接口 /surveys/share/{code}
 * 只要求 survey:fill，而 /surveys/{id} 要求 update/preview 权限，
 * 学生没有后者，用 id 跳会 403。
 * 大厅只列已发布问卷，所以 shareCode 一定有值。
 */
function open(row: HallSurveyVO) {
  const key = row.shareCode ?? String(row.id)
  router.push({ name: 'survey-fill', params: { key } })
}

function openByCode() {
  const code = shareCode.value.trim().toUpperCase()
  if (!code) {
    ElMessage.warning('请输入分享码')
    return
  }
  router.push({ name: 'survey-fill', params: { key: code } })
}

/** 截止时间倒计时文案 */
function deadlineText(row: HallSurveyVO): string {
  if (!row.endTime) return '长期有效'
  const end = new Date(row.endTime.replace(' ', 'T')).getTime()
  const left = end - Date.now()
  if (left <= 0) return '已截止'
  const days = Math.floor(left / 86400000)
  if (days >= 1) return `剩 ${days} 天`
  const hours = Math.floor(left / 3600000)
  if (hours >= 1) return `剩 ${hours} 小时`
  return `剩 ${Math.max(1, Math.floor(left / 60000))} 分钟`
}

/** 卡片按钮文案完全由 myStatus 与 fillable 决定 */
function actionText(row: HallSurveyVO): string {
  if (row.myStatus === 'SUBMITTED') return row.limitOnce === 1 ? '已提交' : '再次填写'
  if (row.myStatus === 'DRAFT') return '继续填写'
  return row.fillable ? '开始填写' : '暂不可填'
}

function actionDisabled(row: HallSurveyVO): boolean {
  if (row.myStatus === 'SUBMITTED' && row.limitOnce === 1) return true
  return !row.fillable && row.myStatus !== 'DRAFT'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">填写问卷</h2>
        <p class="page-desc">当前可以填写的问卷；草稿会自动保存，可以随时继续</p>
      </div>
      <div class="code-box">
        <el-input
          v-model="shareCode"
          placeholder="输入分享码"
          maxlength="16"
          style="width: 150px"
          @keyup.enter="openByCode"
        />
        <el-button type="primary" @click="openByCode">进入</el-button>
      </div>
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
        <el-button @click="resetAndLoad">查询</el-button>
      </div>

      <el-empty v-if="!loading && records.length === 0" description="暂时没有可填写的问卷" />

      <el-row v-else v-loading="loading" :gutter="16">
        <el-col v-for="row in records" :key="row.id" :xs="24" :sm="12" :md="8">
          <el-card shadow="hover" class="survey-card">
            <div class="card-top">
              <el-tag size="small" type="info">{{ categoryLabel(row.category) }}</el-tag>
              <el-tag v-if="row.anonymous === 1" size="small" effect="plain">匿名</el-tag>
              <el-tag
                :type="myStatusType(row.myStatus)"
                size="small"
                class="push-right"
              >
                {{ myStatusLabel(row.myStatus) }}
              </el-tag>
            </div>

            <h3 class="card-title" :title="row.title">{{ row.title }}</h3>
            <p class="card-desc">{{ row.description || '暂无简介' }}</p>

            <div class="card-meta">
              <span>{{ row.questionCount }} 题</span>
              <span class="dot">·</span>
              <span>{{ deadlineText(row) }}</span>
              <span class="dot">·</span>
              <span>{{ row.createByName || '未知发布者' }}</span>
            </div>

            <el-button
              type="primary"
              class="w-full"
              :disabled="actionDisabled(row)"
              @click="open(row)"
            >
              {{ actionText(row) }}
            </el-button>
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
.code-box {
  display: flex;
  gap: 8px;
}

.survey-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.card-top {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 10px;
}

.push-right {
  margin-left: auto;
}

.card-title {
  margin: 0 0 8px;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-desc {
  height: 40px;
  margin: 0 0 12px;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.55;
  color: #909399;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
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
</style>
