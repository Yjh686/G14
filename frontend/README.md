# 班级问卷系统 · 前端

Vue 3 + Vite + Pinia + Vue Router + Element Plus，已接入 Spring Boot 后端
（认证与角色权限模块）。

- **后端**：`D:\idea\javacode\G14`（Spring Boot 2.6.13 + MyBatis + MySQL，端口 8080）
- **联调说明**：见 [`INTEGRATION.md`](./INTEGRATION.md) —— 契约差异、改造点、自检命令都在那里

## 快速开始

```bash
# 1) 先启动后端（8080）。IDEA 里直接运行 G14Application 即可
#    命令行方式见后端仓库说明（mvn spring-boot:run 在本机离线不可用）

# 2) 装依赖（首次）
npm install

# 3) 初始化演示账号（管理员 API 开通，可重复执行）
npm run seed

# 4) 启动前端
npm run dev          # → http://localhost:5173
```

浏览器打开 <http://localhost:5173>，用演示账号登录。

## 演示账号

| 角色 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 管理员 | `admin` | `Admin@123` | 引导账号，已初始化，无需改密 |
| 老师 | `T1001` | `Init@1234` | 也可用手机号 `13800000001` 登录 |
| 老师 | `T1002` | `Init@1234` | 也可用手机号 `13800000002` 登录 |
| 学生 | `2021001` | `Init@1234` | 学号登录 |
| 学生 | `2021002` | `Init@1234` | 学号登录 |

> 除管理员外，**首次登录都会强制跳转「修改初始密码」页**，改完才能进业务页面。
> 改密成功后后端会签发新 token（旧 token 因 `tokenVersion+1` 立即失效），
> 前端 `stores/auth.js` 已自动替换本地令牌。

账号被测试脚本改过密码时，用 `npm run seed -- --reset` 复位。

## 目录结构

```
src/
  api/
    request.js        axios 实例：注入 token、按 HTTP 状态码与业务码分流处理
    auth.js           前端 UI 模型 → 后端 DTO 的唯一转换层
  stores/auth.js      Pinia：登录态、令牌持久化、改密换令牌
  router/index.js     路由守卫：登录拦截、强制改密拦截、角色路由
  utils/
    role.js           角色常量（与后端字符串枚举逐字对齐）
    validate.js       账号/密码校验器（按账号格式自动识别角色）
    user.js           normalizeUser：后端 realName ↔ 页面 name
  views/
    Login.vue         统一登录页（角色 Tab 仅作 UI 提示，不提交给后端）
    ForcePassword.vue 首次登录强制改密
    ChangePassword.vue
    Profile.vue       个人中心
    Dashboard.vue
    admin/UserManage.vue  管理员账号管理
  layouts/MainLayout.vue
```

## 自检

```bash
npm run test:contract   # 契约联调：45 条断言，逐条对齐 src/api/auth.js
npm run test:e2e        # 端到端场景：39 条断言，按用户真实操作顺序串联
npm run test:browser    # 真实浏览器点击：18 条断言（无头 Chrome + CDP）
npm run test:validator  # 登录校验器回归
npm run build           # 生产构建
```

前置条件：后端 8080 在跑、`npm run dev` 的 5173 在跑、已跑过 `npm run seed`。
`test:contract` / `test:e2e` / `test:browser` 都会真实改密码，但开头会调
`tools/demo-fixture.mjs` 自动复位演示账号，因此**可任意顺序重复执行**。

## 关于 mock

`mock/server.js`（`npm run mock` / `npm run dev:all`）是早期照着写页面的假后端，
监听 9090（已避开真实后端 8080）。它密码明文、字段名与真实后端不同，
**接入真实后端后请只用 `npm run dev`**。
