<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configApi, publicConfigApi } from '@/api/config'
import { ApiError } from '@/api/http'
import { CONFIG_GROUP_LABEL, PASSWORD_MASK } from '@/types/config'
import type { ConfigGroup, ConfigSaveRequest, ConfigType, ConfigVO } from '@/types/config'
import { useConfigStore } from '@/stores/config'

/**
 * 系统配置（FR-CFG-01/02/03）。
 *
 * 两件事刻意做在一起而不是拆两页：
 * - 站点信息（名称/Logo/公告/页脚）和密码策略都是「改完立即生效」的全局开关，
 *   管理员调整时通常一起看；
 * - 两者共用同一套「内置项可改不可删 / 自定义项可增删」的交互，
 *   拆开就得写两份几乎一样的增删校验。
 *
 * 保存是全量提交（PUT /api/config 收数组），所以这里维护一份编辑副本 edit，
 * 点「保存」才写库。这样管理员改到一半离开页面，线上配置不会被半成品覆盖。
 */

const configStore = useConfigStore()

const loading = ref(false)
const saving = ref(false)

/** 服务端返回的原始数据，用于判断哪些项被改过、以及「重置」时还原 */
const groups = ref<Record<string, ConfigVO[]>>({})

/** 编辑副本：configKey → 当前输入值。只存值，结构信息仍从 groups 取 */
const edit = reactive<Record<string, string>>({})

const GROUP_ORDER: ConfigGroup[] = ['SITE', 'SECURITY', 'OTHER']

const TYPE_OPTIONS: { value: ConfigType; label: string }[] = [
  { value: 'STRING', label: '短文本' },
  { value: 'TEXT', label: '长文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'BOOLEAN', label: '开关(0/1)' },
  { value: 'PASSWORD', label: '密码' },
]

const GROUP_OPTIONS = GROUP_ORDER.map((g) => ({ value: g, label: CONFIG_GROUP_LABEL[g] }))

/** 按固定分组顺序排出要渲染的块，后端返回的分组顺序不稳定 */
const sections = computed(() =>
  GROUP_ORDER.filter((g) => (groups.value[g]?.length ?? 0) > 0).map((g) => ({
    group: g,
    label: CONFIG_GROUP_LABEL[g],
    items: groups.value[g] ?? [],
  })),
)

/** 有改动的项，用于按钮计数与「没改动就不发请求」 */
const dirtyKeys = computed(() =>
  Object.keys(edit).filter((k) => {
    const origin = findConfig(k)
    return origin != null && edit[k] !== origin.configValue
  }),
)

const hasDirty = computed(() => dirtyKeys.value.length > 0)

function findConfig(key: string): ConfigVO | undefined {
  for (const list of Object.values(groups.value)) {
    const hit = list.find((c) => c.configKey === key)
    if (hit) return hit
  }
  return undefined
}

/** 把服务端数据装进编辑副本；PASSWORD 项存的是 `******`，原样回传即「不修改」 */
function fillEdit() {
  for (const key of Object.keys(edit)) delete edit[key]
  for (const list of Object.values(groups.value)) {
    for (const c of list) edit[c.configKey] = c.configValue
  }
}

async function load() {
  loading.value = true
  try {
    groups.value = await configApi.list()
    fillEdit()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '加载配置失败')
  } finally {
    loading.value = false
  }
}

function resetAll() {
  fillEdit()
  ElMessage.info('已还原为当前保存的值')
}

