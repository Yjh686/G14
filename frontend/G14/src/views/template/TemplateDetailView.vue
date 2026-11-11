<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { templateApi } from '@/api/template'
import { useAuthStore } from '@/stores/auth'
import {
  categoryLabel,
  ratingText,
  questionTypeLabel,
  templateStatusLabel,
  templateStatusType,
} from '@/utils/dict'
import type { TemplateVO } from '@/types/template'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const detail = ref<TemplateVO | null>(null)

const id = computed(() => Number(route.params.id))

const canUse = computed(() => auth.hasPerm('template:use') && detail.value?.status === 'APPROVED')
const canRate = computed(() => auth.hasPerm('template:rate') && detail.value?.status === 'APPROVED')
const isOwner = computed(() => detail.value != null && detail.value.createBy === auth.user?.userId)

async function load() {
  loading.value = true
  try {
    detail.value = await templateApi.detail(id.value)
  } catch (e) {
    ElMessage.error((e as Error).message)
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function useTemplate() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm(
      `将根据《${detail.value.title}》创建一份属于你的新问卷草稿，共 ${detail.value.questionCount} 道题。继续吗？`,
      '使用模板',
      { type: 'info', confirmButtonText: '创建草稿', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const survey = await templateApi.use(detail.value.id)
    ElMessage.success('已创建问卷草稿，可以继续修改后再发布')
    router.push({ name: 'survey-edit', params: { id: survey.id } })
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function rate(score: number) {
  if (!detail.value) return
  // 取消选中时 el-rate 会传 0，后端只接受 1~5
  if (!score) return
  try {
    await templateApi.rate(detail.value.id, score)
    ElMessage.success('评分已提交')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ detail?.title || '模板详情' }}</h2>
        <p class="page-desc">模板是共享时的快照，来源问卷后续修改不会影响这里的内容</p>
      </div>
      <div class="header-actions">
        <el-button @click="router.back()">返回</el-button>
        <el-button v-if="isOwner" @click="router.push({ name: 'my-templates' })">
          我共享的模板
        </el-button>
        <el-button v-if="canUse" type="primary" @click="useTemplate">使用模板</el-button>
      </div>
    </div>

    <template v-if="detail">
      <el-card shadow="never" class="meta-card">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="分类">
            {{ categoryLabel(detail.category) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="templateStatusType(detail.status)" size="small">
              {{ templateStatusLabel(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="题量">{{ detail.questionCount }} 题</el-descriptions-item>
          <el-descriptions-item label="共享者">
            {{ detail.createByName || '未知' }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.submitTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="被使用">{{ detail.useCount }} 次</el-descriptions-item>
          <el-descriptions-item label="匿名">{{ detail.anonymousDefault === 1 ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="限填一次">
            {{ detail.limitOnceDefault === 1 ? '是' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="评分">
            <span class="rating-cell">
              <el-rate
                v-if="canRate"
                :model-value="detail.myScore ?? 0"
                @change="(v: number) => rate(v)"
              />
              <el-rate v-else :model-value="detail.ratingAvg ?? 0" disabled allow-half />
              <span class="rating-text">{{ ratingText(detail.ratingAvg, detail.ratingCount) }}</span>
            </span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="detail.auditRemark" class="audit-remark">
          <el-alert
            :type="detail.status === 'REJECTED' ? 'error' : 'info'"
            :title="`审核意见：${detail.auditRemark}`"
            :closable="false"
            show-icon
          />
        </div>

        <p v-if="detail.description" class="tpl-desc">{{ detail.description }}</p>
      </el-card>

      <el-card shadow="never" class="struct-card">
        <template #header>
          <span>题目结构（{{ detail.structure?.length ?? 0 }} 题）</span>
        </template>

        <el-empty v-if="!detail.structure || detail.structure.length === 0" description="模板暂无题目" />

        <div v-for="(q, i) in detail.structure" :key="i" class="q-item">
          <div class="q-title">
            <span class="q-no">{{ i + 1 }}.</span>
            <span class="q-text">{{ q.title }}</span>
            <el-tag v-if="q.isRequired === 1" size="small" type="danger" effect="plain">必答</el-tag>
            <el-tag size="small" type="info" effect="plain">
              {{ questionTypeLabel(q.questionType) }}
            </el-tag>
          </div>
          <div v-if="q.description" class="q-desc">{{ q.description }}</div>

          <!-- 注意：模板里的选项是纯文本数组，不是带 id 的对象，所以不能复用 QuestionRenderer -->
          <ul v-if="q.options && q.options.length" class="q-options">
            <li v-for="(o, oi) in q.options" :key="oi">{{ o }}</li>
          </ul>

          <div v-if="q.questionType === 'RATING'" class="q-meta">评分上限：{{ q.ratingMax ?? 5 }} 星</div>
          <div
            v-else-if="q.questionType === 'TEXT' || q.questionType === 'TEXTAREA'"
            class="q-meta"
          >
            字数上限：{{ q.maxLength ?? '未限制' }}
          </div>
        </div>
      </el-card>
    </template>

    <el-empty v-else-if="!loading" description="模板不存在或未通过审核" />
  </div>
</template>

<style scoped>
.header-actions {
  display: flex;
  gap: 8px;
}

.meta-card {
  margin-bottom: 16px;
}

.rating-cell {
  display: inline-flex;
  gap: 8px;
  align-items: center;
}

.rating-text {
  font-size: 12px;
  color: #909399;
}

.audit-remark {
  margin-top: 12px;
}

.tpl-desc {
  margin: 12px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
  white-space: pre-wrap;
}

.q-item {
  padding: 14px 0;
  border-bottom: 1px solid #f0f2f5;
}

.q-item:last-child {
  border-bottom: none;
}

.q-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  font-size: 15px;
  line-height: 1.6;
}

.q-no {
  font-weight: 600;
  color: #2f7fd1;
}

.q-text {
  flex: 1;
  font-weight: 500;
}

.q-desc {
  margin: 6px 0 0 22px;
  font-size: 13px;
  color: #909399;
}

.q-options {
  margin: 8px 0 0 22px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: #606266;
}

.q-meta {
  margin: 8px 0 0 22px;
  font-size: 12px;
  color: #a8abb2;
}

.template-status-hint {
  font-size: 12px;
}
</style>
