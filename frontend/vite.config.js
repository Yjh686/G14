import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

// 开发环境代理：把 /api 转发到后端服务，避免跨域
//
// 注意：Vite **不会**把 .env 里的变量注入到配置文件可见的 process.env
// （process.env 只包含真实的进程环境变量，如 shell 里 export 的）。
// 直接写 `process.env.VITE_PROXY_TARGET` 会永远得到 undefined，
// 于是 .env.development 里的配置被静默忽略、始终回落到硬编码默认值。
// 必须用 loadEnv(mode, cwd, 'VITE_') 显式读取。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = env.VITE_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target,
          changeOrigin: true
        }
      }
    }
  }
})
