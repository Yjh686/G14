# 校园问卷信息调查管理系统（G14）

课程级项目：面向学生、教师、系统管理员的校园问卷平台，覆盖**问卷创建 → 发布分发 → 在线填写 → 答卷回收 → 统计分析**闭环，前后端分离。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.2.5 · Java 17 · MyBatis-Plus 3.5.7 · MySQL 8.0 · JWT (jjwt 0.11.5) · BCrypt |
| 前端 | Vue 3.5 · Vite 6 · Vue Router 4 · Pinia 3 · Element Plus 2 · Axios |
| 数据库 | MySQL 8.0，库名 `g14(1)` |
| 部署 | RESTful API，统一前缀 `/api`，Nginx + 前后端分离 |

## 目录结构

```text
/
├── AGENTS.md
├── README.md
├── docs/
│   ├── requirements/          # 需求规格说明书、功能清单、权限矩阵、验收标准
│   ├── api/                   # 接口文档
│   ├── design/                # 设计文档
│   └── test/                  # 测试用例
├── backend/                   # Spring Boot 工程
├── frontend/                  # Vue 工程
└── deploy/
    ├── sql/                   # 建表与初始化脚本
    └── test/                  # 接口冒烟脚本
```

## 快速开始

### 1. 准备数据库

MySQL 8.0 已启动，root 密码 `123456`。执行建表与初始化脚本：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 --default-character-set=utf8mb4 `
  -e "source D:/G14(1)/deploy/sql/01_schema_auth.sql" "g14(1)"
```

脚本会创建 8 张表（`sys_user` / `sys_role` / `sys_permission` / `sys_user_role` / `sys_role_permission` / `sys_user_permission` / `sys_login_log` / `sys_operation_log`），初始化 3 个角色、50 个权限码、3 个演示账号。

问卷模块相关的 3 张表（`survey` / `survey_question` / `survey_question_option`）在第二个脚本里：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 --default-character-set=utf8mb4 `
  -e "source D:/G14(1)/deploy/sql/02_schema_survey.sql" "g14(1)"
```

答题模块的 3 张表（`answer_sheet` / `answer_item` / `survey_draft`）在第三个脚本里：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 --default-character-set=utf8mb4 `
  -e "source D:/G14(1)/deploy/sql/03_schema_answer.sql" "g14(1)"
```

模板中心的 2 张表（`survey_template` / `template_rating`）在第四个脚本里：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 --default-character-set=utf8mb4 `
  -e "source D:/G14(1)/deploy/sql/04_schema_template.sql" "g14(1)"
```

需要清空冒烟与 E2E 留下的测试数据（只保留演示问卷与三个演示账号）时：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 --default-character-set=utf8mb4 `
  -e "source D:/G14(1)/deploy/sql/90_cleanup_test_data.sql" "g14(1)"
```

> 库名带括号，命令行里必须加引号；Windows 上库名实际存为小写 `g14(1)`，JDBC URL 里写 `G14(1)` 也能连通（MySQL 在 Windows 上不区分库名大小写）。

### 2. 启动后端

```powershell
$env:JAVA_HOME = "D:\huanjing\jdk-17"
& "D:\Maven\apache-maven-3.9.16\bin\mvn.cmd" -o -f backend\pom.xml -DskipTests compile
& "$env:JAVA_HOME\bin\java.exe" -jar backend\target\backend1-0.0.1-SNAPSHOT.jar
```

服务地址 `http://localhost:8080`，探活接口 `GET /api/health`。

接口文档（Swagger UI）在 `http://localhost:8080/swagger-ui.html`，OpenAPI JSON 在 `/v3/api-docs`，当前导出 **72 个接口、94 个 Schema**。点右上角 `Authorize` 填入登录拿到的 token 即可直接调接口；标注与分组的写法说明见 `docs/api/README.md`。

### 3. 验证接口

```powershell
# 认证 + 用户管理 + RBAC，66 条断言
& .\deploy\test\smoke_auth.ps1

# 问卷创建/题目/发布/分发，91 条断言
& .\deploy\test\smoke_survey.ps1

# 填卷/草稿/提交/答卷管理，166 条断言
& .\deploy\test\smoke_answer.ps1

# 统计口径/图表数据/Excel 导出，119 条断言
& .\deploy\test\smoke_stat.ps1

# 模板共享/审核/评分/使用，135 条断言
& .\deploy\test\smoke_template.ps1

# 登录/操作日志查询与导出，57 条断言
& .\deploy\test\smoke_log.ps1

# 公开配置/密码策略/内置项保护，77 条断言
& .\deploy\test\smoke_config.ps1
```

