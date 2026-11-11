import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      // scripts/ 下是 Node 侧 E2E 脚本，不由 vitest 收集
      exclude: [...configDefaults.exclude, 'scripts/**'],
      root: fileURLToPath(new URL('./', import.meta.url)),
    },
  }),
)