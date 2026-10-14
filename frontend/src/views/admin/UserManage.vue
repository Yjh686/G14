<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminListUsers, adminCreateUser, adminResetPassword } from '../../api/auth'
import { ROLE, ROLE_LABEL, ROLE_TAG } from '../../utils/role'

const loading = ref(false)
const list = ref([])
const total = ref(0)

const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  role: ''
})

async function loadUsers() {
  loading.value = true
  try {
    const res = await adminListUsers(query)
    list.value = res.data.list || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadUsers()
}

function handleReset() {
  query.keyword = ''
  query.role = ''
  query.page = 1
  loadUsers()
}

onMounted(loadUsers)

// ---------- 开通账号 ----------
const createVisible = ref(false)
const createRef = ref()
const createLoading = ref(false)
const createForm = reactive({
  account: '',
  name: '',
  role: ROLE.STUDENT,
  password: '',
  phone: ''
})

const createRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [{ required: true, message: '请输入初始密码', trigger: 'blur' }]
}

function openCreate() {
  Object.assign(createForm, { account: '', name: '', role: ROLE.STUDENT, password: '', phone: '' })
  createVisible.value = true
}

async function submitCreate() {
  createRef.value?.validate(async (valid) => {
    if (!valid) return
    createLoading.value = true
    try {
      await adminCreateUser(createForm)
      ElMessage.success('账号开通成功，首次登录需修改初始密码')
      createVisible.value = false
      loadUsers()
    } finally {
      createLoading.value = false
    }
  })
}

// ---------- 重置密码 ----------
const resetTarget = ref(null)
const resetVisible = ref(false)
const resetRef = ref()
const resetLoading = ref(false)
const resetForm = reactive({ newPassword: '' })

function openReset(row) {
  resetTarget.value = row
  resetForm.newPassword = ''
  resetVisible.value = true
}

async function submitReset() {
  resetRef.value?.validate(async (valid) => {
    if (!valid) return
    resetLoading.value = true
    try {
      await adminResetPassword(resetTarget.value.id, { newPassword: resetForm.newPassword })
      ElMessage.success('密码已重置，用户下次登录需修改密码')
      resetVisible.value = false
    } finally {
      resetLoading.value = false
    }
  })
}

// ---------- 删除提示占位（删除接口待定，仅示例交互） ----------
async function handleRemove(row) {
  try {
    await ElMessageBox.confirm(`确定删除账号「${row.name}」吗？`, '提示', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    ElMessage.info('删除接口待接入')
  } catch {
    // 取消
  }
}
</script>

<template>
  <el-card class="manage-card" shadow="never">
    <template #header>
      <div class="head">
        <div class="head-text">
          <h3 class="head-title">账号管理</h3>
          <span class="head-sub">开通账号、重置密码、查看系统用户</span>
        </div>
        <el-button type="primary" round @click="openCreate">＋ 开通账号</el-button>
      </div>
    </template>

    <!-- 筛选 -->
    <div class="filters">
      <el-input
        v-model="query.keyword"
        placeholder="搜索账号 / 姓名"
        clearable
        class="kw"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-select v-model="query.role" placeholder="全部角色" clearable class="role" @change="handleSearch">
        <el-option v-for="(label, key) in ROLE_LABEL" :key="key" :label="label" :value="key" />
      </el-select>
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 列表 -->
    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="account" label="账号" min-width="120" />
      <el-table-column prop="name" label="姓名" min-width="100" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="ROLE_TAG[row.role] || 'info'" effect="dark">
            {{ ROLE_LABEL[row.role] || row.role }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="160" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
          <el-button link type="danger" @click="handleRemove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.size"
      :current-page="query.page"
      @current-change="(p) => { query.page = p; loadUsers() }"
    />
  </el-card>

  <!-- 开通账号弹窗 -->
  <el-dialog v-model="createVisible" title="开通账号" width="480px" :close-on-click-modal="false">
    <el-form ref="createRef" :model="createForm" :rules="createRules" label-width="90px">
      <el-form-item label="账号" prop="account">
        <el-input v-model="createForm.account" placeholder="学生学号 / 老师工号或手机号" />
      </el-form-item>
      <el-form-item label="姓名" prop="name">
        <el-input v-model="createForm.name" placeholder="请输入姓名" />
      </el-form-item>
      <el-form-item label="角色" prop="role">
        <el-select v-model="createForm.role" class="full">
          <el-option v-for="(label, key) in ROLE_LABEL" :key="key" :label="label" :value="key" />
        </el-select>
      </el-form-item>
      <el-form-item label="初始密码" prop="password">
        <el-input v-model="createForm.password" type="password" show-password placeholder="首次登录需修改" />
      </el-form-item>
      <el-form-item label="手机号">
        <el-input v-model="createForm.phone" placeholder="老师可选" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="createLoading" @click="submitCreate">开通</el-button>
    </template>
  </el-dialog>

  <!-- 重置密码弹窗 -->
  <el-dialog v-model="resetVisible" title="重置密码" width="420px" :close-on-click-modal="false">
    <p class="reset-tip">
      即将重置 <strong>{{ resetTarget?.name }}</strong>（{{ resetTarget?.account }}）的密码，重置后该用户需重新登录并修改密码。
    </p>
    <el-form ref="resetRef" :model="resetForm" label-width="90px">
      <el-form-item
        label="新密码"
        prop="newPassword"
        :rules="[
          { required: true, message: '请输入新密码', trigger: 'blur' },
          { min: 8, message: '密码至少 8 位', trigger: 'blur' }
        ]"
      >
        <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="至少 8 位" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="resetVisible = false">取消</el-button>
      <el-button type="primary" :loading="resetLoading" @click="submitReset">确认重置</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.manage-card {
  border: none;
  border-radius: var(--radius-lg);
  box-shadow: var(--card-shadow);
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.head-text {
  display: flex;
  flex-direction: column;
}
.head-title {
  margin: 0;
  font-size: 16px;
  color: var(--text-1);
}
.head-sub {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 2px;
}

.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}
.kw {
  width: 220px;
}
.role {
  width: 140px;
}
.full {
  width: 100%;
}

.pager {
  margin-top: 18px;
  justify-content: flex-end;
}
.reset-tip {
  margin: 0 0 16px;
  color: var(--text-2);
  font-size: 14px;
}
</style>
