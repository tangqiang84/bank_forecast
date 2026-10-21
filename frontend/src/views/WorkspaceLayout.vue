<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession, apiBase } from '../session'
import { loadExceptionStats } from '../services/receivables'
import { NAV_GROUPS, navPermitted } from '../utils/navigation'
const router = useRouter()
const route = useRoute()
const session = useSession()

// 顶栏形态（方向 A，2026-10-21）：一级 tab 横排六组，二级条只渲染当前组子项。
// 原侧栏「分组折叠 + 角色默认展开」退化为「按角色默认选中组」。
// 优先级：手动选中组 > 路由所属组 > 角色默认组 > 首个可见组。
// 当路由跳到与手动选中组不同的组（如页内「去处理 →」跳转）时清除手动选中，让二级条跟随路由。
function roleDefaultGroup(): string | null {
  const roles = session.user.value?.roles ?? []
  if (roles.includes('CFO') || roles.includes('ADMIN')) return '第二步 · 回款处理'
  if (roles.includes('CEO')) return '经营洞察'
  if (roles.includes('CASHIER')) return '第一步 · 数据接入'
  if (roles.includes('BUSINESS')) return '基础资料'
  return null
}

const visibleGroups = computed(() =>
  NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((link) => navPermitted(session.user.value, link.permission)),
  })).filter((group) => group.items.length > 0),
)

function active(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}

const selectedGroup = ref<string | null>(null)

const routeGroupLabel = computed(
  () =>
    visibleGroups.value.find((group) => group.items.some((link) => active(link.path)))?.label ??
    null,
)

watch(routeGroupLabel, (group) => {
  if (group && selectedGroup.value && group !== selectedGroup.value) selectedGroup.value = null
})

const currentGroupLabel = computed(
  () =>
    selectedGroup.value ??
    routeGroupLabel.value ??
    roleDefaultGroup() ??
    visibleGroups.value[0]?.label ??
    '',
)
const currentGroup = computed(() =>
  visibleGroups.value.find((group) => group.label === currentGroupLabel.value),
)
const currentGroupIndex = computed(() =>
  Math.max(
    0,
    visibleGroups.value.findIndex((group) => group.label === currentGroupLabel.value),
  ),
)

function selectGroup(label: string) {
  selectedGroup.value = label
}

// 待办角标（P4-20；P6-1 ADMIN 并入 CFO 口径）：CASHIER 显示我的待办、CFO/ADMIN 显示待分派+待复核，其余角色不显示；为 0 或失败不显示
const workbenchBadge = ref<number | null>(null)

async function loadBadge() {
  const user = session.user.value
  if (!user || !session.token.value) return
  if (!navPermitted(user, ['exception:view', 'matching:view'])) return
  const roles = user.roles ?? []
  const isCfo = roles.includes('CFO') || roles.includes('ADMIN')
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
    <header class="top-nav">
      <span class="nav-brand" title="银行资金智能连接器">资金工作台</span>
      <nav class="nav-groups" aria-label="一级导航">
        <button
          v-for="(group, index) in visibleGroups"
          :key="group.label"
          class="nav-group-btn"
          :class="{ current: group.label === currentGroupLabel }"
          type="button"
          :aria-expanded="group.label === currentGroupLabel"
          :aria-controls="`sub-nav-${index}`"
          @click="selectGroup(group.label)"
        >
          {{ group.label }}
        </button>
      </nav>
      <div class="nav-user">
        <button class="ghost-button" type="button" @click="refresh">刷新数据</button>
        <span class="nav-user-name">{{ session.user.value?.display_name }}</span>
        <button class="text-button" type="button" @click="signOut">退出登录</button>
      </div>
    </header>
    <nav :id="`sub-nav-${currentGroupIndex}`" class="sub-nav" aria-label="二级导航">
      <RouterLink
        v-for="link in currentGroup?.items ?? []"
        :key="link.path"
        :to="link.path"
        :class="{ active: active(link.path) }"
        >{{ link.label
        }}<span v-if="link.path === '/exceptions' && workbenchBadge !== null" class="nav-badge">{{
          workbenchBadge
        }}</span></RouterLink
      >
    </nav>
    <main class="workspace-main">
      <div class="workspace-content">
        <RouterView />
      </div>
    </main>
  </div>
</template>
