<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportLoginLogs, exportOperationLogs, logApi } from '@/api/log'
import { ApiError } from '@/api/http'
import type { LoginLogVO, LogQuery, OperationLogVO } from '@/types/log'
import { useAuthStore } from '@/stores/auth'

/**
 * 日志查询（FR-LOG-01/02/03）。
 *
 * 两个 Tab 而不是两个页面：登录日志与操作日志的查询条件、分页、导出行为完全一致，
 * 拆成两页会得到两份几乎一样的代码；管理员排查问题时也常常是「先看登录记录，
 * 再看他都干了什么」，同一个页面切换比来回跳路由顺手。
 *
 * 页面上没有任何「删除」「清理」按钮 —— 后端也只有 GET，
 * 这是 FR-LOG-03「日志只读」的直接体现，不是功能没做完。
 */

const auth = useAuthStore()
const canExport = auth.hasPerm('log:export')

const tab = ref<'login' | 'operation'>('login')

/** 结果选项：1=成功 0=失败（后端小整数，不是布尔） */
const RESULT_OPTIONS = [
  { value: 1, label: '成功' },
  { value: 0, label: '失败' },
]

function resultLabel(v: number | null | undefined): string {
  return v === 1 ? '成功' : '失败'
}

function resultType(v: number | null | undefined): 'success' | 'danger' {
  return v === 1 ? 'success' : 'danger'
}

/* ------------------------------ 登录日志 ------------------------------ */

const loginLoading = ref(false)
const loginRows = ref<LoginLogVO[]>([])
const loginTotal = ref(0)
const loginQuery = reactive({
  keyword: '',
  result: undefined as number | undefined,
  range: null as [string, string] | null,
  page: 1,
  size: 20,
})

async function loadLogin() {
  loginLoading.value = true
  try {
    const res = await logApi.loginLogs(buildQuery(loginQuery))
    loginRows.value = res.records
    loginTotal.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载登录日志失败')
  } finally {
    loginLoading.value = false
  }
}

function searchLogin() {
  loginQuery.page = 1
  loadLogin()
}

function resetLogin() {
  loginQuery.keyword = ''
  loginQuery.result = undefined
  loginQuery.range = null
  loginQuery.page = 1
  loadLogin()
}

/* ------------------------------ 操作日志 ------------------------------ */

const opLoading = ref(false)
const opRows = ref<OperationLogVO[]>([])
const opTotal = ref(0)
const opQuery = reactive({
  keyword: '',
  result: undefined as number | undefined,
  module: undefined as string | undefined,
  action: '',
  range: null as [string, string] | null,
  page: 1,
  size: 20,
})

/**
 * 模块下拉的数据来自后端 `SELECT DISTINCT module`，不在前端写死清单 ——
 * 以后新增「模板管理」「系统配置」等模块时，下拉框自动就有了。
 */
const moduleOptions = ref<string[]>([])

async function loadModules() {
  try {
    moduleOptions.value = await logApi.modules()
  } catch {
    /* 拿不到模块清单不影响日志查询，静默失败即可 */
  }
}

async function loadOperations() {
  opLoading.value = true
  try {
    const q = buildQuery(opQuery)
    if (opQuery.module) q.module = opQuery.module
    if (opQuery.action.trim()) q.action = opQuery.action.trim()
    const res = await logApi.operationLogs(q)
    opRows.value = res.records
    opTotal.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载操作日志失败')
  } finally {
    opLoading.value = false
  }
}

function searchOperations() {
  opQuery.page = 1
  loadOperations()
}

function resetOperations() {
  opQuery.keyword = ''
  opQuery.result = undefined
  opQuery.module = undefined
  opQuery.action = ''
  opQuery.range = null
  opQuery.page = 1
  loadOperations()
}

/* ------------------------------ 公共 ------------------------------ */

/**
 * 把 UI 上的「日期范围」摊平成后端要的两个时间字符串。
 * el-date-picker 用 value-format 直接给 'yyyy-MM-dd HH:mm:ss'，
 * 起止都是「含」边界，所以不加 1 秒。
 */
function buildQuery(src: {
  keyword: string
  result?: number
  range: [string, string] | null
  page: number
  size: number
}): LogQuery {
  const q: LogQuery = { page: src.page, size: src.size }
  if (src.keyword.trim()) q.keyword = src.keyword.trim()
  if (src.result !== undefined) q.result = src.result
  if (src.range?.[0]) q.startTime = src.range[0]
  if (src.range?.[1]) q.endTime = src.range[1]
  return q
}

