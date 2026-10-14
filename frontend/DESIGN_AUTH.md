# 班级问卷系统 —— 用户认证与角色权限模块设计

> 适用范围：本模块覆盖「登录 / 登出 / 当前用户 / 修改密码 / 管理员重置密码 / 管理员开通账号」以及基于角色的接口鉴权。
> 角色：`学生(Student)`、`老师(Teacher)`、`管理员(Admin)` 三种。
> 账号识别：学生用**学号**登录，老师用**工号或手机号**登录，管理员用**管理员账号**登录；后端根据账号前缀 / 角色信息自动识别。
> 安全基线：密码 bcrypt 加密存储、JWT 无状态鉴权、接口鉴权中间件 + 资源属主校验、首次登录强制改密。

---

## 1. 总体设计

### 1.1 认证流程

```
① 用户输入 账号 + 密码  →  POST /api/auth/login
② 后端按账号识别角色（学号→学生，工号/手机号→老师，admin前缀→管理员）
③ 校验 bcrypt 密码哈希；若通过则签发 JWT（payload 含 userId + role）
④ 前端保存 token，并持久化到 localStorage
⑤ 后续请求带 Authorization: Bearer <token>，由鉴权中间件统一校验
⑥ 首次登录（mustChangePassword=1）前端强制跳转改密页，改密成功后清除标记
```

### 1.2 JWT 设计

- Header：`{ "alg": "HS256", "typ": "JWT" }`
- Payload：

```json
{
  "userId": 1,
  "role": "STUDENT",
  "iat": 1730000000,
  "exp": 1730007200
}
```

- 有效期：建议 `2h`（可配置，如 `JWT_EXPIRES_IN=7200`）
- 密钥：服务端环境变量 `JWT_SECRET`，仅存服务端，不外泄
- **不在 token 中存放敏感信息**（姓名、手机号等一律通过 `GET /api/auth/me` 获取），保证 token 失效后可即时收回权限。

### 1.3 账号识别规则

后端通过登录账号的形态自动识别角色（账号前缀 / 规则 / 数据表角色字段三者结合）：

| 登录身份 | 账号形态 | 识别依据 |
|---|---|---|
| 学生 | 学号（纯数字，如 8 位） | 账号命中学号规则 → `STUDENT` |
| 老师 | 工号（纯数字）或手机号（11 位） | 命中工号/手机号规则 → `TEACHER` |
| 管理员 | 管理员账号（如 `admin` 前缀） | 命中管理员规则或白名单 → `ADMIN` |

识别流程（登录控制器内，示例见 4.4）：

```
detectRole(account):
  if account 命中「管理员规则」    → ADMIN
  else if account 命中「手机号/工号」 → TEACHER
  else if account 命中「学号」     → STUDENT
  else                             → 按数据库 role 兜底 / 拒绝
```

> 说明：前端登录页的身份选择 Tab **仅用于输入引导与格式校验**，不会随请求体提交角色；角色判定一律以后端 `detectRole` 为准，防止前端伪造角色。

### 1.4 角色与越权防护矩阵

| 功能点 | 学生 | 老师 | 管理员 |
|---|---|---|---|
| 登录 / 登出 / 获取当前用户 / 改自己密码 | ✔ | ✔ | ✔ |
| 创建班级 / 发布问卷（仅本人） | ✘ | ✔（属主） | ✔（全部） |
| 查看填写自己班级问卷 | ✔（限所在班级） | ✔（属主） | ✔（全部） |
| 开通账号 | ✘ | ✘ | ✔ |
| 重置他人密码 | ✘ | ✘ | ✔ |

> 后端权限判断分两层：**中间件层**校验"是否登录 + 角色是否允许"，**业务层**校验"资源属主 / 数据范围"（防止 A 老师操作 B 老师的班级问卷）。

---

## 2. 数据库表结构

### 2.1 User（用户表）

```sql
CREATE TABLE `user` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `account`             VARCHAR(32)     NOT NULL COMMENT '登录账号：学生学号 / 老师工号或手机号',
  `password_hash`       VARCHAR(100)    NOT NULL COMMENT 'bcrypt 密码哈希',
  `name`                VARCHAR(64)     NOT NULL COMMENT '姓名',
  `role`                TINYINT         NOT NULL COMMENT '角色：1=学生 2=老师 3=管理员',
  `status`              TINYINT         NOT NULL DEFAULT 1   COMMENT '状态：1=正常 0=禁用',
  `must_change_password` TINYINT        NOT NULL DEFAULT 1   COMMENT '是否需强制改密：1=是 0=否',
  `phone`               VARCHAR(20)     DEFAULT NULL COMMENT '手机号（老师可选）',
  `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account` (`account`),
  KEY `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

