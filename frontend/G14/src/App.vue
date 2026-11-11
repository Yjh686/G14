<script setup lang="ts">
// 中文语言包：原来靠 app.use(ElementPlus, { locale: zhCn }) 全局注入，
// 改成按需引入后没有这一步了，改用 ElConfigProvider 包住整个应用。
// 少了它会看到分页「10/page」、日期选择器是英文月份。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
