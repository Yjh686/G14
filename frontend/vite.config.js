import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 开发环境代理：把 /api 转发到后端服务，避免跨域
// 后端地址可通过环境变量 VITE_PROXY_TARGET 覆盖，默认 http://localhost:8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
