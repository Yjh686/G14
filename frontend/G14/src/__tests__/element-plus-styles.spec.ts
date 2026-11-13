import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 按需引入下最隐蔽的一类缺陷：编程式 API 的样式没有被引入。
 *
 * unplugin-vue-components 的 ElementPlusResolver 只处理「模板里写死的标签名」，
 * 对 `<script>` 里手动 `import { ElMessage } from 'element-plus'` 完全不生效。
 * 结果是 JS 正常、CSS 缺失：确认框失去 position:fixed 与遮罩，退化成普通块级
 * 元素排到 body 末尾 —— 用户看到的就是「提示框跑到页面最下面」。
 *
 * 这个问题在小屏幕上同源、在本地 dev 下也可能被缓存的旧样式掩盖，靠肉眼很难发现，
 * 所以用单测锁住：main.ts 必须显式引入这三个 style 入口。
 */
const here = dirname(fileURLToPath(import.meta.url))
const mainTs = readFileSync(resolve(here, '../main.ts'), 'utf8')

describe('编程式 API 的样式必须显式引入', () => {
  const required = [
    'element-plus/es/components/message/style/css',
    'element-plus/es/components/message-box/style/css',
    'element-plus/es/components/loading/style/css',
  ]

  for (const entry of required) {
    it(`main.ts 引入了 ${entry}`, () => {
      expect(mainTs).toContain(entry)
    })
  }

  it('不再全量注册 Element Plus', () => {
    // app.use(ElementPlus) 会把整套样式与组件重新打进首屏，抵消按需引入的收益
    expect(mainTs).not.toMatch(/app\.use\(ElementPlus/)
    expect(mainTs).not.toContain('element-plus/dist/index.css')
  })
})
