<script setup lang="ts">
import { computed } from 'vue'
import { isTextType, questionTypeLabel } from '@/utils/dict'
import type { AnswerInput, QuestionVO } from '@/types/survey'

/**
 * 单题渲染 + 作答绑定。
 *
 * 填写页与预览页共用：预览页传 readonly，填写页双向绑定 answers。
 * 作答状态统一放在 AnswerInput 里（optionValues / textValue / numberValue），
 * 提交时直接把这个数组发给后端，不需要再做一次格式转换。
 */
const props = defineProps<{
  question: QuestionVO
  index: number
  modelValue: AnswerInput
  readonly?: boolean
  /** 校验失败时高亮这道题 */
  invalid?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [AnswerInput] }>()

const q = computed(() => props.question)

/** 单选：el-radio-group 绑的是单个值，这里在 number|null 之间转换 */
const singleValue = computed<number | undefined>({
  get: () => props.modelValue.optionValues?.[0],
  set: (v) => emit('update:modelValue', { ...props.modelValue, optionValues: v == null ? [] : [v] }),
})

/** 多选：直接绑数组，但 el-checkbox-group 不接受 null */
const multipleValue = computed<number[]>({
  get: () => props.modelValue.optionValues ?? [],
  set: (v) => emit('update:modelValue', { ...props.modelValue, optionValues: v }),
})

const textLimit = computed(() => q.value.maxLength ?? (q.value.questionType === 'TEXTAREA' ? 500 : 100))

const ratingMax = computed(() => q.value.ratingMax ?? 5)

const textValue = computed<string>({
  get: () => props.modelValue.textValue ?? '',
  set: (v) => emit('update:modelValue', { ...props.modelValue, textValue: v }),
})

const numberValue = computed<number | undefined>({
  get: () => props.modelValue.numberValue ?? undefined,
  set: (v) =>
    emit('update:modelValue', { ...props.modelValue, numberValue: v == null ? null : v }),
})
</script>

<template>
  <div class="question" :class="{ 'is-invalid': invalid }">
    <div class="question-title">
      <span class="no">{{ index + 1 }}.</span>
      <span class="text">{{ q.title }}</span>
      <el-tag v-if="q.isRequired === 1" size="small" type="danger" effect="plain" class="req">
        必答
      </el-tag>
      <el-tag size="small" type="info" effect="plain">{{ questionTypeLabel(q.questionType) }}</el-tag>
    </div>
    <div v-if="q.description" class="question-desc">{{ q.description }}</div>

    <!-- 单选 -->
    <el-radio-group
      v-if="q.questionType === 'SINGLE'"
      v-model="singleValue"
      :disabled="readonly"
      class="options"
    >
      <el-radio v-for="o in q.options" :key="o.id" :value="o.id" class="option">
        {{ o.content }}
      </el-radio>
    </el-radio-group>

    <!-- 多选 -->
    <el-checkbox-group
      v-else-if="q.questionType === 'MULTIPLE'"
      v-model="multipleValue"
      :disabled="readonly"
      class="options"
    >
      <el-checkbox v-for="o in q.options" :key="o.id" :value="o.id" class="option">
        {{ o.content }}
      </el-checkbox>
    </el-checkbox-group>

    <!-- 填空题 -->
    <el-input
      v-else-if="q.questionType === 'TEXT'"
      v-model="textValue"
      :maxlength="textLimit"
      :disabled="readonly"
      show-word-limit
      placeholder="请输入"
      class="text-input"
    />

    <!-- 多行文本 -->
    <el-input
      v-else-if="q.questionType === 'TEXTAREA'"
      v-model="textValue"
      type="textarea"
      :rows="4"
      :maxlength="textLimit"
      :disabled="readonly"
      show-word-limit
      placeholder="请输入"
      class="text-input"
    />

    <!-- 评分 -->
    <div v-else-if="q.questionType === 'RATING'" class="rating-row">
      <el-rate v-model="numberValue" :max="ratingMax" :disabled="readonly" />
      <span class="rating-hint">{{ numberValue ?? 0 }} / {{ ratingMax }}</span>
    </div>

    <div v-if="invalid" class="invalid-hint">这道题还没有填写</div>
  </div>
</template>

<style scoped>
.question {
  padding: 14px 0;
  border-bottom: 1px solid #f0f2f5;
}

.question:last-child {
  border-bottom: none;
}

.question.is-invalid {
  padding: 14px 12px;
  margin: 0 -12px;
  background-color: #fef0f0;
  border-radius: 6px;
}

.question-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 6px;
  font-size: 15px;
  line-height: 1.6;
}

.no {
  font-weight: 600;
  color: #2f7fd1;
}

.text {
  flex: 1;
  font-weight: 500;
}

.req {
  margin-left: 2px;
}

.question-desc {
  margin: 0 0 10px 22px;
  font-size: 13px;
  line-height: 1.6;
  color: #909399;
}

.options {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-left: 22px;
}

.option {
  height: auto;
  margin-right: 0;
}

.text-input {
  max-width: 560px;
  margin-left: 22px;
}

.rating-row {
  display: flex;
  gap: 12px;
  align-items: center;
  padding-left: 22px;
}

.rating-hint {
  font-size: 13px;
  color: #909399;
}

.invalid-hint {
  margin: 8px 0 0 22px;
  font-size: 12px;
  color: #f56c6c;
}
</style>
