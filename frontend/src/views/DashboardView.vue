<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import TrendChart, { type TrendSeries } from '../components/TrendChart.vue'
import {
  loadDashboardOverview,
  loadDashboardTrends,
  type DashboardOverview,
  type DashboardTrendPoint,
  type DashboardTrends,
} from '../services/dashboard'
import {
  loadAccounts,
  loadTransactions,
  type BankAccount,
  type BankTransaction,
} from '../services/bank'
import { runMatching } from '../services/receivables'
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
import {
  directionLabel,
  importJobStatusLabel,
  matchStatusLabel,
  payableStatusLabel,
  receivableStatusLabel,
  riskLevelLabel,
} from '../utils/labels'
import { navPermitted } from '../utils/navigation'
import { hasPermission } from '../utils/permission'
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
const trends = ref<DashboardTrends | null>(null)
const trendsDays = ref(30)
const trendsLoading = ref(false)
const trendsError = ref('')
const error = ref('')
const loading = ref(false)
const matching = ref(false)
const matchResult = ref<{ autoMatched: number; pendingReview: number } | null>(null)
const matchError = ref('')
// 快捷操作区（菜单重设计方案 §4-4）：任一导入权限点命中即可见「导入数据」
const canImport = computed(() =>
  navPermitted(session.user.value, [
    'transaction:import',
    'contract:import',
    'project:import',
    'receipt:import',
    'reconciliation:run',
    'import:view',
  ]),
)
const canRunMatching = computed(() => hasPermission(session.user.value, 'matching:run'))
const canGenerateReport = computed(() => hasPermission(session.user.value, 'report:generate'))
const showQuickActions = computed(
  () => canImport.value || canRunMatching.value || canGenerateReport.value,
)
async function runQuickMatching() {
  if (!session.user.value || !session.token.value || matching.value) return
  matching.value = true
  matchResult.value = null
  matchError.value = ''
  try {
    const result = await runMatching(apiBase(), session.token.value, session.user.value.tenant_id)
    matchResult.value = {
      autoMatched: Number(result.data.auto_matched_count ?? result.data.matched ?? 0),
      pendingReview: Number(result.data.pending_review_count ?? result.data.suggested ?? 0),
    }
  } catch (cause) {
    matchError.value = cause instanceof Error ? cause.message : '一键匹配运行失败，请稍后重试'
  } finally {
    matching.value = false
  }
}
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
// 近期流水摘要（方向 A）：取最近 5 条真实流水，不做任何加工与编造
const recentTransactions = ref<BankTransaction[]>([])
const recentTxError = ref('')
// 风险项目红绿灯（方向 A）：由真实 risk_projects 的 risk_level 计数派生；空数组即「当前没有风险项目」
const riskLights = computed(() => {
  const projects = overview.value?.risk_projects ?? []
  return {
    high: projects.filter((item) => item.risk_level === 'high').length,
    medium: projects.filter((item) => item.risk_level === 'medium').length,
    low: projects.filter((item) => item.risk_level === 'low').length,
    total: projects.length,
  }
})
// 资金结论句（方向 A）：以结论先行表达资金缺口，避免「缺口」标题配大数字造成歧义（Q-01）
const fundingConclusion = computed(() => {
  const gap = overview.value?.funding_gap
  if (!gap) return null
  return gap.has_gap
    ? `未来 ${gap.horizon_days} 天存在资金缺口 ${formatCurrency(gap.gap_amount)}，请调整付款或融资安排。`
    : `未来 ${gap.horizon_days} 天资金可覆盖，无缺口。`
})
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const [dash, account, recentTx] = await Promise.all([
      loadDashboardOverview(apiBase(), session.token.value, session.user.value.tenant_id),
      loadAccounts(apiBase(), session.token.value, session.user.value.tenant_id),
      loadTransactions(apiBase(), session.token.value, session.user.value.tenant_id, 1, 5),
    ])
    overview.value = dash.data
    accounts.value = account.data.items
    recentTransactions.value = recentTx.data.items
    recentTxError.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '驾驶舱加载失败'
  } finally {
    loading.value = false
  }
}
function percentText(value: number | null) {
  return value === null ? '暂无数据' : `${value}%`
}
const trendDates = computed(() => trends.value?.series.map((point) => point.date) ?? [])
function trendSeries(
  name: string,
  color: string,
  pick: (point: DashboardTrendPoint) => number | null,
): TrendSeries {
  return { name, color, values: trends.value?.series.map(pick) ?? [] }
}
// 趋势系列配色（商务简约换肤，方向 A）：统一为低饱和藏青—青绿—砖红商务色谱。
// 原值含 #7c3aed 紫与高饱和 #2563eb 蓝，违反「禁紫/禁霓虹」风格红线；旧值见 git tag pre-topnav-baseline
const TREND_COLORS = {
  income: '#2b7a78',
  expense: '#c0392b',
  netInflow: '#1a3a5c',
  balance: '#5b8ab5',
  openException: '#c4841d',
  matchRate: '#7a8b99',
  receivable: '#4f7d5a',
  overdue: '#8f3b2c',
} as const

