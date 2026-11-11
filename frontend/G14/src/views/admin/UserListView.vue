<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '@/api/user'
import { ApiError } from '@/api/http'
import type { RoleCode, UserVO } from '@/types/api'
import {
  ALL_ROLES,
  AUDIT_STATUS_OPTIONS,
  auditStatusLabel,
  auditStatusType,
  roleLabel,
  statusLabel,
  statusType,
} from '@/utils/dict'
import UserFormDialog from '@/components/UserFormDialog.vue'

const loading = ref(false)
const rows = ref<UserVO[]>([])
const total = ref(0)

const query = reactive({
  keyword: '',
  status: undefined as number | undefined,
  auditStatus: undefined as number | undefined,
  roleCode: undefined as RoleCode | undefined,
  page: 1,
  size: 10,
})

const dialogVisible = ref(false)
const editing = ref<UserVO | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await userApi.page({
      keyword: query.keyword.trim() || undefined,
      status: query.status,
      auditStatus: query.auditStatus,
      roleCode: query.roleCode,
      page: query.page,
      size: query.size,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

function handleReset() {
  query.keyword = ''
  query.status = undefined
  query.auditStatus = undefined
  query.roleCode = undefined
  query.page = 1
  load()
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: UserVO) {
  editing.value = row
  dialogVisible.value = true
}

async function toggleStatus(row: UserVO) {
  const next = row.status === 1 ? 0 : 1
  const actionText = next === 1 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      next === 1
        ? `确定要启用「${row.realName}」的账号吗？`
        : `确定要禁用「${row.realName}」的账号吗？禁用后该用户会立即被强制下线。`,
      `${actionText}账号`,
      { type: 'warning', confirmButtonText: actionText, cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await userApi.changeStatus(row.id, next)
    ElMessage.success(`已${actionText}`)
    load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : `${actionText}失败`)
  }
}

async function resetPassword(row: UserVO) {
  try {
    await ElMessageBox.confirm(
      `确定要重置「${row.realName}」的密码吗？重置后会强制该用户下线，下次登录必须修改密码。`,
      '重置密码',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const pwd = await userApi.resetPassword(row.id)
    await ElMessageBox.alert(
      `该用户的密码已重置为：${pwd}\n请线下告知本人，并提醒其首次登录后立即修改。`,
      '重置成功',
      { confirmButtonText: '知道了', type: 'success' },
    )
    load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '重置密码失败')
  }
}

async function removeUser(row: UserVO) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${row.realName}（${row.username}）」吗？删除后该账号将无法登录。`,
      '删除用户',
      { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await userApi.remove(row.id)
    ElMessage.success('用户已删除')
    if (rows.value.length === 1 && query.page > 1) query.page -= 1
    load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-desc">维护学生、教师与管理员账号，共 {{ total }} 个账号</p>
      </div>
      <el-button type="primary" icon="Plus" @click="openCreate">新建用户</el-button>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索学号/工号或姓名"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.roleCode"
          placeholder="全部角色"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option v-for="r in ALL_ROLES" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 130px"
          @change="handleSearch"
        >
          <el-option label="正常" :value="1" />
          <el-option label="已禁用" :value="0" />
        </el-select>
        <el-select
          v-model="query.auditStatus"
          placeholder="全部审核状态"
          clearable
          style="width: 150px"
          @change="handleSearch"
        >
          <el-option
            v-for="o in AUDIT_STATUS_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
        <el-button icon="RefreshLeft" @click="handleReset">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" stripe border>
        <el-table-column prop="username" label="学号/工号" width="130" />
        <el-table-column prop="realName" label="姓名" width="110" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ roleLabel(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="department" label="院系/班级" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核" width="90" align="center">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!row.auditRemark"
              :content="row.auditRemark || ''"
              placement="top"
            >
              <el-tag size="small" :type="auditStatusType(row.auditStatus)">
                {{ auditStatusLabel(row.auditStatus) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="首次改密" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.mustChangePassword === 1" size="small" type="warning">待改密</el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="170">
          <template #default="{ row }">
            <span class="text-muted">{{ row.lastLoginTime || '从未登录' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="removeUser(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="handleSearch"
        />
      </div>
    </el-card>

    <UserFormDialog v-model="dialogVisible" :user="editing" @saved="load" />
  </div>
</template>

<style scoped>
.text-muted {
  font-size: 13px;
  color: #909399;
}
</style>
