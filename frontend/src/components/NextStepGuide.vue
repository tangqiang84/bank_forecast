<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { loadReconciliationResults } from '../services/finance'
import { loadExceptionStats, type ExceptionStats } from '../services/receivables'
import { loadReports } from '../services/reports'
import { apiBase, useSession } from '../session'
import { navPermitted } from '../utils/navigation'
import {
  RECONCILIATION_REMINDER_DAY,
  REPORT_REMINDER_DAY,
  exceptionTodoTotal,
  isSameYearMonth,
  resolveNextStep,
  type NextStepContext,
} from '../utils/nextStep'

// 页面「下一步引导条」（P6-6）：页眉下方轻量提示，最多 1 条；接口失败静默降级，关闭状态仅存 sessionStorage（刷新页面恢复）
const props = defineProps<{
  context: NextStepContext
  // 待办处理页复用页面已加载的统计，避免重复请求
  stats?: ExceptionStats | null
}>()

const session = useSession()
const today = new Date()
const dayOfMonth = today.getDate()
const currentYear = today.getFullYear()
const currentMonth = today.getMonth() + 1

const ownStats = ref<ExceptionStats | null>(null)
const hasMonthlyReconciliation = ref<boolean | null>(null)
const hasMonthlyReport = ref<boolean | null>(null)

const todoCount = computed(() => {
  const source = props.context === 'exceptions' ? props.stats : ownStats.value
  return source ? exceptionTodoTotal(source) : null
})

const guide = computed(() =>
  resolveNextStep(props.context, session.user.value, {
    dayOfMonth,
    todoCount: todoCount.value,
    hasMonthlyReconciliation: hasMonthlyReconciliation.value,
    hasMonthlyReport: hasMonthlyReport.value,
  }),
)

function dismissKey(id: string) {
  return `next-step-guide-dismissed:${id}`
}

const dismissed = ref(false)

watch(
  guide,
  (next) => {
    dismissed.value = next !== null && sessionStorage.getItem(dismissKey(next.id)) === '1'
  },
  { immediate: true },
)

function dismiss() {
  if (!guide.value) return
  sessionStorage.setItem(dismissKey(guide.value.id), '1')
  dismissed.value = true
}

async function loadTodoStats() {
  const user = session.user.value
  if (!user || !session.token.value) return
  try {
    ownStats.value = (await loadExceptionStats(apiBase(), session.token.value, user.tenant_id)).data
  } catch {
    ownStats.value = null
  }
}

// 「本月是否已有对账任务」：后端无对账任务列表接口，取最新一条对账差异的创建时间落在本月即视为本月已对账（本月对账零差异时判断不到，属已知近似）
async function loadReconciliationStatus() {
  const user = session.user.value
  if (!user || !session.token.value) return
  try {
    const result = await loadReconciliationResults(
      apiBase(),
      session.token.value,
      user.tenant_id,
      undefined,
      1,
      1,
    )
    hasMonthlyReconciliation.value = isSameYearMonth(
      result.data.items[0]?.created_at,
      currentYear,
      currentMonth,
    )
  } catch {
    hasMonthlyReconciliation.value = null
  }
}

async function loadMonthlyReportStatus() {
  const user = session.user.value
  if (!user || !session.token.value) return
  try {
    const result = await loadReports(apiBase(), session.token.value, user.tenant_id, 1, 100)
    hasMonthlyReport.value = result.data.items.some(
      (task) =>
        task.report_type === 'monthly' &&
        isSameYearMonth(task.created_at, currentYear, currentMonth),
    )
  } catch {
    hasMonthlyReport.value = null
  }
}

async function load() {
  const user = session.user.value
  if (!user || !session.token.value) return
  const tasks: Promise<void>[] = []
  if (props.context === 'transactions' && navPermitted(user, ['exception:view', 'matching:view'])) {
    tasks.push(loadTodoStats())
  }
  if (
    navPermitted(user, 'reconciliation:view') &&
    (props.context === 'reconciliation' || dayOfMonth >= RECONCILIATION_REMINDER_DAY)
  ) {
    tasks.push(loadReconciliationStatus())
  }
  if (
    props.context !== 'transactions' &&
    dayOfMonth >= REPORT_REMINDER_DAY &&
    navPermitted(user, 'report:view')
  ) {
    tasks.push(loadMonthlyReportStatus())
  }
  await Promise.all(tasks)
}

onMounted(() => {
  load()
  window.addEventListener('workspace-refresh', load)
})

onUnmounted(() => {
  window.removeEventListener('workspace-refresh', load)
})

defineExpose({ reload: load })
</script>

<template>
  <div v-if="guide && !dismissed" class="next-step-guide" role="status">
    <span class="next-step-guide-text">{{ guide.text }}</span>
    <RouterLink v-if="guide.link" class="text-button" :to="guide.link.to">{{
      guide.link.label
    }}</RouterLink>
    <button class="text-button next-step-guide-close" type="button" @click="dismiss">关闭</button>
  </div>
</template>