const incomeSeries = computed(() => trendSeries('收入', TREND_COLORS.income, (p) => p.income))
const expenseSeries = computed(() => trendSeries('支出', TREND_COLORS.expense, (p) => p.expense))
const netSeries = computed(() => trendSeries('净流入', TREND_COLORS.netInflow, (p) => p.net_inflow))
const balanceSeries = computed(() => trendSeries('总余额', TREND_COLORS.balance, (p) => p.balance))
const exceptionSeries = computed(() =>
  trendSeries('未闭环异常', TREND_COLORS.openException, (p) => p.open_exceptions),
)
const matchRateSeries = computed(() =>
  trendSeries('匹配率', TREND_COLORS.matchRate, (p) => p.match_rate),
)
const receivableSeries = computed(() =>
  trendSeries('应收总额', TREND_COLORS.receivable, (p) => p.receivable_total),
)
const overdueSeries = computed(() =>
  trendSeries('逾期未收', TREND_COLORS.overdue, (p) => p.overdue_receivable),
)
const snapshotMissing = computed(
  () =>
    !!trends.value?.series.length &&
    trends.value.series.every(
      (point) => point.receivable_total === null && point.overdue_receivable === null,
    ),
)
function percentFormat(value: number) {
  return `${value}%`
}
async function loadTrends() {
  if (!session.user.value || !session.token.value) return
  trendsLoading.value = true
  trendsError.value = ''
  try {
    trends.value = (
      await loadDashboardTrends(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        trendsDays.value,
      )
    ).data
  } catch (cause) {
    trendsError.value = cause instanceof Error ? cause.message : '资金趋势加载失败'
  } finally {
    trendsLoading.value = false
  }
}
function switchDays(days: number) {
  if (days === trendsDays.value) return
  trendsDays.value = days
  loadTrends()
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
  loadTrends()
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
    <!-- 资金结论横幅（方向 A）：老板十秒读完现状，结论由真实缺口与余额数据派生 -->
    <section v-if="fundingConclusion" class="conclusion-banner panel">
      <p class="conclusion-text">
        <strong :class="overview?.funding_gap?.has_gap ? 'expense-amount' : 'income-amount'">{{
          fundingConclusion
        }}</strong>
        <template v-if="overview?.funding_gap">
          依据：当前余额 {{ formatCurrency(overview.funding_gap.balance) }} · 未来
          {{ overview.funding_gap.horizon_days }} 天应收
          {{ formatCurrency(overview.funding_gap.receivable_30d) }} · 应付
          {{ formatCurrency(overview.funding_gap.payable_30d) }}
        </template>
      </p>
    </section>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <section v-if="showQuickActions" class="panel">
      <div class="section-heading">
        <div>
          <h3>快捷操作</h3>
          <span class="meta">看到问题可直接发起处理动作</span>
        </div>
        <div class="action-group">
          <RouterLink v-if="canImport" class="small-primary-button" to="/imports"
            >导入数据</RouterLink
          >
          <button
            v-permission="'matching:run'"
            class="small-primary-button"
            :disabled="matching"
            type="button"
            @click="runQuickMatching"
          >
            {{ matching ? '匹配中...' : '一键匹配' }}
          </button>
          <RouterLink v-permission="'report:generate'" class="small-primary-button" to="/reports"
            >生成报表</RouterLink
          >
        </div>
      </div>
      <p v-if="matchResult" class="feedback-text">
        匹配完成：自动核销 {{ matchResult.autoMatched }} 笔，待人工
        {{ matchResult.pendingReview }} 笔。<RouterLink class="text-button" to="/exceptions"
          >去处理 →</RouterLink
        >
      </p>
      <p v-if="matchError" class="error-banner">{{ matchError }}</p>
    </section>
    <section class="grid stat-grid">
      <article v-for="card in cards()" :key="card.label" class="panel stat-card">
        <span class="label">{{ card.label }}</span
        ><strong class="stat-value">{{ card.value }}</strong>
      </article>
    </section>
    <section class="grid two-up">
      <article class="panel">
        <h3>应收 Top 5</h3>
        <div v-if="overview?.top_receivables?.length" class="list-stack">
          <div v-for="item in overview.top_receivables" :key="item.contract_name" class="list-row">
            <span
              ><strong>{{ item.contract_name }}</strong
              ><small class="meta"
                >{{ item.customer_name }} · 应收日期 {{ item.due_date }} ·
                {{ receivableStatusLabel(item.status) }}</small
              ></span
            ><strong>{{ formatCurrency(item.amount) }}</strong>
          </div>
        </div>
        <p v-else class="empty-state">暂无未收应收计划。</p>
      </article>
      <article class="panel">
        <h3>未来应付 Top 5</h3>
        <div v-if="overview?.payable_top?.length" class="list-stack">
          <div v-for="item in overview.payable_top" :key="item.id" class="list-row">
            <span
              ><strong>{{ item.supplier_name }}</strong
              ><small class="meta"
                >{{ item.node_name }} · 应付日期 {{ item.due_date }} ·
                {{ payableStatusLabel(item.status) }}</small
              ></span
            ><strong>{{ formatCurrency(item.amount) }}</strong>
          </div>
        </div>
        <p v-else class="empty-state">暂无待支付的应付计划。</p>
        <p v-if="overview" class="meta import-hint">
          未来 7 天应付 {{ formatCurrency(overview.payable_7d_amount ?? '0') }} · 未来 30 天应付
          {{ formatCurrency(overview.payable_30d_amount ?? '0') }}
        </p>
      </article>
      <article class="panel">
        <h3>风险项目红绿灯</h3>
        <template v-if="overview?.funding_gap">
          <div class="risk-lights">
            <span class="risk-light"
              ><i class="light-dot light-red" :class="{ 'is-off': !riskLights.high }"></i>预警
              {{ riskLights.high }}</span
            ><span class="risk-light"
              ><i class="light-dot light-amber" :class="{ 'is-off': !riskLights.medium }"></i>关注
              {{ riskLights.medium }}</span
            ><span class="risk-light"
              ><i class="light-dot light-green" :class="{ 'is-off': !riskLights.low }"></i>正常
              {{ riskLights.low }}</span
            >
          </div>
          <p class="meta import-hint">
            {{
              riskLights.total
                ? `当前共 ${riskLights.total} 个风险项目，明细见风险项目排行。`
                : '当前没有风险项目。'
            }}
            余额 {{ formatCurrency(overview.funding_gap.balance) }} · 应收
            {{ formatCurrency(overview.funding_gap.receivable_30d) }} · 应付
            {{ formatCurrency(overview.funding_gap.payable_30d) }}
          </p>
        </template>
        <p v-else class="empty-state">暂无资金缺口数据。</p>
      </article>
      <article class="panel">
        <h3>风险项目排行</h3>
        <div v-if="overview?.risk_projects?.length" class="list-stack">
          <RouterLink
            v-for="item in overview.risk_projects"
            :key="item.project_no"
            class="list-row link-row"
            to="/projects"
            ><span
              ><strong>{{ item.project_no }} · {{ item.project_name }}</strong
              ><small class="meta"
                >{{ item.customer_name }} · 风险分 {{ item.risk_score }} ·
                {{ item.risk_items.join('；') || '暂无风险项' }}</small
              ></span
            ><span class="pill" :class="`risk-${item.risk_level}`">{{
              riskLevelLabel(item.risk_level)
            }}</span></RouterLink
          >
        </div>
        <p v-else class="empty-state">当前没有非健康项目。</p>
      </article>
    </section>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>资金趋势</h3>
          <span class="meta">{{
            trends ? `${trends.start} 至 ${trends.end}` : `近 ${trendsDays} 天`
          }}</span>
        </div>
        <div class="action-group">
          <button
            class="text-button"
            :disabled="trendsDays === 7"
            type="button"
            @click="switchDays(7)"
          >
            近 7 天</button
          ><button
            class="text-button"
            :disabled="trendsDays === 30"
            type="button"
            @click="switchDays(30)"
          >
            近 30 天
          </button>
          <span v-if="trendsLoading" class="meta">加载中...</span>
        </div>
      </div>
      <p v-if="trendsError" class="error-banner">{{ trendsError }}</p>
      <p v-else-if="!trends || !trends.series.length" class="empty-state">
        {{ trendsLoading ? '趋势加载中...' : '暂无趋势数据。' }}
      </p>
      <div v-else class="grid two-up">
        <TrendChart
          title="收支与净流入"
          :dates="trendDates"
          :bars="[incomeSeries, expenseSeries]"
          :lines="[netSeries]"
          :format-value="formatCurrency"
        />
        <TrendChart
          title="总余额"
          :dates="trendDates"
          :lines="[balanceSeries]"
          :format-value="formatCurrency"
        />
        <TrendChart title="未闭环异常" :dates="trendDates" :lines="[exceptionSeries]" />
        <TrendChart
          title="匹配率"
          :dates="trendDates"
          :lines="[matchRateSeries]"
          :format-value="percentFormat"
        />
        <p v-if="snapshotMissing" class="empty-state">暂无快照数据，请先在试点验收区块采集快照。</p>
        <TrendChart
          v-else
          title="应收 / 逾期"
          :dates="trendDates"
          :lines="[receivableSeries, overdueSeries]"
          :format-value="formatCurrency"
        />
      </div>
    </article>
    <!-- 近期流水摘要（方向 A）：最近 5 条真实流水，方向/匹配状态用既有标签函数 -->
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>近期流水摘要</h3>
          <span class="meta">最近 5 条银行流水</span>
        </div>
        <RouterLink class="text-button" to="/transactions">查看全部</RouterLink>
      </div>
      <p v-if="recentTxError" class="error-banner">{{ recentTxError }}</p>
      <div v-else-if="recentTransactions.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>交易日期</th>
              <th>银行</th>
              <th>方向</th>
              <th>金额</th>
              <th>对方户名</th>
              <th>摘要</th>
              <th>匹配状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tx in recentTransactions" :key="tx.id">
              <td>{{ tx.transaction_date }}</td>
              <td>
                {{ tx.bank_name ?? '-'
                }}{{ tx.account_no_last4 ? ` · ${tx.account_no_last4}` : '' }}
              </td>
              <td>{{ directionLabel(tx.direction) }}</td>
              <td :class="tx.direction === 'expense' ? 'expense-amount' : 'income-amount'">
                {{ formatCurrency(tx.amount) }}
              </td>
              <td>{{ tx.counterparty_name ?? '-' }}</td>
              <td class="reason-cell">{{ tx.summary ?? '-' }}</td>
              <td>
                <span class="pill">{{ matchStatusLabel(tx.match_status) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无流水数据。</p>
    </article>
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
