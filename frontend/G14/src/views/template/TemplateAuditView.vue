<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { templateApi } from '@/api/template'
import {
  categoryLabel,
  questionTypeLabel,
  TEMPLATE_STATUS_OPTIONS,
  templateStatusLabel,
  templateStatusType,
} from '@/utils/dict'
import type { TemplateVO } from '@/types/template'

const router = useRouter()

const query = reactive({
  keyword: '',
  status: 'PENDING',
  page: 1,
  size: 10,
})

const loading = ref(false)
const records = ref<TemplateVO[]>([])
const total = ref(0)

/** 驳回对话框：意见必填，学生/老师都能在「我共享的模板」里看到这句话 */
const rejectVisible = ref(false)
const rejectTarget = ref<TemplateVO | null>(null)
const rejectRemark = ref('')
const rejecting = ref(false)

const previewVisible = ref(false)
const previewLoading = ref(false)
const preview = ref<TemplateVO | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await templateApi.page({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      order: 'latest',
      scope: 'audit',
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

async function approve(row: TemplateVO) {
  try {
    await ElMessageBox.confirm(
      `通过后《${row.title}》会立即出现在模板中心，所有学生和老师都能看到。继续吗？`,
      '审核通过',
      { type: 'info', confirmButtonText: '通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await templateApi.audit(row.id, { auditStatus: 'APPROVED' })
    ElMessage.success('已通过审核')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

function openReject(row: TemplateVO) {
  rejectTarget.value = row
  rejectRemark.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!rejectTarget.value) return
  if (!rejectRemark.value.trim()) {
    ElMessage.warning('驳回时必须填写审核意见，共享者会看到')
    return
  }
  rejecting.value = true
  try {
    await templateApi.audit(rejectTarget.value.id, {
      auditStatus: 'REJECTED',
      auditRemark: rejectRemark.value.trim(),
    })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    rejecting.value = false
  }
}

async function openPreview(row: TemplateVO) {
  previewVisible.value = true
  previewLoading.value = true
  preview.value = null
  try {
    preview.value = await templateApi.detail(row.id)
  } catch (e) {
    ElMessage.error((e as Error).message)
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">模板审核</h2>
        <p class="page-desc">通过后模板才会出现在模板中心；驳回必须写明原因</p>
      </div>
      <el-button @click="router.push({ name: 'templates' })">去模板中心</el-button>
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
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 140px" @change="resetAndLoad">
          <el-option
            v-for="s in TEMPLATE_STATUS_OPTIONS"
            :key="s.value"
            :label="s.label"
            :value="s.value"
          />
        </el-select>
        <el-button @click="resetAndLoad">查询</el-button>
        <el-tag v-if="query.status === 'PENDING'" type="warning" effect="plain">
          当前显示待审核，共 {{ total }} 个
        </el-tag>
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
        <el-table-column label="共享者" width="120">
          <template #default="{ row }">{{ row.createByName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="submitTime" label="提交时间" width="160" />
        <el-table-column label="审核意见" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openPreview(row)">查看内容</el-button>
            <el-button
              v-if="row.status === 'PENDING'"
              link
              type="success"
              size="small"
              @click="approve(row)"
            >
              通过
            </el-button>
            <el-button
              v-if="row.status === 'PENDING'"
              link
              type="danger"
              size="small"
              @click="openReject(row)"
            >
              驳回
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

    <!-- 驳回 -->
    <el-dialog v-model="rejectVisible" title="驳回模板" width="480px" :close-on-click-modal="false">
      <p class="reject-lead">
        模板《{{ rejectTarget?.title }}》将被驳回，共享者会看到下面这句话并据此修改后重新提交。
      </p>
      <el-input
        v-model="rejectRemark"
        type="textarea"
        :rows="4"
        maxlength="255"
        show-word-limit
        placeholder="例如：题目描述过于笼统，建议补充分类说明"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 查看内容 -->
    <el-drawer v-model="previewVisible" title="模板内容" size="560px">
      <div v-loading="previewLoading">
        <template v-if="preview">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="模板名称" :span="2">{{ preview.title }}</el-descriptions-item>
            <el-descriptions-item label="分类">{{ categoryLabel(preview.category) }}</el-descriptions-item>
            <el-descriptions-item label="题量">{{ preview.questionCount }} 题</el-descriptions-item>
            <el-descriptions-item label="匿名">{{ preview.anonymousDefault === 1 ? '是' : '否' }}</el-descriptions-item>
            <el-descriptions-item label="限填一次">{{ preview.limitOnceDefault === 1 ? '是' : '否' }}</el-descriptions-item>
            <el-descriptions-item label="说明" :span="2">
              {{ preview.description || '暂无说明' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">题目结构</el-divider>
          <div v-for="(q, i) in preview.structure" :key="i" class="q-item">
            <div class="q-title">
              <span class="q-no">{{ i + 1 }}.</span>
              <span class="q-text">{{ q.title }}</span>
              <el-tag v-if="q.isRequired === 1" size="small" type="danger" effect="plain">必答</el-tag>
              <el-tag size="small" type="info" effect="plain">
                {{ questionTypeLabel(q.questionType) }}
              </el-tag>
            </div>
            <ul v-if="q.options && q.options.length" class="q-options">
              <li v-for="(o, oi) in q.options" :key="oi">{{ o }}</li>
            </ul>
            <div v-if="q.questionType === 'RATING'" class="q-meta">
              评分上限：{{ q.ratingMax ?? 5 }} 星
            </div>
            <div
              v-else-if="q.questionType === 'TEXT' || q.questionType === 'TEXTAREA'"
              class="q-meta"
            >
              字数上限：{{ q.maxLength ?? '未限制' }}
            </div>
          </div>
          <el-empty
            v-if="!preview.structure || preview.structure.length === 0"
            description="该模板没有题目"
          />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.reject-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}

.q-item {
  padding: 12px 0;
  border-bottom: 1px solid #f0f2f5;
}

.q-title {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: baseline;
  font-size: 14px;
}

.q-no {
  font-weight: 600;
  color: #2f7fd1;
}

.q-text {
  flex: 1;
}

.q-options {
  margin: 6px 0 0;
  padding-left: 30px;
  font-size: 13px;
  line-height: 1.8;
  color: #606266;
}

.q-meta {
  margin: 6px 0 0 30px;
  font-size: 12px;
  color: #a8abb2;
}
</style>
