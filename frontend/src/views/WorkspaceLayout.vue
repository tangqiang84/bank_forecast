<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession, apiBase } from '../session'
import { loadExceptionStats } from '../services/receivables'
import { NAV_GROUPS, navPermitted } from '../utils/navigation'
const router = useRouter()
const route = useRoute()
const session = useSession()

// 分组展开状态：按角色给默认值（菜单方案 §4），用户手动收起/展开后 localStorage 记忆
const COLLAPSE_KEY = 'nav-collapsed-groups'

function roleDefaultCollapsed(): string[] {
  const roles = session.user.value?.roles ?? []
  const expand: string[] = roles.includes('CFO')
    ? NAV_GROUPS.map((group) => group.label)
    : roles.includes('CEO')
      ? ['经营总览']
      : roles.includes('CASHIER')
        ? ['日常处理']
        : roles.includes('BUSINESS')
          ? ['业务台账']
          : roles.includes('ADMIN')
            ? ['配置中心', '系统管理']
            : NAV_GROUPS.map((group) => group.label)
  return NAV_GROUPS.map((group) => group.label).filter((label) => !expand.includes(label))
}

function loadCollapsed(): string[] {
  const saved = localStorage.getItem(COLLAPSE_KEY)
  if (saved !== null) {
    try {
      const parsed = JSON.parse(saved)
      if (Array.isArray(parsed)) return parsed.filter((item) => typeof item === 'string')
    } catch {
      // 落回角色默认
    }
  }
  return roleDefaultCollapsed()
}

const collapsed = ref<string[]>(loadCollapsed())

function toggleGroup(label: string) {
  collapsed.value = collapsed.value.includes(label)
    ? collapsed.value.filter((item) => item !== label)
    : [...collapsed.value, label]
  localStorage.setItem(COLLAPSE_KEY, JSON.stringify(collapsed.value))
}

const visibleGroups = computed(() =>
  NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((link) => navPermitted(session.user.value, link.permission)),
  })).filter((group) => group.items.length > 0),
)
const allLinks = computed(() => NAV_GROUPS.flatMap((group) => group.items))
function active(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}
const currentTitle = computed(
  () => allLinks.value.find((link) => active(link.path))?.label ?? '资金工作台',
)

// P4-20 待办角标：CASHIER 显示我的待办、CFO 显示待分派+待复核，其余角色不显示；为 0 或失败不显示
const workbenchBadge = ref<number | null>(null)

async function loadBadge() {
  const user = session.user.value
  if (!user || !session.token.value) return
  if (!navPermitted(user, ['exception:view', 'matching:view'])) return
  const roles = user.roles ?? []
  const isCfo = roles.includes('CFO')
  const isCashier = roles.includes('CASHIER')
  if (!isCfo && !isCashier) return
  try {
    const stats = (await loadExceptionStats(apiBase(), session.token.value, user.tenant_id)).data
    const count = isCfo
      ? stats.unassigned_count + (stats.review_pending_count ?? stats.pending_close_count)
      : stats.my_todo_count
    workbenchBadge.value = count > 0 ? count : null
  } catch {
    workbenchBadge.value = null
  }
}

onMounted(() => {
  loadBadge()
  window.addEventListener('workbench-stats-refresh', loadBadge)
})

function signOut() {
  session.signOut()
  router.push('/login')
}
function refresh() {
  window.dispatchEvent(new CustomEvent('workspace-refresh'))
  loadBadge()
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
          <button
            class="nav-group-title"
            type="button"
            :aria-expanded="!collapsed.includes(group.label)"
            @click="toggleGroup(group.label)"
          >
            {{ group.label }}
          </button>
          <template v-if="!collapsed.includes(group.label)">
            <RouterLink
              v-for="link in group.items"
              :key="link.path"
              :to="link.path"
              :class="{ active: active(link.path) }"
              >{{ link.label
              }}<span
                v-if="link.path === '/exceptions' && workbenchBadge !== null"
                class="nav-badge"
                >{{ workbenchBadge }}</span
              ></RouterLink
            >
          </template>
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