设计说明：

- `account` 唯一索引：学号 / 工号 / 手机号 / 管理员账号全局唯一。
- 角色用 `TINYINT` 数字存（1/2/3），前端 / 接口层映射为可读字符串 `STUDENT/TEACHER/ADMIN`；也可改用 `ENUM`，此处取数字便于扩展。
- `must_change_password` 用于实现「首次登录强制改密」：开通账号时置 1，改密成功后置 0。
- `status` 用于账号禁用；被禁用账号登录时返回明确错误。

### 2.2 关联表（规划，供上下文参考）

```sql
CREATE TABLE `class` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`          VARCHAR(64)     NOT NULL COMMENT '班级名称',
  `invite_code`   VARCHAR(16)     NOT NULL COMMENT '学生加入用邀请码',
  `owner_id`      BIGINT UNSIGNED NOT NULL COMMENT '创建老师 user.id',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级表';

CREATE TABLE `class_member` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `class_id`   BIGINT UNSIGNED NOT NULL,
  `student_id` BIGINT UNSIGNED NOT NULL COMMENT '学生 user.id',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_student` (`class_id`, `student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级成员表（学生加入的班级）';

CREATE TABLE `questionnaire` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `title`      VARCHAR(128) NOT NULL COMMENT '问卷标题',
  `class_id`   BIGINT UNSIGNED NOT NULL COMMENT '发布到的班级',
  `creator_id` BIGINT UNSIGNED NOT NULL COMMENT '创建老师 user.id',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_class` (`class_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问卷表';
```

> 本模块（认证与权限）当前仅交付 `user` 表；`class/class_member/questionnaire` 用于说明属主校验与数据范围权限的落点。

---

## 3. RESTful API 列表

统一响应结构：

```json
{ "code": 200, "message": "成功", "data": {} }
```

通用错误码：`400` 参数错误 / `401` 未登录或 token 无效 / `403` 无权限 / `404` 不存在 / `409` 冲突（如账号已存在）/ `500` 服务异常。

### 3.1 认证接口

#### ① POST /api/auth/login — 登录

- 鉴权：无
- 请求体：

```json
{
  "account": "20250001",
  "password": "abc123456"
}
```

- 响应示例（200）：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOjEsInJvbGUiOiJTVFVERU5UIiwiZXhwIjoxNzMwMDA3MjAwfQ.sig",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "mustChangePassword": true,
    "user": {
      "id": 1,
      "account": "20250001",
      "name": "张三",
      "role": "STUDENT",
      "status": 1
    }
  }
}
```

- 错误示例：
  - `401 {"code":401,"message":"账号或密码错误"}`
  - `403 {"code":403,"message":"账号已被禁用"}`

#### ② POST /api/auth/logout — 登出

- 鉴权：需登录（带 token）
- 请求体：`{}`（无状态 JWT，客户端丢弃 token 即可；如部署服务端黑名单，可在此注销）
- 响应：`{"code":200,"message":"已登出","data":null}`

#### ③ GET /api/auth/me — 获取当前用户信息

- 鉴权：需登录
- 请求头：`Authorization: Bearer <token>`
- 响应（200）：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": 1,
    "account": "20250001",
    "name": "张三",
    "role": "STUDENT",
    "status": 1,
    "mustChangePassword": false,
    "phone": null,
    "createdAt": "2026-10-01 09:00:00"
  }
}
```

#### ④ PUT /api/auth/password — 修改本人密码

- 鉴权：需登录
- 请求体：

```json
{
  "oldPassword": "abc123456",
  "newPassword": "Xyz@789012",
  "confirmPassword": "Xyz@789012"
}
```

- 校验：新旧密码一致；强度（长度 ≥ 8，含字母 + 数字）；改密成功后 `mustChangePassword` 置 0。
- 响应（200）：`{"code":200,"message":"密码修改成功","data":null}`

### 3.2 管理端接口（仅管理员）

#### ⑤ POST /api/admin/users — 开通账号

- 鉴权：需登录 + 角色 `ADMIN`
- 请求体：

```json
{
  "account": "20250002",
  "name": "李四",
  "role": "STUDENT",
  "password": "temp123456",
  "phone": "13800000000"
}
```

- 说明：`role` 取 `STUDENT/TEACHER/ADMIN`；新账号 `mustChangePassword=1`，首次登录强制改密。
- 响应（200）：

```json
{ "code": 200, "message": "账号开通成功", "data": { "id": 2, "account": "20250002", "name": "李四", "role": "STUDENT" } }
```

- 错误：`409 {"code":409,"message":"账号已存在"}`

#### ⑥ PUT /api/admin/users/{id}/password/reset — 重置密码

- 鉴权：需登录 + 角色 `ADMIN`
- 请求体：

```json
{ "newPassword": "Temp@123456" }
```

- 说明：重置后 `mustChangePassword=1`，用户下次登录需改密。
- 响应（200）：`{"code":200,"message":"密码已重置","data":null}`

#### ⑦ GET /api/admin/users — 用户列表（可选）

- 鉴权：需登录 + 角色 `ADMIN`
- Query：`?page=1&size=20&role=STUDENT&keyword=2025`
- 响应（200）：

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "total": 2,
    "list": [
      { "id": 1, "account": "20250001", "name": "张三", "role": "STUDENT", "status": 1, "createdAt": "2026-10-01 09:00:00" },
      { "id": 2, "account": "20250002", "name": "李四", "role": "STUDENT", "status": 1, "createdAt": "2026-10-02 10:00:00" }
    ]
  }
}
```

