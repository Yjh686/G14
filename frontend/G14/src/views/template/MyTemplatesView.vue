<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ShareTemplateDialog from '@/components/ShareTemplateDialog.vue'
import { templateApi } from '@/api/template'
import { useAuthStore } from '@/stores/auth'
import {
  categoryLabel,
  ratingText,
  TEMPLATE_STATUS_OPTIONS,
  templateStatusLabel,
  templateStatusType,
} from '@/utils/dict'
import type { TemplateVO } from '@/types/template'

const router = useRouter()
const auth = useAuthStore()

const canShare = computed(() => auth.hasPerm('template:share'))
const canOffline = computed(() => auth.hasPerm('template:offline'))

const query = reactive({
  keyword: '',
  status: '',
  order: 'latest',
  page: 1,
  size: 10,
})

const loading = ref(false)
const records = ref<TemplateVO[]>([])
const total = ref(0)

const shareVisible = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await templateApi.page({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      order: query.order,
      scope: 'mine',
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

async function resubmit(row: TemplateVO) {
  try {
    await ElMessageBox.confirm(
      '重新提交后模板会回到「待审核」，需要管理员重新审批。继续吗？',
      '重新提交审核',
      { type: 'warning', confirmButtonText: '重新提交', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await templateApi.resubmit(row.id)
    ElMessage.success('已重新提交审核')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function offline(row: TemplateVO) {
  try {
    await ElMessageBox.confirm(
      `下架后《${row.title}》将从模板中心消失，已被别人使用的问卷不受影响。继续吗？`,
      '下架模板',
      { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await templateApi.offline(row.id)
    ElMessage.success('模板已下架')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function remove(row: TemplateVO) {
  try {
    await ElMessageBox.confirm(`确定删除模板《${row.title}》吗？删除后不可恢复。`, '删除模板', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await templateApi.remove(row.id)
    ElMessage.success('模板已删除')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function onShared() {
  shareVisible.value = false
  query.page = 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">我共享的模板</h2>
        <p class="page-desc">共享后需要管理员审核通过，才会出现在模板中心</p>
      </div>
      <div class="header-actions">
        <el-button @click="router.push({ name: 'templates' })">去模板中心</el-button>
        <el-button v-if="canShare" type="primary" @click="shareVisible = true">
          共享为模板
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
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @change="resetAndLoad"
        >
          <el-option
            v-for="s in TEMPLATE_STATUS_OPTIONS"
            :key="s.value"
            :label="s.label"
            :value="s.value"
          />
        </el-select>
        <el-button @click="resetAndLoad">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="records" border>
        <el-table-column prop="title" label="模板名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="分类" width="100">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="templateStatusType(row.status)" size="small">
              {{ templateStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="questionCount" label="题量" width="70" align="center" />
        <el-table-column label="评分" width="150">
          <template #default="{ row }">{{ ratingText(row.ratingAvg, row.ratingCount) }}</template>
        </el-table-column>
        <el-table-column prop="useCount" label="被使用" width="80" align="center" />
        <el-table-column prop="submitTime" label="提交时间" width="160" />
        <el-table-column label="审核意见" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'reject-text': row.status === 'REJECTED' }">
              {{ row.auditRemark || '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="router.push({ name: 'template-detail', params: { id: row.id } })"
            >
              查看
            </el-button>
            <el-button
              v-if="row.status === 'REJECTED'"
              link
              type="warning"
              size="small"
              @click="resubmit(row as TemplateVO)"
            >
              重新提交
            </el-button>
            <el-button
              v-if="row.status === 'APPROVED' && canOffline"
              link
              type="warning"
              size="small"
              @click="offline(row as TemplateVO)"
            >
              下架
            </el-button>
            <el-button
              v-if="row.status === 'REJECTED' || row.status === 'OFFLINE'"
              link
              type="danger"
              size="small"
              @click="remove(row as TemplateVO)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

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

    <ShareTemplateDialog v-model="shareVisible" @shared="onShared" />
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.reject-text {
  color: #f56c6c;
}
</style>
