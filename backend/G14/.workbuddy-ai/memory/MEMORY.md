# 项目长期记忆 —— G14 班级问卷系统

## 仓库与路径（重要）

| 角色 | 路径 | 说明 |
|---|---|---|
| 后端 | `D:\idea\javacode\G14` | 本仓库。Spring Boot 2.6.13 + MyBatis + MySQL，包 `com.g14.g14.login` |
| 前端 | `D:\G14\G14\frontend` | **另一个独立 git 仓库**，不是本仓库的子目录。Vue 3 + Vite + Pinia + Element Plus |

- 用户明确要求：后端代码写在「G14 里面的 Login 包」下 → 即 `com.g14.g14.login`（用小写，符合 Java 规范）。
- 用户明确要求：数据库名 `G14`，账号 `root` / 密码 `123456`。
  注意前端 `tools/unlock-accounts.mjs` 默认连 `class_survey`，需用 `MYSQL_DB=G14` 覆盖。

## 前后端契约（不可随意变更，前端有 102 条断言锁定）

- 响应体恒为 `{code, message, data, timestamp}`，**成功码 200**（不是 0）。
- HTTP 状态码与业务码同源：`400↔400xx`、`401↔401xx`、`403↔403xx`、`404↔404xx`、`409↔409xx`、`500↔500xx`。
- 关键业务码：40001 弱口令 / 40002 原密码错 / 40003 新旧相同 / 40101 无令牌 /
  40102 令牌无效 / 40103 令牌已失效 / 40104 账号或密码错误 / 40300 越权 /
  40301 禁用 / 40302 强制改密 / 40303 锁定 / 40304 不能操作自己 / 40305 不能操作管理员 /
  40402 班级不存在或无权访问 / 40403 问卷不存在或不可见 / 40901 账号已存在 / 40902 手机号占用。
- 方法约定：改密 `POST`、重置密码 `POST`、启禁用 `PUT ...?enabled=`（query 参数）。
- 用户字段名 `realName`（不是 name）；手机号输出必须脱敏 `138****0001`。
- 初始密码来自 `app.auth.initial-password`（`Init@1234`），显式指定密码才走完整强度策略。
- 弱口令黑名单必须含 `Init@1234` 与 `12345678`。
- 数据级越权与「资源不存在」必须返回**同码同文案**（防 ID 枚举）。

## 本机构建/运行要点（踩过的坑）

- **JDK**：必须用 `D:\huanjing\jdk-17`。默认 PATH 里是 JDK 25，**不支持 source/target 1.8**。
  `JAVA_HOME='D:\huanjing\jdk-17' mvn ...`
- **不能用 `mvn spring-boot:run`**：pom 里 spring-boot-maven-plugin 配了 `<skip>true</skip>`，
  且 `<configuration>` 显式值会覆盖 `-Dspring-boot.skip=false`。改用：
  ```
  mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
  java -Dfile.encoding=UTF-8 -cp "target/classes;$(cat target/cp.txt)" com.g14.g14.G14Application
  ```
- **沙箱会注入 `SERVER__PORT` 环境变量**（会被 relaxed binding 成 `server.port`），
  必须 `env -u SERVER__PORT` 并显式传 `--server.port=8080 --server.address=127.0.0.1`。
- **curl 访问本机要加 `--noproxy '*'`**：本机有 http 代理，否则 127.0.0.1 请求会被代理返回 502。
- Node 用托管版 `C:\Users\25347\.workbuddy-ai\binaries\node\versions\22.22.2-6\node.exe`。
- Vite 启动：`VITE_PROXY_TARGET=http://127.0.0.1:8080 node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173`；
  跑测试脚本时设 `PROXY=http://127.0.0.1:5173/api`。

## 前端验收脚本（改后端后必须全跑）

```bash
cd D:\G14\G14\frontend
PROXY=http://127.0.0.1:5173/api node tools/contract-test.mjs   # 45 断言
PROXY=http://127.0.0.1:5173/api node tools/e2e-journey.mjs     # 39 断言
PROXY=http://127.0.0.1:5173/api node tools/browser-login.mjs   # 18 断言（无头 Chrome 真实点击）
node tools/probe-validator.mjs                                  # 校验器回归
```
前置：后端 8080 在跑、Vite 5173 在跑、已跑 `node tools/seed-demo.mjs` 与
`mysql ... G14 < tools/seed-demo.sql`。前两个脚本会真实改密码，但开头会自动复位演示账号，可重复执行。

## 演示账号

`admin` / `Admin@123`（启动自动创建，无需改密）；`T1001`/`T1002`（老师，可用手机号
13800000001/13800000002）；`2021001`/`2021002`（学生）。除 admin 外首次登录强制改密，
初始密码 `Init@1234`。

## 已知局限（已在设计文档中标注）

- `TokenBlacklist` 是进程内 `ConcurrentHashMap`，重启失效、多实例不共享；生产应换 Redis。
- `AuthInterceptor` 每请求查一次库（换取禁用/改密即时生效），QPS 高时应加 Redis 缓存。
- 班级/问卷模块本体未交付，只有权限校验层 + `/api/permission-demo/*` 演示端点。
