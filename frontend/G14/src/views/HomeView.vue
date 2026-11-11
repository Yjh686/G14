<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { roleLabel } from '@/utils/dict'

const router = useRouter()
const auth = useAuthStore()

const user = computed(() => auth.user)

/** 按权限展示的快捷入口，全部指向真实路由 */
const shortcuts = computed(() => {
  const items: { key: string; title: string; desc: string; perm?: string[]; to: string }[] = [
    {
      key: 'create-survey',
      title: '创建问卷',
      desc: '新建问卷并编辑题目，发布后学生即可填写',
      perm: ['survey:create'],
      to: '/surveys/new',
    },
    {
      key: 'my-surveys',
      title: '我的问卷',
      desc: '管理已建问卷、查看回收情况与答卷',
      perm: ['survey:list:self', 'survey:list:all'],
      to: '/surveys',
    },
    {
      key: 'fill-survey',
      title: '填写问卷',
      desc: '查看需要你填写的问卷，支持断点续答',
      perm: ['survey:fill'],
      to: '/fill',
    },
    {
      key: 'my-records',
      title: '我的填写记录',
      desc: '查看自己提交过的答卷（不含他人身份）',
      perm: ['answer:read:self'],
      to: '/my-answers',
    },
    {
      key: 'templates',
      title: '模板中心',
      desc: '浏览老师们共享的问卷模板，一键复制成自己的问卷',
      perm: ['template:browse', 'template:share', 'template:audit'],
      to: '/templates',
    },
    {
      key: 'users',
      title: '用户管理',
      desc: '维护学生与教师账号、审核注册申请',
      perm: ['user:list'],
      to: '/admin/users',
    },
    {
      key: 'audit',
      title: '注册审核',
      desc: '审批自助注册的账号申请',
      perm: ['user:audit'],
      to: '/admin/audit',
    },
    {
      key: 'logs',
      title: '日志查询',
      desc: '登录记录与关键操作审计，日志只读不可删改',
      perm: ['log:login:read', 'log:operation:read'],
      to: '/admin/logs',
    },
    {
      key: 'config',
      title: '系统配置',
      desc: '站点名称、公告与密码策略，改完立即生效',
      perm: ['config:manage'],
      to: '/admin/config',
    },
  ]
  return items.filter((i) => !i.perm || auth.hasAnyPerm(i.perm))
})

function go(item: { to: string }) {
  router.push(item.to)
}


const permCount = computed(() => auth.permissions.length)
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="welcome-card">
      <div class="welcome-row">
        <el-avatar :size="56" class="welcome-avatar">
          {{ auth.displayName.charAt(0) || 'U' }}
        </el-avatar>
        <div class="welcome-text">
          <h2 class="welcome-title">你好，{{ auth.displayName }}</h2>
          <div class="welcome-meta">
            <el-tag type="primary" size="small">{{ roleLabel(user?.roleCode) }}</el-tag>
            <span v-if="user?.department" class="meta-item">{{ user.department }}</span>
            <span class="meta-item">账号：{{ user?.username }}</span>
            <span v-if="user?.lastLoginTime" class="meta-item">
              上次登录：{{ user.lastLoginTime }}
            </span>
          </div>
        </div>
      </div>
    </el-card>

    <el-divider content-position="left">可用功能</el-divider>

    <el-row :gutter="16">
      <el-col v-for="item in shortcuts" :key="item.key" :xs="24" :sm="12" :md="8">
        <el-card
          shadow="hover"
          class="shortcut-card is-ready"
          @click="go(item)"
        >
          <div class="shortcut-title">
            {{ item.title }}
          </div>
          <div class="shortcut-desc">{{ item.desc }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty
      v-if="shortcuts.length === 0"
      description="当前账号没有任何可用的业务功能，请联系管理员分配权限"
    />

    <el-collapse class="debug-collapse">
      <el-collapse-item title="当前账号权限（用于联调核对）" name="1">
        <div class="perm-meta">共 {{ permCount }} 项权限</div>
        <div class="perm-list">
          <el-tag v-for="p in auth.permissions" :key="p" size="small" class="perm-tag">
            {{ p }}
          </el-tag>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.welcome-card {
  margin-bottom: 8px;
  border-radius: 8px;
}

.welcome-row {
  display: flex;
  gap: 16px;
  align-items: center;
}

.welcome-avatar {
  background-color: #2f7fd1;
  font-size: 22px;
}

.welcome-title {
  margin: 0 0 6px;
  font-size: 20px;
}

.welcome-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  font-size: 13px;
  color: #606266;
}

.meta-item {
  color: #909399;
}

.shortcut-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.shortcut-card.is-ready {
  cursor: pointer;
  border-color: #c6e2ff;
}

.shortcut-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 15px;
  font-weight: 600;
}

.shortcut-desc {
  font-size: 13px;
  line-height: 1.6;
  color: #909399;
}

.debug-collapse {
  margin-top: 24px;
}

.perm-meta {
  margin-bottom: 8px;
  font-size: 12px;
  color: #909399;
}

.perm-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.perm-tag {
  font-family: Consolas, Monaco, monospace;
}
</style>