async function save() {
  if (!hasDirty.value) {
    ElMessage.info('没有需要保存的改动')
    return
  }
  const items: ConfigSaveRequest[] = dirtyKeys.value.map((k) => {
    const c = findConfig(k)!
    return {
      configKey: k,
      configValue: edit[k],
      configType: c.configType,
      groupName: c.groupName,
      description: c.description ?? undefined,
      publicVisible: c.publicVisible,
    }
  })
  saving.value = true
  try {
    const changed = await configApi.save(items)
    ElMessage.success(`已保存 ${changed} 项配置`)
    await load()
    // 站点名/公告/密码规则在登录页、侧边栏、标题都用到，保存后立刻拉一次
    await configStore.load(true)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

/* ---------------- 新增/删除自定义项 ---------------- */

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive<ConfigSaveRequest>({
  configKey: '',
  configValue: '',
  configType: 'STRING',
  groupName: 'OTHER',
  description: '',
  publicVisible: false,
})
const createFormRef = ref()

const createRules = {
  configKey: [
    { required: true, message: '请输入配置键', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9._-]{1,63}$/,
      message: '只能由字母、数字、点、下划线、短横线组成，且以字母开头',
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  createForm.configKey = ''
  createForm.configValue = ''
  createForm.configType = 'STRING'
  createForm.groupName = 'OTHER'
  createForm.description = ''
  createForm.publicVisible = false
  createVisible.value = true
}

async function submitCreate() {
  const form = createFormRef.value
  if (form) {
    const ok = await form.validate().catch(() => false)
    if (!ok) return
  }
  creating.value = true
  try {
    await configApi.create({ ...createForm })
    ElMessage.success('配置项已创建')
    createVisible.value = false
    await load()
    await configStore.load(true)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

async function removeItem(c: ConfigVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除配置项「${c.configKey}」吗？删除后前端再读就取不到这个键了。`,
      '删除配置项',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await configApi.remove(c.id)
    ElMessage.success('配置项已删除')
    await load()
    await configStore.load(true)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '删除失败')
  }
}

/* ---------------- 公开配置预览 ---------------- */

const previewVisible = ref(false)
const preview = ref<Record<string, string>>({})

async function openPreview() {
  try {
    preview.value = await publicConfigApi.get()
    previewVisible.value = true
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '读取公开配置失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">系统配置</h2>
        <p class="page-desc">
          站点名称、公告与密码策略修改后立即生效；标记为「公开」的项会被登录页读取，
          密码类配置永远不会公开。
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="openPreview">查看公开配置</el-button>
        <el-button :disabled="!hasDirty" @click="resetAll">还原改动</el-button>
        <el-button type="primary" :loading="saving" :disabled="!hasDirty" @click="save">
          保存{{ hasDirty ? `（${dirtyKeys.length}）` : '' }}
        </el-button>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else>
      <el-card v-for="sec in sections" :key="sec.group" class="group-card" shadow="never">
        <template #header>
          <div class="group-head">
            <span class="group-title">{{ sec.label }}</span>
            <el-tag size="small" type="info" effect="plain">{{ sec.group }}</el-tag>
          </div>
        </template>

        <div v-for="c in sec.items" :key="c.configKey" class="cfg-row">
          <div class="cfg-meta">
            <div class="cfg-key">
              {{ c.configKey }}
              <el-tag v-if="c.builtin" size="small" type="info" effect="plain">内置</el-tag>
              <el-tag v-if="c.publicVisible" size="small" type="success" effect="plain">公开</el-tag>
              <el-tag v-if="c.masked" size="small" type="warning" effect="plain">已加密</el-tag>
            </div>
            <div class="cfg-desc">{{ c.description || '—' }}</div>
          </div>

          <div class="cfg-input">
            <el-switch
              v-if="c.configType === 'BOOLEAN'"
              :model-value="edit[c.configKey] === '1'"
              @update:model-value="(v: string | number | boolean) => (edit[c.configKey] = v ? '1' : '0')"
            />
            <el-input
              v-else-if="c.configType === 'TEXT'"
              v-model="edit[c.configKey]"
              type="textarea"
              :rows="2"
              maxlength="2000"
              show-word-limit
              placeholder="留空表示不显示"
            />
            <el-input
              v-else-if="c.configType === 'PASSWORD'"
              v-model="edit[c.configKey]"
              type="password"
              show-password
              placeholder="留空表示不修改"
            />
            <el-input
              v-else
              v-model="edit[c.configKey]"
              :type="c.configType === 'NUMBER' ? 'number' : 'text'"
              :placeholder="c.configType === 'NUMBER' ? '请输入数字' : '请输入内容'"
            />
            <div v-if="c.masked" class="cfg-tip">
              当前值为 {{ PASSWORD_MASK }}，保持原样提交表示不修改
            </div>
          </div>

          <div class="cfg-ops">
            <el-button v-if="!c.builtin" link type="danger" @click="removeItem(c)">删除</el-button>
          </div>
        </div>
      </el-card>

      <el-card class="group-card" shadow="never">
        <template #header>
          <div class="group-head">
            <span class="group-title">自定义配置项</span>
            <el-button type="primary" size="small" @click="openCreate">新增配置项</el-button>
          </div>
        </template>
        <p class="page-desc">
          内置项可以改值但不能删除（删掉会让代码兜底值生效，行为变得难以排查）。
          自定义项全部为公开与否可配置，适合放页脚联系方式这类站点信息。
        </p>
      </el-card>
    </template>

    <el-dialog v-model="createVisible" title="新增配置项" width="520px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="96px">
        <el-form-item label="配置键" prop="configKey">
          <el-input v-model="createForm.configKey" placeholder="例如 site.contact" />
        </el-form-item>
        <el-form-item label="值">
          <el-input v-model="createForm.configValue" placeholder="可留空" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="createForm.configType" style="width: 100%">
            <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="分组">
          <el-select v-model="createForm.groupName" style="width: 100%">
            <el-option v-for="g in GROUP_OPTIONS" :key="g.value" :label="g.label" :value="g.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="createForm.description" placeholder="给人看的用途说明" />
        </el-form-item>
        <el-form-item label="公开">
          <el-switch v-model="createForm.publicVisible" />
          <span class="cfg-tip inline">公开项会被未登录的登录页读取</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="previewVisible" title="公开配置预览（未登录可见）" size="480px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="这就是登录页在还没有登录令牌时能拿到的东西"
        description="私有项（如默认密码）不会出现在这里。"
        class="preview-alert"
      />
      <el-descriptions :column="1" border>
        <el-descriptions-item v-for="(v, k) in preview" :key="k" :label="String(k)">
          <span v-if="v === ''" class="cfg-empty">空</span>
          <span v-else>{{ v }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.group-card {
  margin-bottom: 16px;
}

.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.group-title {
  font-size: 15px;
  font-weight: 600;
}

.cfg-row {
  display: grid;
  grid-template-columns: 260px 1fr 60px;
  gap: 16px;
  align-items: start;
  padding: 12px 0;
  border-bottom: 1px solid #f0f2f5;
}

.cfg-row:last-child {
  border-bottom: none;
}

.cfg-meta {
  min-width: 0;
}

.cfg-key {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  color: #303133;
}

.cfg-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.cfg-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
}

.cfg-tip.inline {
  margin-left: 10px;
}

.cfg-empty {
  color: #c0c4cc;
}

.cfg-ops {
  text-align: right;
}

.preview-alert {
  margin-bottom: 14px;
}
</style>
