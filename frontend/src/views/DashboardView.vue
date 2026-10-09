<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { loadDashboardOverview, type DashboardOverview } from '../services/dashboard'
import { loadAccounts, type BankAccount } from '../services/bank'
import {
  collectMetricSnapshot,
  loadMetricSeries,
  loadPilotAcceptance,
  type MetricSnapshotPoint,
  type PilotAcceptance,
  type PilotAmountMetric,
  type PilotIndicator,
} from '../services/metrics'
import { apiBase, useSession } from '../session'
import { importJobStatusLabel } from '../utils/labels'
import { formatCurrency } from '../utils/number'
const session = useSession()
const overview = ref<DashboardOverview | null>(null)
const accounts = ref<BankAccount[]>([])
const pilot = ref<PilotAcceptance | null>(null)
const pilotError = ref('')
const pilotMessage = ref('')
const collecting = ref(false)
const trendMetric = ref('')
const trendRows = ref<MetricSnapshotPoint[]>([])
const trendLoading = ref(false)
const error = ref('')
const loading = ref(false)
const cards = () =>
  overview.value
    ? [
        { label: '全账户余额', value: formatCurrency(overview.value.total_balance) },
        { label: '昨日净流入', value: formatCurrency(overview.value.yesterday_net_inflow) },
        { label: '应收总额', value: formatCurrency(overview.value.receivable_amount ?? '0') },
        {
          label: '逾期未收',
          value: formatCurrency(overview.value.overdue_receivable_amount ?? '0'),
        },
        { label: '异常事项', value: String(overview.value.exception_count ?? 0) },
      ]
    : []
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const [dash, account] = await Promise.all([
      loadDashboardOverview(apiBase(), session.token.value, session.user.value.tenant_id),
      loadAccounts(apiBase(), session.token.value, session.user.value.tenant_id),
    ])
    overview.value = dash.data
    accounts.value = account.data.items
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '驾驶舱加载失败'
  } finally {
    loading.value = false
  }
}
function percentText(value: number | null) {
  return value === null ? '暂无数据' : `${value}%`
}
function amountText(item: PilotAmountMetric) {
  if (item.value === null) return '-'
  if (item.metric_code === 'match_rate') return `${item.value}%`
  if (item.metric_code === 'open_exception_count') return String(item.value)
  return formatCurrency(item.value)
}
async function loadPilot() {
  if (!session.user.value || !session.token.value) return
  pilotError.value = ''
  try {
    pilot.value = (
      await loadPilotAcceptance(apiBase(), session.token.value, session.user.value.tenant_id)
    ).data
  } catch (cause) {
    pilotError.value = cause instanceof Error ? cause.message : '试点验收指标加载失败'
  }
}
async function toggleTrend(indicator: PilotIndicator) {
  if (trendMetric.value === indicator.metric_code) {
    trendMetric.value = ''
    trendRows.value = []
    return
  }
  if (!session.user.value || !session.token.value) return
  trendMetric.value = indicator.metric_code
  trendRows.value = []
  trendLoading.value = true
  try {
    trendRows.value = (
      await loadMetricSeries(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        indicator.metric_code,
      )
    ).data
  } catch (cause) {
    pilotMessage.value = cause instanceof Error ? cause.message : '指标趋势加载失败'
  } finally {
    trendLoading.value = false
  }
}
async function collectSnapshot() {
  if (!session.user.value || !session.token.value) return
  collecting.value = true
  try {
    const result = (
      await collectMetricSnapshot(apiBase(), session.token.value, session.user.value.tenant_id)
    ).data
    pilotMessage.value = `已采集 ${result.snapshot_date} 的 ${result.collected} 项指标快照。`
    await loadPilot()
    if (trendMetric.value) {
      const current = pilot.value?.indicators.find(
        (indicator) => indicator.metric_code === trendMetric.value,
      )
      if (current) {
        trendMetric.value = ''
        await toggleTrend(current)
      }
    }
  } catch (cause) {
    pilotMessage.value = cause instanceof Error ? cause.message : '指标快照采集失败'
  } finally {
    collecting.value = false
  }
}
onMounted(() => {
  load()
  loadPilot()
  window.addEventListener('workspace-refresh', load)
})
</script>
<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">管理层概览</p>
        <h2>今天的资金状态</h2>
        <p class="lead">从总余额、应收和异常开始处理。</p>
      </div>
      <span v-if="loading" class="meta">刷新中...</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <section class="grid stat-grid">
      <article v-for="card in cards()" :key="card.label" class="panel stat-card">
        <span class="label">{{ card.label }}</span
        ><strong class="stat-value">{{ card.value }}</strong>
      </article>
    </section>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>试点验收指标</h3>
          <span class="meta">
            数据日期 {{ pilot?.as_of ?? '-' }} · 最近快照
            {{ pilot?.last_snapshot_date ?? '尚未采集' }}
          </span>
        </div>
        <button
          v-permission="'report:generate'"
          class="small-primary-button"
          :disabled="collecting"
          type="button"
          @click="collectSnapshot"
        >
          {{ collecting ? '采集中...' : '采集今日快照' }}
        </button>
      </div>
      <p v-if="pilotMessage" class="feedback-text">{{ pilotMessage }}</p>
      <p v-if="pilotError" class="error-banner">{{ pilotError }}</p>
      <div v-if="pilot" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>指标</th>
              <th>当前值</th>
              <th>验收阈值</th>
              <th>状态</th>
              <th>趋势</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="indicator in pilot.indicators" :key="indicator.metric_code">
              <tr>
                <td>{{ indicator.label }}</td>
                <td>{{ percentText(indicator.value) }}</td>
                <td>≥{{ indicator.threshold }}%</td>
                <td>
                  <span class="pill">{{ indicator.pass ? '达标' : '未达标' }}</span>
                </td>
                <td>
                  <button class="text-button" type="button" @click="toggleTrend(indicator)">
                    {{ trendMetric === indicator.metric_code ? '收起趋势' : '近 30 天趋势' }}
                  </button>
                </td>
              </tr>
              <tr v-if="trendMetric === indicator.metric_code">
                <td colspan="5">
                  <p v-if="trendLoading" class="meta">趋势加载中...</p>
                  <div v-else-if="trendRows.length" class="mini-list">
                    <div v-for="point in trendRows" :key="point.snapshot_date" class="mini-row">
                      <span>{{ point.snapshot_date }}</span
                      ><span class="meta">{{ percentText(point.metric_value) }}</span>
                    </div>
                  </div>
                  <p v-else class="empty-state">
                    近 30 天没有快照数据，点击「采集今日快照」后开始积累趋势。
                  </p>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <p v-else-if="!pilotError" class="empty-state">试点验收指标加载中...</p>
      <p v-if="pilot" class="meta import-hint">
        <template v-for="(item, index) in pilot.amounts" :key="item.metric_code"
          >{{ item.label }} {{ amountText(item)
          }}<template v-if="index < pilot.amounts.length - 1"> · </template></template
        >
      </p>
    </article>
    <section class="grid two-up">
      <article class="panel">
        <h3>关键风险</h3>
        <div v-if="overview?.key_risks?.length" class="list-stack">
          <div v-for="risk in overview.key_risks" :key="risk.title" class="list-row">
            <span>{{ risk.title }}</span
            ><span class="pill risk-medium">{{ risk.count }}</span>
          </div>
        </div>
        <p v-else class="empty-state">当前没有已识别风险。</p>
      </article>
      <article class="panel">
        <h3>最近导入任务</h3>
        <div v-if="overview?.recent_import_jobs?.length" class="list-stack">
          <RouterLink
            v-for="job in overview.recent_import_jobs"
            :key="`${job.name}-${job.status}`"
            class="list-row link-row"
            :to="job.job_id ? `/imports/${job.job_id}` : '/transactions'"
            ><span
              ><strong>{{ job.name }}</strong
              ><small class="meta">{{ job.message || '查看导入任务' }}</small></span
            ><span class="pill">{{ importJobStatusLabel(job.status) }}</span></RouterLink
          >
        </div>
        <p v-else class="empty-state">暂无导入任务。</p>
      </article>
    </section>
    <article class="panel">
      <div class="section-heading">
        <h3>账户概览</h3>
        <RouterLink class="text-button" to="/accounts">查看全部</RouterLink>
      </div>
      <div class="account-grid">
        <div v-for="account in accounts" :key="account.id" class="account-row">
          <span
            >{{ account.bank_name }} · {{ account.account_name
            }}<small class="meta">后四位 {{ account.account_no_last4 }}</small></span
          ><strong>{{ formatCurrency(account.current_balance) }}</strong>
        </div>
      </div>
    </article>
  </section>
</template>
