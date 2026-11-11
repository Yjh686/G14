# frontend/AGENTS.md

前端工程位于 `frontend/G14/`，技术栈：Vue 3.5 + Vite 6 + Vue Router 4 + Pinia 3 + Element Plus 2 + Axios + TypeScript。

## 启动与验证

```powershell
cd frontend\G14
npm install
npm run dev            # http://localhost:5173，/api 代理到 http://localhost:8080
npm run build          # 类型检查 + 生产构建
npm run test:unit      # Vitest 单元测试
npm run e2e            # 认证与后台 E2E（需后端与 dev server 已启动）
npm run e2e:survey     # 问卷全链路 E2E：建卷→发布→学生填写→答卷管理
npm run e2e:survey-edit # 问卷编辑 E2E：预览、复制、草稿自动保存与断点续答
npm run e2e:stat       # 统计 E2E：图表数据与 Excel 下载
npm run e2e:template   # 模板 E2E：共享→审核→评分→使用→下架
npm run e2e:config     # 系统配置 E2E：站点名/公告/密码规则联动、公开配置边界
npm run e2e:all        # 依次跑完六个 E2E
```

## 目录约定

```text
src/
├── api/          # 后端接口封装：http.ts 是唯一 axios 实例，auth.ts / user.ts / survey.ts / stat.ts / template.ts / log.ts / config.ts 按模块分文件
├── stores/       # Pinia：auth（登录态）与 config（公开配置，路由守卫里预加载）
├── router/       # 路由表 + 导航守卫（登录、强制改密、权限校验）
├── layout/       # MainLayout：侧边菜单 + 顶栏，菜单由权限动态生成
├── views/        # 页面，按领域分目录：auth / user / admin / survey / template / error
├── components/   # 可复用组件：UserFormDialog、QuestionRenderer（题目渲染）、BaseChart（ECharts 容器）、ShareTemplateDialog
├── types/        # 与后端 DTO 一一对应的 TypeScript 类型
├── utils/        # token 存取、字典文案与校验规则、chart.ts 图表 option 构造器
└── styles/       # global.css 全局样式
```

## 必须遵守的约定

