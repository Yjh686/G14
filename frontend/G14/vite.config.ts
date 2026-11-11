import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    /**
     * 按需引入 Element Plus。
     *
     * 原来 main.ts 里是 `app.use(ElementPlus)` 全量注册，产物主 chunk 1.29MB
     * （gzip 419KB）+ CSS 365KB，首屏必须走完这一坨。
     * 电脑上有本地缓存看不出问题，但经公网隧道（实测约 60KB/s）首屏要 6~8 秒。
     *
     * 这两个插件做的事：模板里写 <el-button> 时自动补 import 与样式，
     * script 里写 ElMessage 时自动补 import —— 源码不用改一行。
     * 只打进真正用到的组件（本项目 49 个），省掉的是「一次都没用过」的组件
     * 和全部 300 多个图标。
     */
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      // ElMessage / ElMessageBox 这类编程式 API 也走按需引入
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
      // 自己的组件也自动注册，省掉一堆 import（BaseChart / QuestionRenderer 等）
      dirs: ['src/components'],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 前端统一请求 /api，开发环境由 Vite 代理到后端，避免跨域与硬编码地址
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
