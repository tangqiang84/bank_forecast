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
  { path: '/reports', label: '报表中心', permission: 'report:view' },
  { path: '/forecast', label: '现金预测', permission: 'forecast:view' },
]
const visibleLinks = computed(() =>
  links.filter((link) => hasPermission(session.user.value, link.permission)),
)
function active(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}
function signOut() {
  session.signOut()
  router.push('/login')
}
function refresh() {
  window.dispatchEvent(new CustomEvent('workspace-refresh'))
}
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div>
        <p class="eyebrow">银行资金智能连接器</p>
        <h1>资金工作台</h1>
      </div>
      <div class="user-actions">
        <span>{{ session.user.value?.display_name }}</span
        ><button class="ghost-button" type="button" @click="refresh">刷新数据</button
        ><button class="ghost-button" type="button" @click="signOut">退出登录</button>
      </div>
    </header>
    <nav class="view-tabs" aria-label="工作区导航">
      <RouterLink
        v-for="link in visibleLinks"
        :key="link.path"
        :to="link.path"
        :class="{ active: active(link.path) }"
        >{{ link.label }}</RouterLink
      >
    </nav>
    <RouterView />
  </main>
</template>