`smoke_auth.ps1` 覆盖 11 组共 66 条断言：健康检查、登录与令牌、越权拦截、列表筛选、新建用户、默认密码强制改密、注册审核流程、禁用踢下线、自我保护约束、权限追加撤销、参数校验。

`smoke_survey.ps1` 覆盖 12 组共 91 条断言：权限边界、新建问卷、题目差量与校验、发布与结构锁定、分享码访问、归属校验、暂停恢复结束、复制问卷、列表筛选、删除确认、幂等清理。

`smoke_answer.ps1` 覆盖 14 组共 166 条断言：问卷大厅与填写状态、草稿保存与断点续答、提交校验（必答/题型/选项/字数/评分/越界题目）、限填一次、我的填写记录、匿名裁剪、回收概览、标记无效与 `answer_count` 联动、权限与数据范围、实名可重复填写、暂停恢复结束未开始对填写的影响、清理。脚本每次自建问卷并在结束时删除，可重复执行。

`smoke_stat.ps1` 覆盖 15 组共 119 条断言：整卷/单题统计结构、单选与多选的人数与占比（含"多选占比之和 > 100%"这一正确语义）、文本题作答列表与高频词、评分题平均分与全档分布、标记无效后分母重算、Excel 两个 Sheet 的内容与文件名、匿名导出不含填写人列、零答卷不报错且明细页有占位行、权限与归属越权、清理。导出校验借助 `deploy/test/tools/read_xlsx.py`（openpyxl）回读 xlsx，能真正检查单元格内容而不只是看字节数。

`smoke_template.ps1` 覆盖 13 组共 135 条断言：共享为模板（无题目被拦、越权被拦）、待审核模板对外不可见（列表为 0、非本人详情 404）、审核队列权限、驳回必填原因、驳回→共享者可见→重新提交清空意见→再通过、评分一人一票可改不可删、模板中心强制只看 `APPROVED`（传 `status=PENDING` 无效）、三种排序、使用模板建卷（题型选项继承、无分享码、`use_count` 自增）、改新问卷不影响模板快照、下架后不可见/不可用/不可评、管理员也不能下架或删除他人模板、删除约束。脚本自带幂等清理，可重复执行。

`smoke_log.ps1` 覆盖 8 组共 57 条断言：登录日志与操作日志的分页/按账号/按关键词/按结果/按时间范围筛选、模块清单来自 `SELECT DISTINCT`、日志只读（`DELETE`/`PUT` 一律不被接受）、xlsx 导出回读表头、教师与学生越权 403、非法时间格式 400。

`smoke_config.ps1` 覆盖 10 组共 77 条断言：公开配置免登录可读且不含私有项、管理员全量配置（密码项打码、内置标记、NUMBER 类型）、改密码最小长度后注册/建用户/改密三处文案同步跟随、掩码 `******` 原样回传视为未修改、内置项不可删、自定义项新增→重复 409→删除→同键重建（复活墓碑行）、五类参数校验。脚本开头会把配置归一化，因此可重复执行。

七个脚本都可重复执行，全部 `PASS` 才算通过。

### 4. 启动前端

```powershell
cd frontend\G14
npm install
npm run dev          # http://localhost:5173
```

开发服务器已配置代理：`/api` → `http://localhost:8080`，前端无需改动后端地址，也不涉及跨域。

生产构建：

```powershell
npm run build        # 产物在 frontend/G14/dist
```

### 5. 前端 E2E 冒烟

后端(8080)与前端(5173)都启动后执行。六个脚本都用本机 Chrome（`playwright-core`，不下载浏览器）真实操作页面，可在 `package.json` 里分别执行：

```powershell
npm run e2e              # 认证与后台，23 条断言
npm run e2e:survey       # 建卷→发布→学生填写→答卷管理，41 条断言
npm run e2e:survey-edit  # 预览、复制为新草稿、草稿自动保存与断点续答，13 条断言
npm run e2e:stat         # 统计图表与 Excel 下载，34 条断言
npm run e2e:template     # 模板共享→审核→评分→使用→下架，57 条断言
npm run e2e:config       # 站点名/公告/密码规则改完立即生效，32 条断言
npm run e2e:all          # 依次跑完六个
```

