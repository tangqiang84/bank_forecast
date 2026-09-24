<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../session'
const router = useRouter()
const route = useRoute()
const session = useSession()
const links = [
  { path: '/dashboard', label: '驾驶舱' },
  { path: '/accounts', label: '银行账户' },
  { path: '/transactions', label: '银行流水' },
  { path: '/receivables', label: '合同应收' },
  { path: '/projects', label: '项目资金' },
  { path: '/matching', label: '匹配结果' },
  { path: '/exceptions', label: '异常事项' },
  { path: '/reconciliation', label: '财务对账' },
  { path: '/reports', label: '报表中心' },
  { path: '/forecast', label: '现金预测' },
]
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
        v-for="link in links"
        :key="link.path"
        :to="link.path"
        :class="{ active: active(link.path) }"
        >{{ link.label }}</RouterLink
      >
    </nav>
    <RouterView />
  </main>
</template>
