<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="auth-wrapper">
    <el-result icon="warning" title="403" sub-title="您没有访问该页面的权限，如需开通请联系管理员">
      <template #extra>
        <el-button type="primary" @click="router.replace('/')">返回工作台</el-button>
        <el-button @click="router.back()">返回上一页</el-button>
      </template>
    </el-result>
  </div>
</template>
