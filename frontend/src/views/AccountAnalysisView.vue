<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ModalPanel from '../components/ModalPanel.vue'
import TrendChart, { type TrendSeries } from '../components/TrendChart.vue'
import {
  loadAccountAnalysis,
  proposeCloseBankAccount,
  type AccountAnalysis,
  type CloseSuggestion,
  type UseTypeDistributionItem,
} from '../services/bank'
import { apiBase, useSession } from '../session'
import { useTypeLabel } from '../utils/labels'
import { formatCurrency } from '../utils/number'

const session = useSession()
const analysis = ref<AccountAnalysis | null>(null)
const days = ref(30)
const loading = ref(false)
const error = ref('')
const message = ref('')
const proposeTarget = ref<CloseSuggestion | null>(null)
const proposeReason = ref('')

function openPropose(item: CloseSuggestion) {
  proposeTarget.value = item
  proposeReason.value = item.reasons.join('；')
  message.value = ''
}

function closePropose() {
  proposeTarget.value = null
}

async function proposeClose() {
  if (!session.user.value || !session.token.value || !proposeTarget.value) return
  const reason = proposeReason.value.trim()
  if (!reason) {
    message.value = '请填写销户原因'
    return
  }
  try {
    await proposeCloseBankAccount(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      proposeTarget.value.account_id,
      reason,
    )
    message.value = `已发起销户：${proposeTarget.value.account_name} 进入待销户状态。`
    proposeTarget.value = null
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '发起销户失败'
  }
}

const PALETTE = [
  '#2563eb',
  '#1a7f37',
  '#d97706',
  '#7c3aed',
  '#0891b2',
  '#dc2626',
  '#0d9488',
  '#c026d3',
  '#65a30d',
  '#ea580c',
]

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    analysis.value = (
      await loadAccountAnalysis(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        days.value,
      )
    ).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账户分析加载失败'
  } finally {
    loading.value = false
  }
}

function switchDays(next: number) {
  if (next === days.value) return
  days.value = next
  load()
}

const trend = computed(() => analysis.value?.balance_trend ?? null)
const trendDates = computed(() => trend.value?.accounts[0]?.points.map((point) => point.date) ?? [])
const trendLines = computed<TrendSeries[]>(() =>
  (trend.value?.accounts ?? []).map((account, index) => ({
    name: account.account_name,
    color: PALETTE[index % PALETTE.length],
    values: account.points.map((point) => point.balance),
  })),
)

const distribution = computed(() => analysis.value?.use_type_distribution ?? [])
const distributionTotal = computed(() =>
  distribution.value.reduce((sum, item) => sum + Number(item.balance), 0),
)
const distributionMax = computed(() =>
  Math.max(1, ...distribution.value.map((item) => Number(item.balance))),
)
function distributionPercent(item: UseTypeDistributionItem) {
  if (distributionTotal.value <= 0) return '0.0'
  return ((Number(item.balance) / distributionTotal.value) * 100).toFixed(1)
}
function distributionWidth(item: UseTypeDistributionItem) {
  return `${Math.max(2, (Number(item.balance) / distributionMax.value) * 100)}%`
}

