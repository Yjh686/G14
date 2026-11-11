# AGENTS.md
角色权限：
1. 系统管理员（ROLE_ADMIN）：用户/角色管理、日志、模板审核、系统配置、全量数据管理。
2. 教师（ROLE_TEACHER）：创建/编辑/发布自己的问卷，查看自己问卷的答卷、统计、导出，共享模板。
3. 学生（ROLE_STUDENT）：填写问卷、保存草稿、断点续答、查看自己的填写记录、浏览和评分模板。
“问卷发布者”不是独立角色，是权限：survey:create、survey:publish:self 默认授予 ADMIN 和 TEACHER。
学生默认不能创建问卷；如需学生干部发布，给指定学生加 survey:create 权限，不新增角色。

## 1. 项目概述

校园问卷信息调查管理系统，实现问卷创建、发布分发、在线填写、答卷回收、统计分析可视化闭环。
采用 Spring Boot + Vue 前后端分离，敏捷开发，期末交付并答辩。

硬性指标：
- 高并发答卷场景响应时间 < 3 秒
- 长期运行故障率 < 0.1%
- 必须覆盖 P0 功能闭环

## 2. 技术栈

后端：Spring Boot 3.x、MyBatis-Plus、MySQL 8
前端：Vue 3
部署：前后端分离，RESTful API，统一前缀 `/api`

## 3. 仓库结构

```text
/
├── AGENTS.md
├── README.md
├── docs/                  # 需求、设计、接口、测试、答辩材料
│   ├── requirements/
│   ├── design/
│   ├── api/
│   └── test/
├── backend/               # Spring Boot 工程
│   └── AGENTS.md
├── frontend/              # Vue 工程
│   └── AGENTS.md
└── deploy/                # docker-compose、nginx、sql