| 脚本 | 覆盖内容 | 断言 |
| --- | --- | --- |
| `scripts/e2e-smoke.mjs` | 未登录重定向、错误密码提示、管理员登录、权限驱动菜单、用户列表真实数据、新建用户对话框、刷新保持登录、学生看不到后台菜单、学生越权访问被拦到 403、退出登录清理令牌、无控制台报错 | 23 |
| `scripts/e2e-survey.mjs` | 教师菜单按权限生成、新建问卷并编辑两道题、保存与发布、发布后结构锁定（按钮消失 + 输入框只读）、学生凭分享码进入、必答校验与题目高亮、提交回执、限填一次再进提示已提交、学生填写记录匿名不回显、学生访问问卷管理跳 403、教师答卷列表与详情里填写人被裁剪、教师列表可见分享码、无控制台报错 | 41 |
| `scripts/e2e-survey-edit.mjs` | 新建草稿、预览页只读渲染、复制为新草稿（标题带「（副本）」、保留题目）、5s 防抖草稿自动保存、刷新后选项恢复（断点续答）、无控制台报错 | 13 |
| `scripts/e2e-stat.mjs` | 造 5 种题型、5 份已知分布答卷后校验统计页：概览卡数字、5 题导航、单选柱图+饼图、多选只画柱并有「占比之和会超过 100%」提示、文本高频词与作答列表、评分平均分 4.20、Excel 下载（文件名含问卷标题）、学生访问统计被拦到 403、无控制台报错 | 34 |
| `scripts/e2e-template.mjs` | 教师共享为模板（对话框自动带出模板名）、待审模板对教师和学生都不可见、管理员审核队列查看结构抽屉、驳回不填原因被拦、驳回→共享者看到意见→重新提交→通过、学生评分与改分（4.0→5.0 且人数不变）、教师使用模板跳转到编辑器并验证题目与选项带出、改新问卷后模板快照不变、下架后模板中心不可见、学生进审核页与我共享页被拦到 403、无控制台报错 | 57 |
| `scripts/e2e-config.mjs` | 管理员改站点名后侧边栏品牌、浏览器标题、未登录登录页三处立即跟随、公告渲染与清空、密码规则改 12 位后注册页提示与前端拦截同步、内置项删除被拒、自定义项增删、公开配置预览抽屉不含默认密码、教师/学生越权被拦到 403、无控制台报错 | 32 |

五个含数据写入的脚本（survey / survey-edit / stat / template / config）结束时都会还原自己改动的数据（走的是后端软删除接口，所以 `survey` / `survey_template` 表里会留下 `deleted=1` 的行，但列表、大厅、统计里都看不到）。表因此会随 E2E 次数缓慢增长，答辩前可执行 `deploy/sql/90_cleanup_test_data.sql` 清理。

## 演示账号

| 账号 | 密码 | 角色 | 权限数 |
| --- | --- | --- | --- |
| admin | Admin@123 | 系统管理员 | 50 |
| teacher | Teacher@123 | 教师 | 23 |
| student | Student@123 | 学生 | 10 |

> 这三个是初始化脚本写入的固定账号，冒烟脚本不会改动它们的密码。若某次测试手动改过而忘记，用 admin 调 `POST /api/users/3/reset-password` 重置，或重跑建表脚本。

## 角色与权限模型

三个内置角色，权限码共 50 个：

- **系统管理员（ROLE_ADMIN）**：用户/角色管理、日志、模板审核、系统配置、全量数据管理。
- **教师（ROLE_TEACHER）**：创建/编辑/发布自己的问卷，查看自己问卷的答卷、统计、导出，共享模板。
- **学生（ROLE_STUDENT）**：填写问卷、保存草稿、断点续答、查看自己的填写记录、浏览和评分模板。

「问卷发布者」不是独立角色，而是权限：`survey:create` + `survey:publish:self`，默认授予 ADMIN 和 TEACHER。学生干部需要发卷时，由管理员给该学生**追加** `survey:create` 权限（`sys_user_permission`），不新增角色。

实际生效权限 = 角色权限 ∪ 用户追加权限（只加不减）。

## 已完成

