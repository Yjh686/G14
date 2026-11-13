import { createApp } from 'vue'
import { createPinia } from 'pinia'

// Element Plus 组件与样式由 unplugin-vue-components 按需注入（见 vite.config.ts），
// 这里只手动处理两件插件管不到的事：全局中文语言包、以及「用字符串指定图标」的情况。
import {
  ArrowDown,
  CircleCheck,
  Document,
  EditPen,
  Expand,
  Files,
  Fold,
  HomeFilled,
  Key,
  Memo,
  Setting,
  Share,
  Stamp,
  Tickets,
  Tools,
  User,
} from '@element-plus/icons-vue'

/**
 * 编程式 API 的样式必须显式引入。
 *
 * unplugin-vue-components 的 resolver 只认模板里写死的标签名，对
 * `import { ElMessage } from 'element-plus'` 这种手动导入不生效：
 * JS 正常、CSS 缺失。缺的是 el-message-box 自己的 `position: fixed` 居中
 * 与 el-overlay 的全屏遮罩，于是确认框退化成普通块级元素、排在 body 末尾
 * —— 也就是「退出登录的提示框跑到页面最下面」。
 *
 * 这三个 css.mjs 内部只 import 需要的分片（message-box 会带 input/button/overlay），
 * 加起来约 30KB，比全量 theme-chalk 便宜得多。
 */
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'

import '@/styles/global.css'

import App from './App.vue'
import router from './router'
import { onUnauthorized } from './api/http'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

/**
 * 图标只注册这 16 个，而不是 `for (const [k, v] of Object.entries(ElementPlusIconsVue))`
 * 全量注册 300 多个。
 *
 * 为什么不能完全交给按需插件：菜单项是数据驱动的
 * （`{ icon: 'Memo' }` + `<component :is="item.icon" />`），
 * 插件只认模板里写死的标签名，认不出字符串。所以这些必须手工登记。
 * 新增菜单项时如果图标显示为空，就是漏登记了这一处。
 */
const ICONS = {
  ArrowDown,
  CircleCheck,
  Document,
  EditPen,
  Expand,
  Files,
  Fold,
  HomeFilled,
  Key,
  Memo,
  Setting,
  Share,
  Stamp,
  Tickets,
  Tools,
  User,
}
for (const [name, comp] of Object.entries(ICONS)) {
  app.component(name, comp)
}

/**
 * 401 统一处理：清掉登录态并跳登录页。
 * 在这里注册而不是在 axios 模块内直接 import router，避免循环依赖。
 */
const auth = useAuthStore(pinia)
onUnauthorized(() => {
  auth.clear()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')