---

## 4. 后端鉴权中间件关键代码（Node.js / Express 参考实现）

> 以下为关键代码示意；设计思想（校验 token → 注入身份 → 角色守卫 → 业务属主校验）适用于任意后端语言。

### 4.1 JWT 签发（登录）

```js
// services/token.js
const jwt = require('jsonwebtoken');

function signToken(user) {
  return jwt.sign(
    { userId: user.id, role: user.role },      // 仅放非敏感、鉴权必需字段
    process.env.JWT_SECRET,
    { expiresIn: Number(process.env.JWT_EXPIRES_IN || 7200) }
  );
}
```

### 4.2 鉴权中间件（校验 token + 注入 req.user）

```js
// middleware/auth.js
const jwt = require('jsonwebtoken');

function verifyToken(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;

  if (!token) {
    return res.status(401).json({ code: 401, message: '未登录或缺少 token' });
  }
  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET);
    // 注入当前身份，供后续中间件 / 业务使用
    req.user = { id: payload.userId, role: payload.role };
    next();
  } catch (err) {
    return res.status(401).json({ code: 401, message: 'token 无效或已过期' });
  }
}

// 角色守卫：requireRole('ADMIN') / requireRole('TEACHER','ADMIN')
function requireRole(...roles) {
  return (req, res, next) => {
    if (!roles.includes(req.user.role)) {
      return res.status(403).json({ code: 403, message: '无权限访问' });
    }
    next();
  };
}

module.exports = { verifyToken, requireRole };
```

### 4.3 资源属主校验（防止越权：A 老师操作 B 老师的资源）

```js
// services/ownership.js —— 示例：校验某个班级是否属于当前老师
async function ensureClassOwner(req, res, next) {
  const classId = req.params.classId;
  const cls = await ClassModel.findById(classId);
  if (!cls) return res.status(404).json({ code: 404, message: '班级不存在' });

  // 管理员放行；老师仅放行自己创建的班级
  if (req.user.role === 'ADMIN' || cls.owner_id === req.user.id) {
    req.class = cls;
    return next();
  }
  // 越权访问统一返回 403
  return res.status(403).json({ code: 403, message: '无权操作该班级' });
}
```

越权访问统一约定：凡是不属于当前用户的资源，一律返回 `403 {"code":403,"message":"无权访问该资源"}`，与「未登录（401）」严格区分，便于前端分别处理。

### 4.4 登录控制器（自动识别角色 + bcrypt + 首次改密标记）

```js
// controllers/authController.js
const bcrypt = require('bcryptjs');
const { signToken } = require('../services/token');
const UserModel = require('../models/user');

// 账号识别角色：管理员 > 老师（工号/手机号） > 学生（学号）
function detectRole(account) {
  if (/^admin/i.test(account)) return 'ADMIN';                 // 管理员账号规则
  if (/^1\d{10}$/.test(account) || /^\d+$/.test(account)) return 'TEACHER'; // 手机号/工号
  if (/^\d{4,}$/.test(account)) return 'STUDENT';              // 学号（纯数字）
  return null;
}

async function login(req, res) {
  const { account, password } = req.body;
  // 自动识别角色并查询对应账号
  const role = detectRole(account);
  const user = await UserModel.findByAccountAndRole(account, role);
  if (!user) return res.status(401).json({ code: 401, message: '账号或密码错误' });

  const ok = await bcrypt.compare(password, user.password_hash);
  if (!ok) return res.status(401).json({ code: 401, message: '账号或密码错误' });

  if (user.status !== 1) return res.status(403).json({ code: 403, message: '账号已被禁用' });

  const token = signToken(user);
  res.json({
    code: 200, message: '登录成功',
    data: {
      token,
      tokenType: 'Bearer',
      expiresIn: Number(process.env.JWT_EXPIRES_IN || 7200),
      mustChangePassword: user.must_change_password === 1,
      user: { id: user.id, account: user.account, name: user.name, role: user.role, status: user.status }
    }
  });
}

async function changePassword(req, res) {
  const { oldPassword, newPassword } = req.body;
  const user = await UserModel.findById(req.user.id);
  const ok = await bcrypt.compare(oldPassword, user.password_hash);
  if (!ok) return res.status(400).json({ code: 400, message: '原密码错误' });

  const hash = await bcrypt.hash(newPassword, 10);
  await UserModel.updatePassword(user.id, hash);
  await UserModel.clearMustChange(user.id);        // 首次登录改密后解除强制
  res.json({ code: 200, message: '密码修改成功', data: null });
}
```