- [x] 需求拆解：SRS、功能清单、权限矩阵、验收标准（`docs/requirements/`）
- [x] 数据库建表与初始化脚本（`deploy/sql/01_schema_auth.sql`）
- [x] 认证模块：登录/退出/注册/改密/个人信息，JWT + token_version 强制下线
- [x] 用户管理：增删改查、启用禁用、注册审核、重置密码，含自我保护约束
- [x] RBAC：角色/权限查询、追加与撤销附加权限
- [x] 登录日志、操作日志落库
- [x] 接口冒烟测试脚本，66 条断言全通过
- [x] 接口文档（`docs/api/认证与用户管理接口.md`）
- [x] 问卷模块后端：建表、问卷与题目 CRUD、差量保存题目、发布/暂停/恢复/结束状态机、8 位分享码与分享落地接口、复制为新草稿
- [x] 问卷模块冒烟脚本，91 条断言全通过
- [x] 接口文档（`docs/api/问卷管理接口.md`）
- [x] 前端脚手架升级到稳定版（Vue 3.5 / Router 4 / Pinia 3 / Element Plus 2 / Vite 6）
- [x] 前端认证闭环：登录、注册、强制改密、退出、刷新保持登录、401 自动跳登录
- [x] 前端后台模块：主布局与权限菜单、工作台、个人资料、用户管理、注册审核、权限配置、403/404
- [x] 前端 E2E 冒烟脚本，23 条断言全通过
- [x] 答题模块后端：问卷大厅、草稿断点续答、提交校验、限填一次（生成列唯一索引 + 幂等）、答卷列表/详情、匿名裁剪、标记无效与恢复、回收概览
- [x] 答题模块冒烟脚本，166 条断言全通过
- [x] 接口文档（`docs/api/答题与答卷接口.md`）
- [x] 前端问卷业务页面：我的问卷列表（筛选/发布/暂停/恢复/结束/复制/删除）、问卷编辑器（五种题型、选项增删、上下排序、匿名与限填设置、起止时间、分享码展示）、预览页、填写页（分享码直达、5s 防抖草稿、断点续答、必答高亮、提交回执）、我的填写记录、答卷管理（回收概览 + 列表 + 详情 + 标记无效/恢复/删除）
- [x] 前端 E2E 冒烟扩充到 3 个脚本 77 条断言全通过
- [x] 文档（`docs/design/前端问卷模块说明.md`）
- [x] 统计模块后端：整卷统计与单题统计，选择题人数/占比、文本题作答列表与高频词、评分题平均分与全档分布，全部只算有效答卷
- [x] 导出模块后端：EasyExcel 流式导出 xlsx（明细 + 汇总两个 Sheet），匿名卷不含填写人列，文件名带问卷标题与时间戳
- [x] 统计导出冒烟脚本，119 条断言全通过
- [x] 接口文档（`docs/api/统计与导出接口.md`）
- [x] 前端统计页面：ECharts 按需注册（路由懒加载，独立 chunk），单选柱图+饼图、多选柱图与占比提示、文本高频词与作答列表、评分平均分、空数据占位；答卷管理页加「导出 Excel」真下载
- [x] 前端 E2E 冒烟扩充到 4 个脚本 111 条断言全通过
- [x] 文档（`docs/design/前端统计模块说明.md`）
- [x] 模板中心后端：共享为模板（结构快照存 JSON）、待审/通过/驳回/下架状态机、审核队列、一人一票可改不可删的评分、使用模板复制为草稿
- [x] 模板中心冒烟脚本，135 条断言全通过
- [x] 接口文档（`docs/api/模板中心接口.md`）
- [x] 前端模板中心页面：模板中心（卡片 + 星级评分 + 使用模板）、模板详情（结构只读展示）、我共享的模板（重新提交/下架/删除）、模板审核（内容抽屉 + 通过/驳回）、共享为模板对话框
- [x] 前端 E2E 冒烟扩充到 5 个脚本 168 条断言全通过
- [x] 文档（`docs/design/前端模板模块说明.md`）
- [x] 日志模块后端：登录日志与操作日志查询（分页/账号/模块/动作/结果/时间范围）、模块清单来自 `SELECT DISTINCT`、日志只读（无 PUT/DELETE）、xlsx 导出
- [x] 前端日志查询页：登录/操作两个 Tab、时间范围筛选、导出按钮、页面上刻意没有任何删除按钮
- [x] 日志冒烟脚本，57 条断言全通过
- [x] 系统配置后端：`sys_config` 键值表、公开配置免登录读取、密码策略与默认密码配置化、内置项保护、墓碑行复活
- [x] 系统配置冒烟脚本，77 条断言全通过
- [x] 接口文档（`docs/api/系统配置接口.md`）
- [x] 前端系统配置页：分组编辑、自定义项增删、公开配置预览抽屉；站点名/公告/密码规则接入登录页、侧边栏与浏览器标题
- [x] 前端 E2E 冒烟扩充到 6 个脚本 200 条断言全通过
- [x] 文档（`docs/design/前端系统配置模块说明.md`）
- [x] 用户 Excel 批量导入：模板下载、逐行小事务、同文件内重复学号检出、错误精确到行号、明确拒绝导入管理员
- [x] 部署产物：`deploy/docker-compose.yml` + 后端/前端 Dockerfile + nginx 配置（静态托管与 `/api` 反代同源）
- [x] 压测脚本（`deploy/test/loadtest.py`）与 100 并发 5 分钟报告，SRS 六条通过条件逐条取证
- [x] 长稳测试脚本（`deploy/test/soak.py`）：按小时切分故障率、JVM GC 采样（`jstat`，不 STW）
- [x] 接口文档（springdoc-openapi）：`/swagger-ui.html` 可交互，72 个接口按业务域分组
- [x] 数据库设计文档（`docs/design/数据库设计.md`）：17 张表逐表说明、24 条索引对应真实查询、11 条关键决策
- [x] 测试用例与执行结果（`docs/test/测试用例与执行结果.md`）：P0 逐条对照 + 16 个缺陷的根因与修法
- [x] 部署文档（`docs/deploy/部署文档.md`）：Docker 一键启动 + 本机手动启动 + 11 条生产注意事项
- [x] 答辩材料：16 页 PPT（`docs/答辩PPT.pptx`）、演示脚本（对应 AC-01~03）、12 张页面截图

