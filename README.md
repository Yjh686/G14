# 班级问卷系统 · 认证与角色权限模块

小组项目（G14）。当前已完成 **用户认证与角色权限** 部分：登录、登出、当前用户、
修改密码、管理员开通账号 / 重置密码 / 启用禁用，以及三级权限控制。

## 目录

```
G14/
├─ frontend/          Vue 3 + Vite + Element Plus 前端（已接真实后端）
│  ├─ src/            页面、路由守卫、Pinia store、axios 拦截器
│  ├─ tools/          联调自检脚本（契约测试 / 校验器回归 / 演示数据）
│  ├─ mock/           早期假后端，接入真实后端后不再使用
│  └─ INTEGRATION.md  前后端联调说明（启动步骤、演示账号、契约对照）
└─ backend/           预留目录；后端实际源码在 D:\idea\javacode\G14
```

> 后端 Spring Boot 工程（`com.g14.g14`）位于 `D:\idea\javacode\G14`，
> 独立于本仓库，详见 `frontend/INTEGRATION.md`。

## 快速开始

```bash
# 1. 启动后端（MySQL + Spring Boot，监听 8080）
#    见 frontend/INTEGRATION.md 第 1.1 节

# 2. 初始化演示账号
cd frontend && npm install
npm run seed

# 3. 启动前端
npm run dev        # http://localhost:5173
```

## 演示账号

| 角色 | 账号 | 密码 |
|---|---|---|
| 管理员 | `admin` | `Admin@123` |
| 老师 | `T1001` / `T1002` | `Init@1234`（可用手机号 `13800000001` / `13800000002`） |
| 学生 | `2021001` / `2021002` | `Init@1234` |

除管理员外首次登录会强制修改初始密码。

## 自检

```bash
cd frontend
npm run test:contract    # 45 条接口契约断言（需后端 + dev server 在跑）
npm run test:validator   # 登录校验器回归
npm run build            # 生产构建
```

## 已完成能力

- 三种角色（学生 / 老师 / 管理员），**角色由后端按账号查库识别**，前端不提交 role
- JWT 无状态令牌，载荷含用户 ID 与角色；`tokenVersion` 支持改密/禁用即时吊销
- 首次登录强制改密；改密后回签新令牌，旧令牌立即失效
- 登出用 jti 黑名单，只吊销当前这一个令牌，不影响其他设备
- 三级防护：认证中间件 → 接口级 `@RequireRole` → 数据级归属校验
- 「无权访问」与「资源不存在」返回相同错误码，防资源 ID 枚举
- 管理员账号管理：开通、分页检索、重置密码、启用/禁用
- 前端登录页、强制改密页、个人中心、修改密码页、账号管理页 + 路由守卫

## 待接入（后续模块）

- 老师：创建班级、生成邀请码、发布问卷
- 学生：通过邀请码加入班级、填写问卷
