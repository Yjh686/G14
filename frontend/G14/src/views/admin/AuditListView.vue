<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '@/api/user'
import { ApiError } from '@/api/http'
import type { UserVO } from '@/types/api'
import { auditStatusLabel, auditStatusType, roleLabel } from '@/utils/dict'

const loading = ref(false)
const rows = ref<UserVO[]>([])
const total = ref(0)

const query = reactive({
  auditStatus: 0 as number | undefined,
  page: 1,
  size: 10,
})

const auditDialogVisible = ref(false)
const auditing = ref<UserVO | null>(null)
const auditRemark = ref('')
const auditSubmitting = ref(false)
/** 'approve' | 'reject' */
const auditAction = ref<'approve' | 'reject'>('approve')

async function load() {
  loading.value = true
  try {
    const res = await userApi.page({
      auditStatus: query.auditStatus,
      page: query.page,
      size: query.size,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openAudit(row: UserVO, action: 'approve' | 'reject') {
  auditing.value = row
  auditAction.value = action
  auditRemark.value = ''
  auditDialogVisible.value = true
}

async function submitAudit() {
  if (!auditing.value) return
  if (auditAction.value === 'reject' && !auditRemark.value.trim()) {
    ElMessage.warning('驳回时必须填写审核意见，用户会看到这段说明')
    return
  }

  auditSubmitting.value = true
  try {
    await userApi.audit(auditing.value.id, {
      auditStatus: auditAction.value === 'approve' ? 1 : 2,
      auditRemark: auditRemark.value.trim() || undefined,
    })
    ElMessage.success(auditAction.value === 'approve' ? '已通过审核' : '已驳回申请')
    auditDialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    auditSubmitting.value = false
  }
}

async function batchApprove() {
  const pending = rows.value.filter((r) => r.auditStatus === 0)
  if (pending.length === 0) {
    ElMessage.info('当前页没有待审核的申请')
    return
  }
  try {
    await ElMessageBox.confirm(`确定要一次性通过当前页 ${pending.length} 个申请吗？`, '批量通过', {
      type: 'warning',
    })
  } catch {
    return
  }
  let ok = 0
  for (const r of pending) {
    try {
      await userApi.audit(r.id, { auditStatus: 1 })
      ok += 1
    } catch {
      // 单条失败不影响其它，最后统一提示
    }
  }
  ElMessage.success(`已通过 ${ok} 个申请`)
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">注册审核</h2>
        <p class="page-desc">自助注册的账号必须审核通过后才能登录</p>
      </div>
      <el-button v-if="query.auditStatus === 0" type="primary" icon="Select" @click="batchApprove">
        批量通过本页
      </el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-radio-group v-model="query.auditStatus" @change="((query.page = 1), load())">
          <el-radio-button :value="0">待审核</el-radio-button>
          <el-radio-button :value="2">已驳回</el-radio-button>
          <el-radio-button :value="1">已通过</el-radio-button>
          <el-radio-button :value="undefined">全部</el-radio-button>
        </el-radio-group>
        <el-button icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" stripe border>
        <el-table-column prop="username" label="学号/工号" width="140" />
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column label="申请身份" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ roleLabel(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="department" label="院系/班级" min-width="160">
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="130">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="申请时间" width="170" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tooltip :disabled="!row.auditRemark" :content="row.auditRemark || ''" placement="top">
              <el-tag size="small" :type="auditStatusType(row.auditStatus)">
                {{ auditStatusLabel(row.auditStatus) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="row.auditStatus === 0">
              <el-button link type="success" @click="openAudit(row, 'approve')">通过</el-button>
              <el-button link type="danger" @click="openAudit(row, 'reject')">驳回</el-button>
            </template>
            <span v-else class="text-muted">已处理</span>
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
          background
          @current-change="load"
          @size-change="((query.page = 1), load())"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="auditDialogVisible"
      :title="auditAction === 'approve' ? '通过审核' : '驳回申请'"
      width="520px"
    >
      <el-descriptions v-if="auditing" :column="1" border>
        <el-descriptions-item label="学号/工号">{{ auditing.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ auditing.realName }}</el-descriptions-item>
        <el-descriptions-item label="申请身份">
          {{ roleLabel(auditing.roleCode) }}
        </el-descriptions-item>
        <el-descriptions-item label="院系/班级">
          {{ auditing.department || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-form label-position="top" class="audit-form">
        <el-form-item
          :label="auditAction === 'reject' ? '驳回原因（必填，用户登录时可见）' : '审核备注（选填）'"
        >
          <el-input
            v-model="auditRemark"
            type="textarea"
            :rows="3"
            :placeholder="
              auditAction === 'reject'
                ? '例如：学号不在本届名册内，请核对后重新注册'
                : '可留空'
            "
            maxlength="255"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="auditDialogVisible = false">取消</el-button>
        <el-button
          :type="auditAction === 'approve' ? 'success' : 'danger'"
          :loading="auditSubmitting"
          @click="submitAudit"
        >
          确认{{ auditAction === 'approve' ? '通过' : '驳回' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.audit-form {
  margin-top: 16px;
}

.text-muted {
  font-size: 13px;
  color: #909399;
}
</style>