function switchTab(name: string | number) {
  if (name === 'operation' && moduleOptions.value.length === 0) loadModules()
}

/* ------------------------------ 导出 ------------------------------ */

const exporting = ref(false)

async function doExport() {
  exporting.value = true
  try {
    const name =
      tab.value === 'login'
        ? await exportLoginLogs(buildQuery(loginQuery))
        : await exportOperationLogs(
            (() => {
              const q = buildQuery(opQuery)
              if (opQuery.module) q.module = opQuery.module
              if (opQuery.action.trim()) q.action = opQuery.action.trim()
              return q
            })(),
          )
    ElMessage.success(`已导出 ${name}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  loadLogin()
  loadModules()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">日志查询</h2>
        <p class="page-desc">
          登录日志记录每一次登录尝试（含失败原因），操作日志记录问卷、用户、权限、导出等关键动作。
          日志只读，系统内不提供删除入口。
        </p>
      </div>
      <el-button v-if="canExport" type="primary" :loading="exporting" @click="doExport">
        导出当前筛选结果
      </el-button>
    </div>

    <el-tabs v-model="tab" @tab-change="switchTab">
      <!-- ============================ 登录日志 ============================ -->
      <el-tab-pane label="登录日志" name="login">
        <div class="filter-bar">
          <el-input
            v-model="loginQuery.keyword"
            placeholder="账号模糊搜索"
            clearable
            style="width: 200px"
            @keyup.enter="searchLogin"
          />
          <el-select
            v-model="loginQuery.result"
            placeholder="全部结果"
            clearable
            style="width: 130px"
          >
            <el-option
              v-for="o in RESULT_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <el-date-picker
            v-model="loginQuery.range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 360px"
          />
          <el-button type="primary" @click="searchLogin">查询</el-button>
          <el-button @click="resetLogin">重置</el-button>
        </div>

        <el-table v-loading="loginLoading" :data="loginRows" stripe>
          <el-table-column prop="username" label="账号" width="150" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="resultType(row.result)" size="small">
                {{ resultLabel(row.result) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="说明" min-width="200" show-overflow-tooltip />
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column label="客户端" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.userAgent || '-' }}</template>
          </el-table-column>
          <el-table-column prop="createTime" label="时间" width="170" />
          <template #empty>
            <el-empty description="没有符合条件的登录记录" />
          </template>
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="loginQuery.page"
            v-model:page-size="loginQuery.size"
            :total="loginTotal"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadLogin"
            @size-change="searchLogin"
          />
        </div>
      </el-tab-pane>

      <!-- ============================ 操作日志 ============================ -->
      <el-tab-pane label="操作日志" name="operation">
        <div class="filter-bar">
          <el-input
            v-model="opQuery.keyword"
            placeholder="账号或操作内容"
            clearable
            style="width: 200px"
            @keyup.enter="searchOperations"
          />
          <el-select
            v-model="opQuery.module"
            placeholder="全部模块"
            clearable
            style="width: 150px"
          >
            <el-option v-for="m in moduleOptions" :key="m" :label="m" :value="m" />
          </el-select>
          <el-input
            v-model="opQuery.action"
            placeholder="动作，如 发布问卷"
            clearable
            style="width: 170px"
            @keyup.enter="searchOperations"
          />
          <el-select
            v-model="opQuery.result"
            placeholder="全部结果"
            clearable
            style="width: 130px"
          >
            <el-option
              v-for="o in RESULT_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <el-date-picker
            v-model="opQuery.range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 360px"
          />
          <el-button type="primary" @click="searchOperations">查询</el-button>
          <el-button @click="resetOperations">重置</el-button>
        </div>

        <el-table v-loading="opLoading" :data="opRows" stripe>
          <el-table-column prop="username" label="操作人" width="130">
            <template #default="{ row }">{{ row.username || '-' }}</template>
          </el-table-column>
          <el-table-column prop="module" label="模块" width="120" />
          <el-table-column prop="action" label="动作" width="140" />
          <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.detail || '-' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="resultType(row.result)" size="small">
                {{ resultLabel(row.result) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="140" />
          <el-table-column prop="createTime" label="时间" width="170" />
          <template #empty>
            <el-empty description="没有符合条件的操作记录" />
          </template>
        </el-table>

        <div class="pagination-bar">
          <el-pagination
            v-model:current-page="opQuery.page"
            v-model:page-size="opQuery.size"
            :total="opTotal"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadOperations"
            @size-change="searchOperations"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
