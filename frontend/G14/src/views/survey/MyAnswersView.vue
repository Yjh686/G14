<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hallApi } from '@/api/survey'
import { surveyStatusLabel, surveyStatusType } from '@/utils/dict'
import type { MyAnswerVO } from '@/types/survey'

const router = useRouter()

const query = reactive({ isValid: undefined as number | undefined, page: 1, size: 10 })
const loading = ref(false)
const records = ref<MyAnswerVO[]>([])
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await hallApi.myRecords({
      isValid: query.isValid,
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
 * 匿名卷 contentVisible=false，后端不返回作答内容，
 * 所以这里直接不提供「查看」入口，而不是点进去看到空页面。
 */
function viewDetail(row: MyAnswerVO) {
  router.push({ name: 'my-answer-detail', params: { sheetId: row.sheetId } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">我的填写记录</h2>
        <p class="page-desc">
          这里只显示你自己的提交记录；匿名问卷提交后不回显作答内容
        </p>
      </div>
      <el-button @click="router.push({ name: 'survey-hall' })">去填写问卷</el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-radio-group v-model="query.isValid" @change="resetAndLoad">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="1">有效</el-radio-button>
          <el-radio-button :value="0">已失效</el-radio-button>
        </el-radio-group>
      </div>

      <el-table v-loading="loading" :data="records" stripe empty-text="还没有提交过答卷">
        <el-table-column prop="surveyTitle" label="问卷" min-width="220" show-overflow-tooltip />
        <el-table-column label="问卷状态" width="110">
          <template #default="{ row }">
            <el-tag :type="surveyStatusType(row.surveyStatus)" size="small">
              {{ surveyStatusLabel(row.surveyStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submitTime" label="提交时间" width="170" />
        <el-table-column label="答卷状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.isValid === 1 ? 'success' : 'danger'" size="small">
              {{ row.isValid === 1 ? '有效' : '已失效' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              v-if="row.contentVisible"
              link
              type="primary"
              size="small"
              @click="viewDetail((row as MyAnswerVO))"
            >
              查看作答
            </el-button>
            <span v-else class="muted">匿名，不回显</span>
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
.muted {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