1. **接口返回值就是业务数据**：`src/api/http.ts` 的响应拦截器已经把统一响应体拆掉，业务代码 `await userApi.page(...)` 直接拿到 `PageResult<UserVO>`。只有非 200 的 code 会抛 `ApiError`，用 `e instanceof ApiError ? e.message : '兜底文案'` 取后端中文提示。
2. **401 统一处理集中在 `main.ts`**：拦截器通过 `onUnauthorized()` 回调通知，`main.ts` 注册的回调负责清登录态并跳登录页。不要在页面里各自处理 401。
3. **权限判定一律用权限码，不要硬编码角色**：`auth.hasPerm('user:list')` / `auth.hasAnyPerm(['a','b'])`。原因：学生干部发卷是给个人追加 `survey:create`，角色仍是 `ROLE_STUDENT`。
4. **前端权限只做隐显，不是安全边界**：真正的校验在后端 `@RequirePerm`。新页面必须在 `meta.perm` 里声明权限码，守卫和菜单都会读它。
5. **刷新后必须先 `fetchMe()`**：路由守卫用 `auth.loaded` 标记是否已向服务端确认过身份，不要只凭 localStorage 里的 token 判断已登录。
6. **改密返回新 token**：`auth.changePassword()` 内部会 `applyAuth(res)` 换新令牌，调用方不要再手动处理 token。
7. **密码规则要跟着系统配置走，不能写死**：长度与字母/数字/特殊字符开关来自 `useConfigStore().passwordRules`（后端 `security.password.*`，管理员在系统配置页改完即时生效）。前端提示文案用 `config.passwordRuleText`，校验用 `validatePassword(value, config.passwordRules)`。手机号 `^1[3-9]\d{9}$`、账号 4~64 位 `^[A-Za-z0-9_.-]+$` 仍写在 `src/utils/dict.ts`。
8. **类型跟后端同步**：新增接口先改 `src/types/api.ts`，再写 `src/api/*.ts`，最后写页面。不要用 `any` 绕过类型。
9. **作答态的题目渲染只有 `QuestionRenderer` 一处**：填写页与预览页都用它，只读态由 `readonly` prop 控制。编辑器是「出题」界面（题干与选项可编辑），不走这个组件。不要为填写与预览各复制一份题目模板，样式与交互会漂移。
10. **作答状态用 `AnswerInput`**（`optionValues` / `textValue` / `numberValue`），提交时直接把这个数组发给后端，不要在提交前再做一次格式转换。
11. **整数枚举不要传布尔**：后端 `anonymous` / `limitOnce` / `isRequired` 是 `Integer(1/0)`，`el-switch` 要写 `:active-value="1" :inactive-value="0"`，传 `true` 会 500。
12. **大厅跳转用 `shareCode` 而不是 id**：`/surveys/{id}` 需要 `update/preview` 权限，学生没有会 403；`/surveys/share/{code}` 只要求 `survey:fill`。填写页路由 `:key` 同时接受问卷 id 与分享码。
13. **`noUncheckedIndexedAccess` 已开启**：`arr[i]` 的类型是 `T | undefined`；`Record<number, T>` 的索引同理，模板里 `v-model="map[key]"` 会报类型错，要用 `answerFor(id)` 兜底再 `:model-value` + `@update:model-value`。
14. **E2E 脚本要自己清理数据**：新建的问卷必须在 finally 里删掉（借当前登录态 `fetch('/api/surveys/{id}?confirm=true', { method: 'DELETE' })`），否则每跑一次就往库里堆一份测试数据。
15. **ECharts 只按需注册**：`BaseChart.vue` 里已经 `echarts.use([BarChart, PieChart, ...])`。要新图表类型就显式加一行，绝不改成 `import * as echarts from 'echarts'`（会让统计页 chunk 从 510KB 涨到 1MB+）。
16. **图表 option 一律由 `utils/chart.ts` 的纯函数构造**：`barOption/pieOption/ratingOption/wordOption`，不要写在页面里。这些函数有单测（`chart.spec.ts`），页面里手写 option 就没法测。
17. **多选题不画饼图**：占比分母是「该题有效作答人数」，多选各选项占比之和会超过 100%。页面必须保留那条 el-alert 提示，删掉它等同于让用户以为系统算错了。
18. **下载文件用 `http.get(url, { responseType: 'blob' })`，不能用 `get<T>()`**：拦截器对无 `code` 字段的响应原样透传，拿到的是完整 `AxiosResponse`。文件名从 `content-disposition` 的 `filename*=UTF-8''` 解析（`decodeURIComponent`），后端做过字符替换与截断，前端不能自己拼。
19. **统计页不出现填写人**：`QuestionStatsVO` 的文本列表只有 `{text, submitTime}`。不要为了「方便老师」在前端加身份入口——匿名裁剪在后端出口，前端加了也拿不到数据，但会给下一个维护者错误的暗示。
20. **模板里的选项是纯文本数组 `string[]`**：`TemplateQuestionVO.options` 没有 `id`，也没有 `{id, content}` 结构。不要写 `options[0].content`，也不要把模板结构塞进 `QuestionRenderer`（它的 v-model 绑的是选项 id）。模板结构与作答是两件事。
21. **评分控件交互态不能用 `allow-half`**：后端 `score` 是 `TINYINT 1~5` 整数，4.5 会被 400 拒。只读展示 `ratingAvg` 时可以开 `allow-half`（这样 4.5 能显示半颗星）。
22. **`el-rate` 再点同一颗星会归零传 `0`**：后端不接受 0，所有评分入口必须 `if (!score) return` 静默忽略。
23. **`ratingAvg` 为 `null` 表示「暂无评分」，不是 0 分**：一律走 `ratingText(avg, count)`，不要写 `ratingAvg?.toFixed(1) ?? '0.0'`（新模板会显示成「0.0 分」，比「暂无评分」伤人）。
24. **使用模板成功后必须 `router.push` 到编辑器**：留在模板中心只弹个提示的话，用户看不到任何变化（模板本身没变，只是 `useCount + 1`），会以为没成功再点一次，于是多出一份草稿。
25. **站点名/公告一律从 `useConfigStore()` 读，不要在模板里写死「校园问卷系统」**：登录页品牌、侧边栏品牌、浏览器标题三处都必须跟随配置。公开配置在路由守卫里 `load()` 过，所以未登录页面也能拿到。
26. **配置项的值一律是字符串**：`BOOLEAN` 存 `'1'`/`'0'`，`NUMBER` 存数字字面量。`el-switch` 要写 `:model-value="edit[k] === '1'"` + `@update:model-value="v => edit[k] = v ? '1' : '0'"`，`v-model` 会把布尔写回字符串字段。
27. **密码项的掩码 `******` 是哨兵值不是真实值**：原样提交表示「不修改」，后端据此跳过该字段。不要在前端把它替换成空串，否则保存会把默认密码清空。
28. **日志页不要加删除/清理按钮**：日志只增不改是需求（FR-LOG-03），页面上没有删除入口是刻意的，不是没做完。要清理由 DBA 按保留期归档。
29. **评分平均分展示保留 1 位小数**：`验收标准.md` 2.3 与 SRS R-TPL-2 都要求 1 位，页面用 `toFixed(1)`、Excel 汇总行用 `%.1f`。后端接口 JSON 仍返回 2 位（给较精确的值避免二次舍入），**不要为了「统一」把接口也改成 1 位**，那样再取整一次会产生 `4.25 → 4.2 → 4.2` 与手工计算不符的偏差。
30. **E2E 断言不要假设演示账号在第 1 页**：用户表按 id 倒序分页，跑过压测或冒烟脚本后，压测账号会把 `teacher`/`student` 挤到后面几页，断言「第 1 页含张老师」会假失败。要么用搜索框定位（`input[placeholder="搜索学号/工号或姓名"]`），要么断言「搜到了」。同类问题：任何「列表里应该有 X」的断言都要先想清楚分页与排序。


