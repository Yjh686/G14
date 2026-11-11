<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { userApi } from '@/api/user'
import { ApiError } from '@/api/http'
import type { CreateUserRequest, RoleCode, UpdateUserRequest, UserVO } from '@/types/api'
import { ALL_ROLES, validatePassword, validatePhone } from '@/utils/dict'
import { useConfigStore } from '@/stores/config'

const props = defineProps<{
  /** null 表示新建 */
  user: UserVO | null
}>()

const emit = defineEmits<{
  saved: []
}>()

const visible = defineModel<boolean>({ required: true })
const config = useConfigStore()

const isEdit = computed(() => Boolean(props.user))
const title = computed(() => (isEdit.value ? '编辑用户' : '新建用户'))
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  username: '',
  realName: '',
  roleCode: 'ROLE_STUDENT' as RoleCode,
  password: '',
  department: '',
  phone: '',
  email: '',
  remark: '',
})

const rules = computed<FormRules>(() => ({
  username: isEdit.value
    ? []
    : [
        { required: true, message: '请输入学号/工号', trigger: 'blur' },
        { min: 4, max: 64, message: '账号长度需为 4~64 位', trigger: 'blur' },
        {
          pattern: /^[A-Za-z0-9_.-]+$/,
          message: '账号只能包含字母、数字、下划线、点或短横线',
          trigger: 'blur',
        },
      ],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  roleCode: [{ required: true, message: '请选择角色', trigger: 'change' }],
  password: [
    {
      validator: (_rule, value: string, callback) => {
        // 新建时留空表示使用系统默认密码
        if (!value) return callback()
        const err = validatePassword(value, config.passwordRules)
        callback(err ? new Error(err) : undefined)
      },
      trigger: 'blur',
    },
  ],
  phone: [
    {
      validator: (_rule, value: string, callback) => {
        const err = validatePhone(value)
        callback(err ? new Error(err) : undefined)
      },
      trigger: 'blur',
    },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}))

function resetForm() {
  const u = props.user
  form.username = u?.username ?? ''
  form.realName = u?.realName ?? ''
  form.roleCode = u?.roleCode ?? 'ROLE_STUDENT'
  form.password = ''
  form.department = u?.department ?? ''
  form.phone = u?.phone ?? ''
  form.email = u?.email ?? ''
  form.remark = u?.remark ?? ''
  formRef.value?.clearValidate()
}

watch(visible, (open) => {
  if (open) resetForm()
})

async function handleSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (isEdit.value && props.user) {
      const payload: UpdateUserRequest = {
        realName: form.realName.trim(),
        roleCode: form.roleCode,
        department: form.department.trim(),
        phone: form.phone.trim(),
        email: form.email.trim(),
        remark: form.remark.trim(),
      }
      await userApi.update(props.user.id, payload)
      ElMessage.success('用户信息已更新')
    } else {
      const payload: CreateUserRequest = {
        username: form.username.trim(),
        realName: form.realName.trim(),
        roleCode: form.roleCode,
        password: form.password || undefined,
        department: form.department.trim(),
        phone: form.phone.trim(),
        email: form.email.trim(),
        remark: form.remark.trim(),
      }
      await userApi.create(payload)
      ElMessage.success(
        form.password ? '用户已创建' : '用户已创建，将使用系统默认密码，首次登录需修改',
      )
    }
    visible.value = false
    emit('saved')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="600px" destroy-on-close>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="学号/工号" prop="username">
        <el-input
          v-model="form.username"
          :disabled="isEdit"
          placeholder="4~64 位字母、数字、下划线、点或短横线"
          clearable
        />
        <div v-if="isEdit" class="field-tip">账号创建后不可修改</div>
      </el-form-item>

      <el-form-item label="真实姓名" prop="realName">
        <el-input v-model="form.realName" placeholder="请输入真实姓名" clearable />
      </el-form-item>

      <el-form-item label="角色" prop="roleCode">
        <el-select v-model="form.roleCode" placeholder="请选择角色" class="w-full">
          <el-option v-for="r in ALL_ROLES" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
        <div v-if="isEdit" class="field-tip">修改角色会同时更新该账号的角色权限</div>
      </el-form-item>

      <el-form-item v-if="!isEdit" label="初始密码" prop="password">
        <el-input v-model="form.password" type="password" show-password placeholder="留空则使用系统默认密码" />
        <div class="field-tip">
          {{ config.passwordRuleText }}；留空时使用系统默认密码并强制首次登录改密
        </div>
      </el-form-item>

      <el-form-item label="院系/班级" prop="department">
        <el-input v-model="form.department" placeholder="选填" clearable />
      </el-form-item>

      <el-form-item label="手机号" prop="phone">
        <el-input v-model="form.phone" placeholder="选填" clearable />
      </el-form-item>

      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="选填" clearable />
      </el-form-item>

      <el-form-item label="备注" prop="remark">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-tip {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
