<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchJson } from './services/http'
import { loadDashboardOverview, type DashboardOverview } from './services/dashboard'
import { formatCurrency } from './utils/number'

type ServiceHealth = {
  code: number
  message: string
  data: {
    service: string
    status: string
    timestamp: string
  }
  trace_id: string
}

type OverviewCard = {
  label: string
  value: string
  hint: string
}

const backendBase = import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080'
const analyticsBase = import.meta.env.VITE_ANALYTICS_BASE_URL ?? 'http://localhost:8001'

const backendHealth = ref<ServiceHealth | null>(null)
const analyticsHealth = ref<ServiceHealth | null>(null)
const overview = ref<DashboardOverview | null>(null)
const loading = ref(false)
const backendError = ref('')
const analyticsError = ref('')
const overviewError = ref('')

const overviewCards = computed<OverviewCard[]>(() => {
  if (!overview.value) {
    return [
      { label: '全账户余额', value: '-', hint: '等待接口返回' },
      { label: '昨日净流入', value: '-', hint: '等待接口返回' },
      { label: '待处理异常', value: '-', hint: '等待接口返回' },
      { label: '闲置账户', value: '-', hint: '等待接口返回' },
    ]
  }

  return [
    {
      label: '全账户余额',
      value: formatCurrency(overview.value.total_balance),
      hint: `最近同步 ${overview.value.last_sync_at}`,
    },
    {
      label: '昨日净流入',
      value: formatCurrency(overview.value.yesterday_net_inflow),
      hint: `匹配率 ${Math.round(overview.value.match_rate * 100)}%`,
    },
    {
      label: '待处理异常',
      value: String(overview.value.pending_exceptions),
      hint: '异常事项中心待分派',
    },
    {
      label: '闲置账户',
      value: String(overview.value.idle_accounts),
      hint: '账户盘点建议关注',
    },
  ]
})

const moduleCards = [
  { title: '银行流水', desc: '导入、筛选、详情、分类、回单' },
  { title: '银行账户', desc: '账户状态、活跃度、销户建议' },
  { title: '合同应收', desc: '合同、应收计划、回款进度' },
  { title: '项目资金', desc: '项目回款、现金流健康度' },
  { title: '异常事项', desc: '分派、确认、处理、关闭' },
  { title: '报表中心', desc: '日报、月报、体检报告导出' },
  { title: '规则配置', desc: '阈值、匹配窗口、行业模型' },
  { title: '系统管理', desc: '用户、角色、数据源、审计' },
]

async function refresh() {
  loading.value = true
  backendError.value = ''
  analyticsError.value = ''
  overviewError.value = ''

  const backendPromise = fetchJson<ServiceHealth>(`${backendBase}/api/v1/health`)
  const analyticsPromise = fetchJson<ServiceHealth>(`${analyticsBase}/health`)
  const overviewPromise = loadDashboardOverview(backendBase)

  const [backendResult, analyticsResult, overviewResult] = await Promise.allSettled([
    backendPromise,
    analyticsPromise,
    overviewPromise,
  ])

  if (backendResult.status === 'fulfilled') {
    backendHealth.value = backendResult.value
  } else {
    backendHealth.value = null
    backendError.value = backendResult.reason instanceof Error
      ? backendResult.reason.message
      : String(backendResult.reason)
  }

  if (analyticsResult.status === 'fulfilled') {
    analyticsHealth.value = analyticsResult.value
  } else {
    analyticsHealth.value = null
    analyticsError.value = analyticsResult.reason instanceof Error
      ? analyticsResult.reason.message
      : String(analyticsResult.reason)
  }

  if (overviewResult.status === 'fulfilled') {
    overview.value = overviewResult.value.data
  } else {
    overview.value = null
    overviewError.value = overviewResult.reason instanceof Error
      ? overviewResult.reason.message
      : String(overviewResult.reason)
  }

  loading.value = false
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <main class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">银行资金智能连接器</p>
        <h1>资金驾驶舱</h1>
        <p class="lead">先把流水、合同、异常和账户状态连起来，再逐步补齐导入、匹配和报表闭环。</p>
      </div>
      <button class="ghost-button" :disabled="loading" @click="refresh">
        {{ loading ? '刷新中...' : '刷新数据' }}
      </button>
    </header>

    <section class="grid stat-grid">
      <article v-for="card in overviewCards" :key="card.label" class="panel stat-card">
        <span class="label">{{ card.label }}</span>
        <strong class="stat-value">{{ card.value }}</strong>
        <p class="meta">{{ card.hint }}</p>
      </article>
    </section>

    <section class="grid two-up">
      <article class="panel">
        <h2>服务状态</h2>
        <div class="status-row">
          <span>后端</span>
          <strong>{{ backendHealth?.data.status ?? '未连接' }}</strong>
        </div>
        <p class="meta">{{ backendHealth?.data.service ?? 'bank-forecast-backend' }}</p>
        <p v-if="backendError" class="error-text">{{ backendError }}</p>

        <div class="status-row">
          <span>分析服务</span>
          <strong>{{ analyticsHealth?.data.status ?? '未连接' }}</strong>
        </div>
        <p class="meta">{{ analyticsHealth?.data.service ?? 'bank-forecast-analytics' }}</p>
        <p v-if="analyticsError" class="error-text">{{ analyticsError }}</p>
      </article>

      <article class="panel">
        <h2>首期入口</h2>
        <ul class="endpoint-list">
          <li><code>{{ backendBase }}/api/v1/health</code></li>
          <li><code>{{ backendBase }}/api/v1/dashboard/overview</code></li>
          <li><code>{{ analyticsBase }}/health</code></li>
        </ul>
        <p v-if="overviewError" class="error-text">{{ overviewError }}</p>
        <p class="meta">当前页面直接对应首页驾驶舱，后续可继续扩展到流水、合同、异常和报表页面。</p>
      </article>
    </section>

    <section class="grid two-up">
      <article class="panel">
        <h2>关键风险</h2>
        <div v-if="overview" class="risk-list">
          <div v-for="risk in overview.key_risks" :key="risk.title" class="list-row">
            <div>
              <strong>{{ risk.title }}</strong>
              <p class="meta">{{ risk.description }}</p>
            </div>
            <span class="pill">{{ risk.count }}</span>
          </div>
        </div>
      </article>

      <article class="panel">
        <h2>近期导入任务</h2>
        <div v-if="overview" class="job-list">
          <div v-for="job in overview.recent_import_jobs" :key="job.name" class="list-row">
            <div>
              <strong>{{ job.name }}</strong>
              <p class="meta">{{ job.message }}</p>
            </div>
            <span class="pill">{{ job.status }}</span>
          </div>
        </div>
      </article>
    </section>

    <section class="panel">
      <h2>模块入口</h2>
      <div class="module-grid">
        <article v-for="module in moduleCards" :key="module.title" class="module-card">
          <strong>{{ module.title }}</strong>
          <p class="meta">{{ module.desc }}</p>
        </article>
      </div>
    </section>

    <section class="panel">
      <h2>应收 Top 3</h2>
      <div v-if="overview" class="receivable-list">
        <div v-for="item in overview.top_receivables" :key="item.contract_name" class="list-row">
          <div>
            <strong>{{ item.contract_name }}</strong>
            <p class="meta">{{ item.customer_name }} · {{ item.due_date }}</p>
          </div>
          <div class="amount-block">
            <strong>{{ formatCurrency(item.amount) }}</strong>
            <span class="pill">{{ item.status }}</span>
          </div>
        </div>
      </div>
    </section>
  </main>
</template>
