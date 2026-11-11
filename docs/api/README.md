# 接口文档索引（D-06）

两套文档并存，分工不同：

| 形态 | 位置 | 适合回答 |
| --- | --- | --- |
| Swagger UI（自动生成） | 启动后端后访问 `http://localhost:8080/swagger-ui.html` | 「这个接口要传什么字段」「能不能点一下试试」 |
| Markdown（手写） | 本目录 6 个文件 | 「这个字段为什么这么设计」「错误码什么语义」「权限与数据范围」 |

Swagger 由注解生成，永远和代码同步；但注解里写不下设计理由。Markdown 写理由，代价是需要人工维护——所以两份都要有，且**以 Swagger 的字段为准**，Markdown 里的示例若与 Swagger 不一致，说明代码改了文档没跟上。

---

## 1. Swagger 入口

| 用途 | 地址 |
| --- | --- |
| 交互式文档 | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| 当前规模 | 72 个接口、94 个 Schema、11 个分组 |

### 怎么调需要登录的接口

1. 先用 `POST /api/auth/login` 拿 `token`（也可以直接在 Swagger 里点这个接口试）。
2. 点页面右上角 **Authorize**，在 `bearerAuth` 里只填 token 本身，**不要加 `Bearer ` 前缀**（Swagger 会自动加）。
3. 之后所有请求都会带上 `Authorization: Bearer <token>`。

### 分组是怎么来的

11 个 Controller 各带一个中文 `@Tag`，编号即菜单顺序：

| 编号 | 分组 | Controller |
| --- | --- | --- |
| 01 | 认证 | `AuthController` |
| 02 | 用户管理 | `UserController` |
| 03 | 角色与权限 | `RbacController` |
| 04 | 问卷管理 | `SurveyController` |
| 05 | 答题与答卷 | `AnswerController` |
| 06 | 统计分析 | `StatController` |
| 07 | 数据导出 | `ExportController` |
| 08 | 模板中心 | `TemplateController` |
| 09 | 日志审计 | `LogController` |
| 10 | 系统配置 | `ConfigController` |
| 11 | 健康检查 | `HealthController` |

**没有给 72 个方法逐个写 `@Operation(summary=...)`**。原因：每个方法都补一句中文摘要，工作量与维护成本都不小，而真正的口径（占比分母是谁、匿名怎么裁剪、驳回为什么必填原因）本来就写在下面这些 Markdown 里。Swagger 负责「结构正确」，Markdown 负责「语义正确」。

---

## 2. Markdown 文档

| 文档 | 覆盖接口 | 值得单独看的点 |
| --- | --- | --- |
| [认证与用户管理接口.md](认证与用户管理接口.md) | 登录/注册/改密/个人信息、用户 CRUD、注册审核、RBAC | `token_version` 如何让旧令牌立即失效；登录失败为什么不区分「账号不存在」 |
| [问卷管理接口.md](问卷管理接口.md) | 问卷 CRUD、题目差量保存、发布/暂停/恢复/结束、分享码、复制 | 发布后结构锁定；差量保存的三种操作；`fillable` 为什么由后端算 |
| [答题与答卷接口.md](答题与答卷接口.md) | 大厅、填写状态、草稿、提交、我的记录、答卷管理 | 生成列 + 唯一索引实现限填一次；匿名只发生在出口；`answer_count` 四条路径 |
| [统计与导出接口.md](统计与导出接口.md) | 整卷统计、单题统计、Excel 导出 | 多选占比之和 > 100% 的语义；为什么不做预聚合表；导出为什么不遵守统一响应结构 |
| [模板中心接口.md](模板中心接口.md) | 共享、审核、浏览、评分、使用、下架 | 模板是快照不是引用；待审内容为什么回 404 而不是 403；模板为什么没有 `*:all` 权限 |
| [系统配置接口.md](系统配置接口.md) | 公开配置、配置 CRUD | 唯一键为什么不带 `deleted`；`******` 是哨兵值；密码规则越界为什么 clamp 而不报错 |

---

## 3. 全部接口的公共约定

| 约定 | 说明 |
| --- | --- |
| 统一前缀 | 所有接口以 `/api` 开头 |
| 统一响应 | `{ code, msg, data, timestamp }`，`code` 与 HTTP 状态码一致 |
| 例外 | 只有两个导出接口不遵守——它们直接写二进制流，`Content-Disposition` 用 `filename*=UTF-8''` |
| 分页参数 | `page` 从 1 开始，`size` 默认 10、上限 100 |
| 分页响应 | `{ records, total, page, size, pages }` |
| 时间格式 | 请求 `yyyy-MM-dd HH:mm:ss`，响应 ISO-8601 带时区 |
| 鉴权 | `Authorization: Bearer <token>`，匿名可用路径只有登录、注册、公开配置、健康检查、分享码取卷 |
| 权限注解 | 方法级 `@RequirePerm({"a","b"})` 为「任一命中」；数据级范围在 Service 里二次校验 |

常见状态码：

| 码 | 场景 |
| --- | --- |
| 400 | 参数校验失败、状态不允许该操作、业务规则拒绝 |
| 401 | 未登录、令牌过期、`token_version` 已失效 |
| 403 | 已登录但无权限，或操作了不属于自己的数据 |
| 404 | 资源不存在，或**存在但当前身份不该知道它存在**（如未过审的模板） |
| 409 | 冲突：账号被锁、重复提交、唯一键冲突 |

---

## 4. 验证文档与代码一致

```powershell
# Swagger 能导出、接口数没掉
Invoke-RestMethod http://localhost:8080/v3/api-docs | ForEach-Object { $_.paths.PSObject.Properties.Count }

# 6 个 Markdown 覆盖的接口都有对应冒烟脚本
& .\deploy\test\smoke_auth.ps1
& .\deploy\test\smoke_survey.ps1
& .\deploy\test\smoke_answer.ps1
& .\deploy\test\smoke_stat.ps1
& .\deploy\test\smoke_template.ps1
& .\deploy\test\smoke_config.ps1
```

期望接口数 72。若数字变小，说明有 Controller 没被扫描到或注解缺失。
