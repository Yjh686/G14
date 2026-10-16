# 前后端联调说明（认证模块）

本文档说明如何把 `frontend`（Vue 3 + Vite）接到真实后端（Spring Boot，`com.g14.g14`），
并跑通登录、强制改密、管理员账号管理三条主链路。

> 后端源码位于 `D:\idea\javacode\G14`（同一台机器上的独立工程）。
> 前端仓库内的 `mock/` 是早期照着假接口写的内存后端，**接入真实后端后不要再用**。

---

## 1. 启动顺序

### 1.1 启动后端（必须先在跑）

后端依赖 MySQL，需先建库建表：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS class_survey DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p class_survey < src/main/resources/db/schema-mysql.sql
```

然后启动（二选一）：

- **IDEA**：直接运行 `G14Application`（推荐，最省事）。
- **命令行**：本机 `mvn spring-boot:run` 离线不可用（pom 里 spring-boot-maven-plugin 配了
  `<skip>true</skip>` 且插件依赖未缓存），改用导出 classpath 后直接 `java -cp` 启动：

  ```powershell
  cd D:\idea\javacode\G14
  $env:JAVA_HOME='D:\huanjing\jdk-17'   # 注意：JDK 25 不支持 source/target 1.8
  mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  $cp = (Get-Content target\cp.txt -Raw).Trim()
  & "$env:JAVA_HOME\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "target\classes;$cp" com.g14.g14.G14Application
  ```

启动成功后监听 **8080**。首次启动会自动创建引导管理员 `admin / Admin@123`。

### 1.2 初始化演示账号（可选，但推荐）

用管理员 API 开通三种角色的演示账号（等价于在页面上点「开通账号」）：

```bash
cd frontend
npm run seed
# 或指定代理地址： node tools/seed-demo.mjs http://localhost:5173/api
```

账号若已存在会跳过，脚本可重复执行。加 `--reset` 可把已存在账号的密码重置回初始密码：

```bash
npm run seed -- --reset
```

> `--reset` 走的是管理员的「重置密码」接口，且**必须传 `newPassword: null`**。
> 传显式密码会套用完整强度策略，而 `Init@1234` 本身就在后端弱口令黑名单里，会被拒（400/40001）；
> 传 `null` 才走「恢复系统初始密码」分支（只校验长度）。

### 1.2.1 造班级 / 问卷演示数据（可选）

班级与问卷模块尚未交付（当前只有权限校验层 + 演示端点）。若想验证**数据级**越权拦截
（学生只能看本班班级与已发布问卷），需要手工造数据：

```bash
mysql -u root -p class_survey < tools/seed-demo.sql
```

脚本用 `INSERT ... WHERE NOT EXISTS` 保证幂等，可重复执行。它建出：
班级 1（老师甲，学生一已加入）、班级 2（老师乙）、班级 1 下的已发布问卷与草稿问卷各一张。
`npm run test:e2e` 会自动检测这些数据是否存在；不存在则跳过数据级断言而不是误报失败。

### 1.3 启动前端

```bash
cd frontend
npm install     # 首次
npm run dev     # http://localhost:5173
```

浏览器打开 <http://localhost:5173> 即可看到登录页。

---

## 2. 演示账号

| 角色 | 账号 | 密码 | 说明 |
|---|---|---|---|
| 管理员 | `admin` | `Admin@123` | 后端首次启动自动创建，无需改密 |
| 老师 | `T1001` | `Init@1234` | 也可用手机号 `13800000001` 登录 |
| 老师 | `T1002` | `Init@1234` | 也可用手机号 `13800000002` 登录 |
| 学生 | `2021001` | `Init@1234` | 学号登录 |
| 学生 | `2021002` | `Init@1234` | 学号登录 |

除 `admin` 外，其余账号首次登录都会返回 `mustChangePassword=true`，
前端会自动跳转到「修改初始密码」页，改密前访问任何业务接口都会被后端拦（403 / 40302）。

---

## 3. 联调链路

```
浏览器 5173  ──/api/*──▶  Vite dev server  ──proxy──▶  Spring Boot 8080  ──▶  MySQL
```

- 前端 `axios` 的 `baseURL` 是 `/api`，请求同源发给 Vite；
- Vite 把 `/api` 前缀原样转发到后端（`changeOrigin: true`），浏览器侧不产生跨域；
- 因此**不需要后端配置 CORS**。

代理目标由 `frontend/.env.development` 的 `VITE_PROXY_TARGET` 控制，默认 `http://localhost:8080`。

> ⚠️ 曾经的真实缺陷：`vite.config.js` 里写的是 `process.env.VITE_PROXY_TARGET`。
> Vite **不会**把 `.env` 文件注入到配置文件可见的 `process.env`，该表达式恒为 `undefined`，
> 于是 `.env.development` 里的配置被静默忽略、永远回落到硬编码默认值。
> 现已改用 `loadEnv(mode, process.cwd(), 'VITE_')` 读取。

---

## 4. 前端为适配后端所做的契约转换

前端页面的字段命名沿用了早期的 mock 风格（`name` / `password` / `page` / `size`），
而后端契约不同。**所有转换都收敛在 `src/api/auth.js` 与 `src/utils/user.js` 两处**，
页面组件不感知差异。

| 项 | 前端页面模型 | 后端契约 | 转换位置 |
|---|---|---|---|
| 改密方法 | `PUT /auth/password` | `POST /auth/password` | `src/api/auth.js:42` |
| 重置密码方法 | `PUT /admin/users/{id}/password/reset` | `POST /admin/users/{id}/password/reset` | `src/api/auth.js:67` |
| 分页参数 | `page` / `size` | `pageNum` / `pageSize` | `src/api/auth.js:82-83` |
| 姓名字段 | `name` | `realName` | `src/utils/user.js:normalizeUser` |
| 开通账号 | `{name, password}` | `{realName, initialPassword}` | `src/api/auth.js:56-62` |
| 启用/禁用 | 「删除」 | `PUT /admin/users/{id}/status?enabled=` | `src/api/auth.js:93` |

### 4.1 改密后必须换发新令牌

后端改密会让 `token_version + 1`，**旧令牌立即失效**，同时在响应里回签了一个新 token
（`data.token`）。前端 `stores/auth.js` 的 `changePassword()` 必须把新 token 落盘，
否则改密后的下一个请求就会 401 / 40103。

### 4.2 角色由后端识别，前端不提交 role

登录请求体只有 `{account, password}`。登录页的身份 Tab 仅用于输入引导，
**不参与鉴权**；后端按账号查库得出真实角色。

对应地，`src/utils/validate.js` 的账号校验是**宽松的**：只拦空值、超长、非法字符，
不按身份限制字符集。

> ⚠️ 曾经的真实缺陷：旧校验器要求「老师 = 纯数字工号或手机号」，
> 而真实工号形如 `T1001`，导致老师在登录页选「老师」后**被前端拦死、请求根本发不出去**。
> 现已移除按角色限制字符集的逻辑，并在 `tools/probe-validator.mjs` 中加了回归断言。

### 4.3 403 的分支处理

`src/api/request.js` 对 403 做了细分，不能一律当作「无权限」：

| 业务码 | 含义 | 前端行为 |
|---|---|---|
| 40300 | 权限不足 | 仅提示，不跳转 |
| 40301 | 账号已禁用 | 清登录态 → 回登录页 |
| 40302 | 必须修改初始密码 | 跳「修改初始密码」页 |
| 40303 | 账号已锁定 | 清登录态 → 回登录页 |

---

## 5. 自检命令

前四个脚本只依赖 Node（`fetch`），可在 CI 或本地直接跑；`test:browser` 需要本机装有
Chrome 或 Edge（用 CDP 驱动无头浏览器真实点击），会把登录后的截图存到
`tools/artifacts/browser-after-login.png` 作为证据。

```bash
# 1) 契约联调：45 条断言，逐条对齐 src/api/auth.js 的 URL/方法/请求体
#    前置：后端 8080 在跑 + npm run dev 的 5173 在跑 + 已跑 npm run seed
npm run test:contract

# 2) 端到端场景：39 条断言，按「用户真实走一遍」的顺序串联
#    学生首次登录 → 强制改密 → 换新令牌 → 数据级越权拦截
#    老师手机号登录 → 管理员全权限 → 禁用踢下线 → 登出
npm run test:e2e

# 3) 真实浏览器点击：用无头 Chrome (CDP) 真填表、真点「登录」
#    验证 Vue 挂载、路由守卫、Pinia 落盘、改密换令牌、首页渲染
#    前置：同上；需本机装有 Chrome 或 Edge
npm run test:browser

# 4) 校验器回归：确保后端真实账号都能通过前端登录校验
npm run test:validator

# 5) 生产构建
npm run build
```

**两个测试脚本都会真实改密码**（这正是要验证的业务流程），因此它们**不是幂等的** ——
第二次直接跑会因为「旧密码已失效」而整片 40104/40101，看起来像回归，其实是测试状态脏了。
为此两者都在开头调用 `tools/demo-fixture.mjs` 的 `resetDemoAccounts()`，
通过管理员 API 把演示账号复位到「首次开通」状态（密码回 `Init@1234`、
`mustChangePassword=1`、`status=1`、`tokenVersion+1`）。

所以只要先跑过一次 `npm run seed` 建好账号，之后 `test:contract` 与 `test:e2e`
可以任意顺序、任意次数重复执行，结果稳定。已验证连续两次 `test:e2e` 均 39 PASS / 0 FAIL。

账号若被测试禁用过，`resetDemoAccounts()` 也会自动重新启用。

---

## 6. 常见问题

**登录后立刻被踢回登录页**
浏览器 localStorage 里可能残留旧 token。清掉 `access_token` / `user_info` 再试。

**改密后所有请求都 40103**
说明前端没有保存改密响应里的新 token。检查 `stores/auth.js` 的 `changePassword()`。

**接口 404 或代理连不上**
确认后端在 8080、`npm run dev` 在 5173，且 `frontend/.env.development` 的
`VITE_PROXY_TARGET` 指向后端。改过 `.env` 需重启 dev server。

**老师用 T1001 登录，表单提示「请使用工号或手机号登录」**
前端校验器是旧版本的按角色卡格式逻辑，已修复；确认 `src/utils/validate.js` 是当前版本。

**`npm run mock` / `npm run dev:all` 还能用吗**
能跑，但那是假后端（明文密码、监听 8080，会与真实后端抢端口）。
接入真实后端后请只用 `npm run dev`。
