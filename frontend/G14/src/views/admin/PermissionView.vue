<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { rbacApi, userApi } from '@/api/user'
import { ApiError } from '@/api/http'
import type { PermissionVO, RoleCode, UserVO } from '@/types/api'
import { ALL_ROLES, roleLabel } from '@/utils/dict'

const loading = ref(false)
const rows = ref<UserVO[]>([])
const total = ref(0)

const query = reactive({
  keyword: '',
  roleCode: undefined as RoleCode | undefined,
  page: 1,
  size: 10,
})

/** 权限码 → 权限详情，用于展示中文名 */
const permMap = ref<Record<string, PermissionVO>>({})
/** 按模块分组的权限 */
const groupedPerms = ref<Record<string, PermissionVO[]>>({})

const drawerVisible = ref(false)
const currentUser = ref<UserVO | null>(null)
const effectivePerms = ref<string[]>([])
const extraPerms = ref<string[]>([])
const drawerLoading = ref(false)
const savingCode = ref('')

/** 角色自带（不可撤销）的权限 = 生效权限 - 追加权限 */
const rolePermSet = computed(() => {
  const extra = new Set(extraPerms.value)
  return new Set(effectivePerms.value.filter((p) => !extra.has(p)))
})

function isRolePerm(code: string): boolean {
  return rolePermSet.value.has(code)
}

function isExtraPerm(code: string): boolean {
  return extraPerms.value.includes(code)
}

async function load() {
  loading.value = true
  try {
    const res = await userApi.page({
      keyword: query.keyword.trim() || undefined,
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

async function loadPermDict() {
  try {
    const grouped = await rbacApi.permissions()
    groupedPerms.value = grouped
    const map: Record<string, PermissionVO> = {}
    for (const list of Object.values(grouped)) {
      for (const p of list) map[p.code] = p
    }
    permMap.value = map
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载权限字典失败')
  }
}

async function openDrawer(row: UserVO) {
  currentUser.value = row
  drawerVisible.value = true
  await refreshPerms()
}

async function refreshPerms() {
  if (!currentUser.value) return
  drawerLoading.value = true
  try {
    const [eff, extra] = await Promise.all([
      rbacApi.userPermissions(currentUser.value.id),
      rbacApi.userExtraPermissions(currentUser.value.id),
    ])
    effectivePerms.value = eff
    extraPerms.value = extra
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载权限失败')
  } finally {
    drawerLoading.value = false
  }
}

async function togglePerm(code: string, checked: boolean) {
  if (!currentUser.value) return
  savingCode.value = code
  try {
    if (checked) {
      await rbacApi.grant(currentUser.value.id, code)
      ElMessage.success(`已追加权限：${permMap.value[code]?.name ?? code}`)
    } else {
      await rbacApi.revoke(currentUser.value.id, code)
      ElMessage.success(`已撤销权限：${permMap.value[code]?.name ?? code}`)
    }
    await refreshPerms()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '操作失败')
    await refreshPerms()
  } finally {
    savingCode.value = ''
  }
}

function handleSearch() {
  query.page = 1
  load()
}

onMounted(() => {
  load()
  loadPermDict()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">权限配置</h2>
        <p class="page-desc">
          角色权限由角色统一分配，这里可以为个别账号追加额外权限（如学生干部发问卷）
        </p>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索学号/工号或姓名"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
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
        <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" stripe border>
        <el-table-column prop="username" label="学号/工号" width="140" />
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag size="small">{{ roleLabel(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="department" label="院系/班级" min-width="180">
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button link type="primary" icon="Key" @click="openDrawer(row)">
              配置权限
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </el-card>

    <el-drawer v-model="drawerVisible" size="620px" :title="`权限配置 - ${currentUser?.realName ?? ''}`">
      <div v-loading="drawerLoading">
        <el-alert type="info" :closable="false" show-icon class="drawer-tip">
          <template #title>
            生效权限 = 角色权限 ∪ 追加权限。带
            <el-tag size="small" type="info" class="inline-tag">角色</el-tag>
            标记的权限来自角色，不能在此撤销；勾选/取消勾选只影响追加部分。
          </template>
        </el-alert>

        <el-descriptions :column="2" border size="small" class="drawer-desc">
          <el-descriptions-item label="账号">{{ currentUser?.username }}</el-descriptions-item>
          <el-descriptions-item label="角色">
            {{ roleLabel(currentUser?.roleCode) }}
          </el-descriptions-item>
          <el-descriptions-item label="生效权限">
            {{ effectivePerms.length }} 项
          </el-descriptions-item>
          <el-descriptions-item label="追加权限">
            {{ extraPerms.length }} 项
          </el-descriptions-item>
        </el-descriptions>

        <div v-for="(list, module) in groupedPerms" :key="module" class="perm-group">
          <div class="perm-group-title">{{ module }}</div>
          <div class="perm-items">
            <div v-for="p in list" :key="p.code" class="perm-item">
              <el-checkbox
                :model-value="isRolePerm(p.code) || isExtraPerm(p.code)"
                :disabled="isRolePerm(p.code) || savingCode === p.code"
                @change="(v: boolean) => togglePerm(p.code, v)"
              >
                {{ p.name }}
              </el-checkbox>
              <el-tag v-if="isRolePerm(p.code)" size="small" type="info">角色</el-tag>
              <el-tag v-else-if="isExtraPerm(p.code)" size="small" type="success">追加</el-tag>
              <span class="perm-code">{{ p.code }}</span>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.drawer-tip {
  margin-bottom: 16px;
}

.drawer-desc {
  margin-bottom: 16px;
}

.inline-tag {
  margin: 0 2px;
}

.perm-group {
  margin-bottom: 20px;
}

.perm-group-title {
  padding-bottom: 6px;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid #ebeef5;
}

.perm-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.perm-item {
  display: flex;
  gap: 8px;
  align-items: center;
}

.perm-code {
  margin-left: auto;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
