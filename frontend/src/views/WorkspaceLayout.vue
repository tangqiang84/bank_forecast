<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../session'
import { hasPermission } from '../utils/permission'
const router = useRouter()
const route = useRoute()
const session = useSession()
const links = [
  { path: '/dashboard', label: '驾驶舱', permission: 'dashboard:view' },
  { path: '/accounts', label: '银行账户', permission: 'account:view' },
  { path: '/transactions', label: '银行流水', permission: 'transaction:view' },
  { path: '/receivables', label: '合同应收', permission: 'contract:view' },
  { path: '/projects', label: '项目资金', permission: 'project:view' },
  { path: '/matching', label: '匹配结果', permission: 'matching:view' },
  { path: '/exceptions', label: '异常事项', permission: 'exception:view' },
  { path: '/reconciliation', label: '财务对账', permission: 'reconciliation:view' },
  { path: '/rules', label: '规则中心', permission: 'project:rule' },
  { path: '/system', label: '系统管理', permission: 'system:manage' },
  { path: '/reports', label: '报表中心', permission: 'report:view' },
  { path: '/forecast', label: '现金预测', permission: 'forecast:view' },
]
const visibleLinks = computed(() =>
  links.filter((link) => hasPermission(session.user.value, link.permission)),
)
function active(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}
const currentTitle = computed(() => links.find((link) => active(link.path))?.label ?? '资金工作台')
function signOut() {
  session.signOut()
  router.push('/login')
}
function refresh() {
  window.dispatchEvent(new CustomEvent('workspace-refresh'))
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <p class="sidebar-brand-eyebrow">银行资金智能连接器</p>
        <p class="sidebar-brand-title">资金工作台</p>
      </div>
      <nav class="sidebar-nav" aria-label="工作区导航">
        <RouterLink
          v-for="link in visibleLinks"
          :key="link.path"
          :to="link.path"
          :class="{ active: active(link.path) }"
          >{{ link.label }}</RouterLink
        >
      </nav>
      <div class="sidebar-user">
        <span class="sidebar-user-name">{{ session.user.value?.display_name }}</span
        ><button class="sidebar-signout" type="button" @click="signOut">退出登录</button>
      </div>
    </aside>
    <main class="workspace-main">
      <header class="workspace-topbar">
        <p class="workspace-topbar-title">{{ currentTitle }}</p>
        <button class="ghost-button" type="button" @click="refresh">刷新数据</button>
      </header>
      <div class="workspace-content">
        <RouterView />
      </div>
    </main>
  </div>
</template>