## 已知事项

- Element Plus 是**按需引入**（`unplugin-vue-components` + `unplugin-auto-import`，配置在 `vite.config.ts`），别再写 `app.use(ElementPlus)` 或 `import 'element-plus/dist/index.css'`——那样会把已经降下来的首屏体积打回 1.29 MB。首屏 entry 现约 354 KB（gzip 131 KB）。
- 两个按需引入之后的硬约定：①写死的 PascalCase 图标自动生效，但**字符串形式的图标**（菜单数据里的 `{ icon: 'Memo' }`、`<component :is="'Expand'" />`）必须在 `src/main.ts` 的 `ICONS` 里手工登记，否则渲染空白；②中文语言包靠 `App.vue` 的 `<el-config-provider :locale="zhCn">`，去掉它分页会显示「10/page」、日期选择器变英文。
- **路由守卫里不许 await 网络请求**。`useConfigStore().load()` 是 fire-and-forget（`void ... .then(applyTitle)`）：本机 18ms 看不出来，公网隧道实测 3.1s，await 它等于给首屏白加 3 秒。站点名有兜底值、配置是响应式的，晚到不影响正确性。
- 登录页 `LoginView` 是**静态引入**（`component: LoginView`），其余 27 个路由继续 `() => import(...)`。理由同上：登录页是必经第一页，多一个动态 chunk 就多一个约 2 秒的往返。往 entry 里塞别的页面（尤其带 echarts 的统计页）是相反方向的错误。
- 不要给这个项目加 `manualChunks` 按包名分组。实测把 `element-plus` 归成一个 vendor chunk 后，它变成 957 KB 并被 `modulepreload` 强制进首屏 —— 按需引入懒加载的组件全被拽了回来。详见 `docs/design/首屏加载优化.md`。
- `src/auto-imports.d.ts` 与 `src/components.d.ts` 是插件自动生成的类型声明，**要入库**（否则别人 clone 下来 `vue-tsc` 报找不到 ElMessage 等类型）。改完 `vite.config.ts` 或新增组件后会自动重写，冲突时直接接受生成的版本。
- 按需引入后 `<el-table>` 的 `<template #default="{ row }">` 中 row 类型是 `DefaultRow`（`Record<string, any>`），传给声明为具体 VO 的函数会报 `TS2345`。写法是 `@click="fn((row as XxxVO))"`——**保留外层括号**，否则 `<` 可能被模板解析成标签。
- `playwright-core` 只是驱动本机已安装的 Chrome，不下载浏览器；换机器需设 `E2E_CHROME` 环境变量指向 chrome.exe。
- 本机只有 Windows PowerShell 5.1，`pwsh` 命令不存在；执行 `.ps1` 脚本要用 `& 'path\to\script.ps1'`，且脚本需带 UTF-8 BOM 才能正确解析中文。
- E2E 里 `page.evaluate(() => localStorage...)` 前必须先 `page.goto()` 到同源页面，否则 `about:blank` 会抛 `SecurityError`。
- 填空（TEXT）渲染成单行 `input`，多行文本（TEXTAREA）才是 `textarea`；E2E 找填空题要用 `input[placeholder="请输入"]`。
- 统计页（`SurveyStatsView`）是独立 chunk（约 510KB，含 echarts），只有打开统计页的人才下载它，登录页与填写页首屏不受影响。
- 问卷相关页面与设计说明见 `docs/design/前端问卷模块说明.md`，统计相关见 `docs/design/前端统计模块说明.md`，模板相关见 `docs/design/前端模板模块说明.md`，系统配置相关见 `docs/design/前端系统配置模块说明.md`。
- 站点名/公告改动后无需重新部署：`configStore.load(true)` 会强制刷新，侧边栏与标题立即跟随；刷新浏览器也会重新拉公开配置。
- 编辑器里的题目类名是 `.question-item`；`.question` 只存在于 `QuestionRenderer`（填写页/预览页）。E2E 断言编辑器里的题干与选项要读 `inputValue()`，`innerText` 取不到 `<input>` 的值。
- 条形图末端标签形如 `3 · 37.5%`，`barOption` 的 `grid.right` 必须留够宽度（现为 84px）。留 48px 时 ECharts 会按画布右界裁切，百分号被切掉，截图看着像 `37.5'`。
- E2E 断言「列表里没有 X」时**必须按行定位**（`.el-table__row` 的 `hasText`），不要断言整张表。数据库里可能残留别的记录，整表断言会随数据变化假失败——`e2e-survey` 的「匿名卷不回显」就踩过。
- 首屏性能的取舍与实测数据见 `docs/design/首屏加载优化.md`（含「试过但撤回」的 manualChunks、以及为什么瓶颈在隧道而非代码）。
