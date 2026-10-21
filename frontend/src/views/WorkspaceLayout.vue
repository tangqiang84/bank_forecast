<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../session'
import { hasPermission } from '../utils/permission'
const router = useRouter()
const route = useRoute()
const session = useSession()
// 菜单按用户工作流分五组（docs/菜单与角色导航重组方案）；组内按权限过滤，空组隐藏
const groups = [
  {
    label: '经营总览',
    items: [
      { path: '/dashboard', label: '驾驶舱', permission: 'dashboard:view' },
      { path: '/forecast', label: '现金预测', permission: 'forecast:view' },
      { path: '/reports', label: '报表中心', permission: 'report:view' },
      { path: '/accounts/analysis', label: '账户分析', permission: 'account:view' },
    ],
  },
  {
    label: '日常处理',
    items: [
      { path: '/transactions', label: '银行流水', permission: 'transaction:view' },
      {
        path: '/exceptions',
        label: '回款处理工作台',
        permission: ['exception:view', 'matching:view'],
      },
      { path: '/reconciliation', label: '财务对账', permission: 'reconciliation:view' },
    ],
  },
  {
    label: '业务台账',
    items: [
      { path: '/accounts', label: '银行账户', permission: 'account:view' },
      { path: '/receivables', label: '合同应收', permission: 'contract:view' },
      { path: '/projects', label: '项目资金', permission: 'project:view' },
    ],
  },
  {
    label: '配置中心',
    items: [
      { path: '/rules', label: '规则中心', permission: 'project:rule' },
      { path: '/templates', label: '模板配置', permission: 'template:manage' },
    ],
  },
  {
    label: '系统管理',
    items: [{ path: '/system', label: '系统管理', permission: 'system:manage' }],
  },
]
type NavLink = { path: string; label: string; permission: string | string[] }
const visibleGroups = computed(() =>
  groups
    .map((group) => ({
      ...group,
      items: group.items.filter((link) =>
        ([] as string[])
          .concat(link.permission)
          .some((code) => hasPermission(session.user.value, code)),
      ),
    }))
    .filter((group) => group.items.length > 0),
)
const allLinks = computed(() => groups.flatMap((group) => group.items))
function active(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}
const currentTitle = computed(
  () => allLinks.value.find((link: NavLink) => active(link.path))?.label ?? '资金工作台',
)
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
        <div v-for="group in visibleGroups" :key="group.label" class="nav-group">
          <p class="nav-group-title">{{ group.label }}</p>
          <RouterLink
            v-for="link in group.items"
            :key="link.path"
            :to="link.path"
            :class="{ active: active(link.path) }"
            >{{ link.label }}</RouterLink
          >
        </div>
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