### 4.5 路由挂载

```js
// routes/index.js
const { verifyToken, requireRole } = require('../middleware/auth');

router.post('/auth/login', authController.login);
router.post('/auth/logout', verifyToken, authController.logout);
router.get('/auth/me', verifyToken, authController.me);
router.put('/auth/password', verifyToken, authController.changePassword);

router.post('/admin/users', verifyToken, requireRole('ADMIN'), adminController.createUser);
router.put('/admin/users/:id/password/reset', verifyToken, requireRole('ADMIN'), adminController.resetPassword);
router.get('/admin/users', verifyToken, requireRole('ADMIN'), adminController.listUsers);
```

---

## 5. 前端实现（本仓库 `src/`）

技术栈：Vue 3 + Vue Router 4 + Pinia + Axios + Element Plus。

| 文件 | 职责 |
|---|---|
| `src/utils/role.js` | 角色常量与文案 |
| `src/api/request.js` | axios 实例 + 请求拦截（带 token）+ 响应拦截（401 统一登出） |
| `src/api/auth.js` | 认证 / 管理端接口封装 |
| `src/stores/auth.js` | Pinia 认证状态（token / user / 登录 / 登出 / 拉取用户） |
| `src/router/index.js` | 路由表 + 全局前置守卫（登录校验 / 首次改密 / 角色守卫） |
| `src/layouts/MainLayout.vue` | 主布局：顶栏 + 用户菜单（个人中心/改密/登出）+ 管理员入口 |
| `src/views/Login.vue` | 统一登录页 |
| `src/views/ForcePassword.vue` | 首次登录强制改密页 |
| `src/views/ChangePassword.vue` | 修改密码页 |
| `src/views/Profile.vue` | 个人中心 |
| `src/views/Dashboard.vue` | 角色化首页 |
| `src/views/admin/UserManage.vue` | 管理员账号管理（开通 / 重置密码 / 列表） |

### 5.1 角色常量

```js
// src/utils/role.js
export const ROLE = { STUDENT: 'STUDENT', TEACHER: 'TEACHER', ADMIN: 'ADMIN' };
export const ROLE_LABEL = { STUDENT: '学生', TEACHER: '老师', ADMIN: '管理员' };
export const ROLE_TAG = { STUDENT: 'primary', TEACHER: 'warning', ADMIN: 'danger' };
```

### 5.2 登录逻辑（`Login.vue` 核心）

```js
async function handleSubmit() {
  formRef.value?.validate(async (valid) => {
    if (!valid) return;
    loading.value = true;
    try {
      const user = await authStore.login(form.value);
      ElMessage.success('登录成功');
      // 首次登录强制改密
      if (user.mustChangePassword) {
        router.replace({ name: 'ForcePassword' });
      } else {
        router.replace(route.query.redirect || '/dashboard');
      }
    } finally {
      loading.value = false;
    }
  });
}
```

### 5.3 路由守卫（`router/index.js` 关键代码）

```js
router.beforeEach(async (to) => {
  const auth = useAuthStore();

  if (to.meta.public) {                    // 公开页（登录 / 404）
    if (to.name === 'Login' && auth.isLoggedIn) return { name: 'Dashboard' };
    return true;
  }
  if (!auth.isLoggedIn) {                  // 未登录 → 登录页，并记住来源
    return { name: 'Login', query: { redirect: to.fullPath } };
  }
  if (!auth.user && auth.token) {          // 有 token 无用户信息，尝试恢复
    try { await auth.fetchUser(); } catch { auth.logout(); return { name: 'Login' }; }
  }
  if (auth.user?.mustChangePassword && to.name !== 'ForcePassword') {  // 首次强制改密
    return { name: 'ForcePassword' };
  }
  if (to.meta.roles && !to.meta.roles.includes(auth.user?.role)) {     // 角色守卫
    return { name: 'Dashboard' };
  }
  return true;
});
```

完整源码见 `src/` 目录，直接 `npm run dev` 即可运行（联调时在 `.env` 配置 `VITE_API_BASE_URL`）。