## 待完成

- [ ] 24 小时长稳测试跑满并定稿报告（本次运行中：2026-11-09 12:09 起跑，2026-11-10 12:09 结束，
      结束后 `deploy/test/soak.py` 会自动写 `docs/test/长稳测试报告.md` 与 `soak-result.json`。
      期间**不能重启后端**，否则 JVM 采样与「无重启」这条判定都失去意义）
- [ ] docker daemon 启动后实际 `build` / `up` 验证 compose 编排。**本机没有安装 Docker Desktop**
      （只有 docker CLI 29.7.2，`docker info` 连不上 `npipe:////./pipe/dockerDesktopLinuxEngine`），
      目前只做了 `docker compose config --quiet` 静态语法校验，Dockerfile 与 nginx 配置未经真实构建。
      这是已知的、已写进部署文档与测试文档的限制
- [ ] 指标二（长期运行故障率 < 0.1%）的第二种取证方式：Nginx access log 按 3 天实际使用统计
      （需要真实流量，课程环境下无法提供，报告中已说明这一条只有 24h 压测证据）

## 关键设计决策

| 决策 | 理由 |
| --- | --- |
| 响应 `code` 与 HTTP 状态码一致 | 前端和网关只需判断一处，避免「HTTP 200 + body 里藏错误码」 |
| JWT 只放 `userId/username/ver`，不放角色权限 | 权限改动要立即生效；令牌体积小；每次请求从缓存加载真实权限 |
| 改密/禁用/删除 → `token_version + 1` | JWT 无状态也能实现「立即踢下线」，无需维护黑名单 |
| 登录失败文案统一为「账号或密码错误」 | 防止通过报错枚举学号/工号是否存在 |
| 自助注册必须管理员审核 | 校园场景账号可控，避免外部人员随意注册 |
| 权限 = 角色权限 ∪ 用户追加权限 | 学生干部发卷只追加一条权限，不污染角色体系 |
| 日志表只增不改，不加 `deleted` | 审计日志被逻辑删除就失去意义 |
| 分页 `maxLimit = 500` + 防全表更新拦截器 | 防止误操作拖库 |
| 问卷发布后锁定题目结构 | 已收答卷按题目 ID 存储，改结构会让答卷与题目错位、统计失真；要改就复制为新草稿 |
| 整卷差量保存题目（有 id 更新 / 无 id 新增 / 多余删除） | 拖拽排序一次改动多题，逐题调用既慢又无法保证顺序一致；整卷提交天然是原子的 |
| `fillable` 由后端算好返回 | 前端再算一遍时间判断迟早与后端不一致；截止时间实时判断，不依赖定时任务 |
| 分享码用去混淆字符集（无 0/O/1/I/L） | 校园场景常靠口头传达与手抄，混淆字符会直接产生投诉 |
| 「限填一次」用生成列 + 唯一索引，不靠代码判断 | `limit_once=0` 的问卷要允许重复填，直接给 `(survey_id, user_id)` 建唯一索引会让这类问卷不可用；生成列把「要不要占槽」下推到数据库，唯一索引允许多 NULL，一条索引同时满足两种语义，并发防线在 DB |
| 匿名仍然存 `user_id`，但只从一个出口返回 | 不存 `user_id` 就没法做「限填一次」和「我的填写记录」；用 DTO 裁剪而非前端隐藏，是因为前端隐藏挡不住直接调接口 |
| `answer_sheet` 不记 IP / UA | 匿名问卷一旦记 IP，与 Nginx 访问日志交叉比对就能反推身份 |
| 作答选项存 JSON 数组（`answer_item.option_values`） | 拆成选项表要多插 N 行、统计要 GROUP BY 两次；JSON 让一题恒为一行，MySQL 8 的 `JSON_CONTAINS` / `JSON_LENGTH` 够用 |
| 草稿宽进（截断、丢弃非法题目），提交严出 | 草稿的语义就是「没答完」，此时报错会打断填写；提交才是数据质量的关口 |
| 问卷编辑器整卷提交题目，不逐题调接口 | 与后端差量接口一一对应；拖拽排序一次改动多题，逐题调用既慢也无法保证顺序 |
| 填写页与预览页共用 `QuestionRenderer` | 学生看到的题目外观必须与预览一致，复制两份样式迟早会漂移；只读态由 `readonly` prop 控制 |
| 填写页路由参数 `:key` 同时接受问卷 id 与分享码 | 分享链接 `/fill/Q3PTC5RC` 与大厅跳转 `/fill/1` 共用一个页面，省掉一套几乎重复的视图；分享码走只要求 `survey:fill` 的接口，学生才有权限 |
| 大厅卡片跳转用 `shareCode` 而非 id | `/surveys/{id}` 要求 `update/preview` 权限，学生没这个权限会 403；`/surveys/share/{code}` 才是填写者的入口 |
| 前端权限只做隐显，不放行任何请求 | 路由守卫拦的是「不该看到的页面」，接口安全完全靠后端 `@RequirePerm` + 数据级归属校验 |
| `survey.answer_count` 语义 = 有效答卷数 | 「交了多少份」和「算进统计多少份」是两个数，混用会导致统计页与概览页对不上；提交+1、无效-1、恢复+1、删除有效-1 四条路径成对修改 |
| 统计只算有效答卷，分母是该题有效作答人数 | 无效卷被排除后分母要同步变小，否则占比之和凑不满 1；多选题因此占比之和会超过 100%，这是正确语义而非 bug，前端不画饼图并显式提示 |
| 统计在内存聚合，不做预聚合表 | 预聚合会引入「答卷提交了但统计没变」的不一致窗口，还得在提交/无效/恢复/删除四条路径上触发重算；课程级数据量下现算一次 < 100ms |
| 文本高频词用 2-gram 滑窗，不引入分词库 | jieba/ansj 会让课程项目陷入装依赖、调词典、版本冲突；词频只用于快速看倾向，2-gram 零依赖且效果可接受，接口注释与页面都写明「不替代人工阅读」 |
| ECharts 按需注册 `BarChart/PieChart` | 全量引入会让统计页 chunk 翻倍到 1MB+；按需注册 510KB 且路由懒加载，登录页与填写页完全不受影响 |
| 导出是全项目唯一不返回 `{code,msg,data}` 的接口 | 浏览器需要二进制流才能触发下载；因此所有校验（权限、归属、问卷存在）必须在写流之前完成，写流后出错已无法改状态码 |
| Excel 明细只导出有效答卷 | 与统计口径一致，否则会出现「表里 10 行、页面说 8 份」的对不上；无效答卷只是不计入统计，数据仍在库里，恢复后重新导出即可 |
| 模板是**快照**，不是指向问卷的引用 | 存 `source_survey_id` 实时读会让「老师改问卷→别人用的模板跟着变」、删问卷→模板指向不存在的题；快照换来「看到什么就是什么」，模板里没有 question_id / option_id |
| 模板结构与评分不用「选项 ID」而用文本 | 选项 ID 在来源问卷改删后会悬空；模板只做整块读整块写，没有跨模板按题查询，所以 JSON 一列足够，不拆 `template_question` / `template_option` |
| `rating_sum + rating_count` 存两个数，`rating_avg` 用生成列 | 改分时 `sum + (new - old)` 即可，不必 `avg * count` 反算（反算会引入浮点漂移）；生成列还能建索引，直接支持按评分排序 |
| 模板默认排序不加 `rating_avg IS NOT NULL` | MySQL 里 `NULL` 最小，`DESC` 自然排最后；加了过滤会让没人评分的新模板永远等不到第一个评分 |
| 提交审核只接受 `APPROVED` / `REJECTED` | 允许传回 `PENDING` 等于「撤销审核」，会让已经被别人用过的模板凭空回到待审；撤销的正确姿势是下架 |
| 待审核模板对外一律 404 而非 403 | 403 等于告诉对方「这里确实有个模板，只是不给你看」，存在性就泄露了 |
| 模板没有 `*:all` 全量权限 | 管理员能审核能看全部待审内容，但不能下架或删除别人的模板——审核是职责，替人撤下已发布的模板不是 |
| 使用模板后必跳编辑器 | 留在模板中心弹个成功提示的话，用户看不到任何变化（模板本身没变，只是 `useCount + 1`），会以为没成功再点一次，于是多出一份草稿 |