onMounted(load)
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">账户分析</p>
        <h2>账户活跃度与结构分析</h2>
        <p class="lead">按动账活跃度、余额趋势、用途分布识别高频与低效账户，辅助销户决策。</p>
      </div>
      <span v-if="loading" class="meta">加载中...</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message && !proposeTarget" class="feedback-text">{{ message }}</p>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>账户活跃度排名</h3>
          <span class="meta"
            >统计窗口：近 {{ analysis?.activity_window_days ?? 90 }} 天动账，按笔数降序</span
          >
        </div>
      </div>
      <div v-if="analysis?.activity_ranking.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>排名</th>
              <th>账户</th>
              <th>银行</th>
              <th>账号后 4 位</th>
              <th>用途</th>
              <th>近 90 天笔数</th>
              <th>近 90 天金额</th>
              <th>最近动账日</th>
              <th>当前余额</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in analysis.activity_ranking" :key="item.account_id">
              <td>{{ item.rank }}</td>
              <td>{{ item.account_name }}</td>
              <td>{{ item.bank_name }}</td>
              <td>{{ item.account_no_last4 }}</td>
              <td>{{ useTypeLabel(item.use_type ?? 'unset') }}</td>
              <td>{{ item.txn_count_90d }}</td>
              <td>{{ formatCurrency(item.txn_amount_90d) }}</td>
              <td>{{ item.last_transaction_date || '-' }}</td>
              <td>{{ formatCurrency(item.current_balance) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无账户数据。</p>
    </article>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>余额趋势</h3>
          <span class="meta">
            按当前余额 Top {{ trend?.account_limit ?? 10 }} 账户，每日余额回推
            <template v-if="trend"> · {{ trend.start }} 至 {{ trend.end }}</template>
          </span>
        </div>
        <div class="action-group">
          <button class="text-button" :disabled="days === 7" type="button" @click="switchDays(7)">
            近 7 天</button
          ><button
            class="text-button"
            :disabled="days === 30"
            type="button"
            @click="switchDays(30)"
          >
            近 30 天
          </button>
        </div>
      </div>
      <TrendChart
        v-if="trendLines.length && trendDates.length"
        title="账户余额"
        :dates="trendDates"
        :lines="trendLines"
        :format-value="formatCurrency"
      />
      <p v-else class="empty-state">暂无余额趋势数据。</p>
    </article>

    <article class="panel">
      <div class="section-heading">
        <div>
          <h3>账户用途分布</h3>
          <span class="meta">按当前余额汇总，条形长度按余额占比</span>
        </div>
      </div>
      <div v-if="distribution.length" class="distribution-list">
        <div v-for="item in distribution" :key="item.use_type" class="distribution-row">
          <span class="distribution-label">{{ useTypeLabel(item.use_type) }}</span>
          <div class="distribution-track">
            <div class="distribution-bar" :style="{ width: distributionWidth(item) }" />
          </div>
          <span class="meta">
            账户数 {{ item.account_count }} · 余额 {{ formatCurrency(item.balance) }} · 占比
            {{ distributionPercent(item) }}%
          </span>
        </div>
      </div>
      <p v-else class="empty-state">暂无账户数据。</p>
    </article>

    <article class="panel">
      <div class="section-heading">
        <div>
          <h3>销户建议</h3>
          <span class="meta">基于闲置天数与余额自动生成，仅提示不执行</span>
        </div>
      </div>
      <div v-if="analysis?.close_suggestions.length" class="suggestion-list">
        <div
          v-for="item in analysis.close_suggestions"
          :key="item.account_id"
          class="suggestion-card"
        >
          <div class="suggestion-card-header">
            <strong>{{ item.bank_name }} · {{ item.account_name }}</strong>
            <span class="pill risk-medium">{{ item.suggestion }}</span>
          </div>
          <p class="meta">
            后四位 {{ item.account_no_last4 }} · 余额 {{ formatCurrency(item.current_balance) }} ·
            闲置 {{ item.idle_days ?? '-' }} 天 · 用途
            {{ useTypeLabel(item.use_type ?? 'unset') }}
          </p>
          <ul class="suggestion-reasons">
            <li v-for="reason in item.reasons" :key="reason">{{ reason }}</li>
          </ul>
          <div class="action-group">
            <button
              v-permission="'account:manage'"
              class="small-danger-button"
              type="button"
              @click="openPropose(item)"
            >
              确认销户</button
            ><RouterLink class="text-button" to="/accounts">前往账户页处理</RouterLink>
          </div>
        </div>
      </div>
      <p v-else class="empty-state">当前无销户建议。</p>
    </article>

    <ModalPanel v-if="proposeTarget" title="确认销户" @close="closePropose">
      <p class="meta">
        {{ proposeTarget.bank_name }} · {{ proposeTarget.account_name }} · 后四位
        {{ proposeTarget.account_no_last4 }}
      </p>
      <label>销户原因<textarea v-model="proposeReason" rows="4" /></label>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button
          v-permission="'account:manage'"
          class="primary-button"
          type="button"
          @click="proposeClose"
        >
          提交销户申请
        </button>
        <button class="ghost-button" type="button" @click="closePropose">取消</button>
      </template>
    </ModalPanel>
  </section>
</template>

<style scoped>
.distribution-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.distribution-row {
  display: grid;
  grid-template-columns: 72px 1fr auto;
  align-items: center;
  gap: 12px;
}
.distribution-label {
  font-weight: 600;
}
.distribution-track {
  height: 10px;
  overflow: hidden;
  background: #eef1f6;
  border-radius: 5px;
}
.distribution-bar {
  height: 100%;
  background: #2563eb;
  border-radius: 5px;
}
.suggestion-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.suggestion-card {
  padding: 12px;
  border: 1px solid #e2e7ef;
  border-radius: 8px;
}
.suggestion-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.suggestion-reasons {
  margin: 8px 0;
  padding-left: 18px;
  color: #5b6472;
}
</style>