## 硬性指标

| 指标 | 目标 | 口径 |
| --- | --- | --- |
| 高并发答卷响应时间 | < 3 秒 | 100 并发，提交接口 P95 < 3000ms、平均 < 1500ms，混合比例 查询 20% / 草稿 10% / 提交 70%，ramp-up 10s，持续 5 分钟 |
| 长期运行故障率 | < 0.1% | (5xx + 超时 + 连接拒绝) / 总请求；4xx 业务错误不计入；需 24h 压测 + 实际使用日志两种方式取证 |

## 文档索引

| 文档 | 路径 |
| --- | --- |
| 需求规格说明书 | `docs/requirements/SRS-需求规格说明书.md` |
| 功能清单 | `docs/requirements/功能清单.md` |
| 权限矩阵 | `docs/requirements/权限矩阵.md` |
| 验收标准 | `docs/requirements/验收标准.md` |
| 接口文档（认证/用户/RBAC） | `docs/api/认证与用户管理接口.md` |
| 接口文档（问卷） | `docs/api/问卷管理接口.md` |
| 接口文档（答题与答卷） | `docs/api/答题与答卷接口.md` |
| 接口文档（统计与导出） | `docs/api/统计与导出接口.md` |
| 接口文档（模板中心） | `docs/api/模板中心接口.md` |
| 接口文档（系统配置） | `docs/api/系统配置接口.md` |
| 接口文档（Swagger / OpenAPI） | `http://localhost:8080/swagger-ui.html`（运行后访问） |
| 数据库设计 | `docs/design/数据库设计.md` |
| 压测报告 | `docs/test/压测报告.md` |
| 长稳测试报告 | `docs/test/长稳测试报告.md` |
| 测试用例与执行结果 | `docs/test/测试用例与执行结果.md` |
| 部署文档 | `docs/deploy/部署文档.md` |
| 答辩 PPT | `docs/答辩PPT.pptx` |
| 答辩演示脚本 | `docs/答辩演示脚本.md` |
| 答辩素材截图 | `docs/答辩素材/` |
| 前端架构说明 | `docs/design/前端架构说明.md` |
| 前端问卷模块说明 | `docs/design/前端问卷模块说明.md` |
| 前端统计模块说明 | `docs/design/前端统计模块说明.md` |
| 前端模板模块说明 | `docs/design/前端模板模块说明.md` |
| 前端系统配置模块说明 | `docs/design/前端系统配置模块说明.md` |
| 前端开发约定 | `frontend/AGENTS.md` |
