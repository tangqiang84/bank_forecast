<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { activateForecastModel, backfillForecastActuals, loadForecastDetail, loadForecastModels, loadLatestForecast, retryForecast, runForecast, type ForecastDetail, type ForecastModel } from '../services/forecast'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'

const route = useRoute()
const session = useSession()
const forecast = ref<ForecastDetail>({ job: null, results: [], evaluation: { evaluated_points: 0, mae: '0', rmse: '0', mean_deviation: '0' } })
const models = ref<ForecastModel[]>([])
const horizon = ref(7)
const windowSize = ref(3)
const loading = ref(false)
const error = ref('')
const message = ref('')

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const base = apiBase()
    const token = session.token.value
    const tenant = session.user.value.tenant_id
    forecast.value = route.params.jobId
      ? (await loadForecastDetail(base, token, tenant, Number(route.params.jobId))).data
      : (await loadLatestForecast(base, token, tenant)).data
    models.value = (await loadForecastModels(base, token, tenant)).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '预测详情加载失败'
  } finally {
    loading.value = false
  }
}

async function run() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  message.value = ''
  try {
    forecast.value = (await runForecast(apiBase(), session.token.value, session.user.value.tenant_id, horizon.value, windowSize.value)).data
    message.value = '预测任务已完成。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '预测任务失败'
  } finally {
    loading.value = false
  }
}

async function retryJob() {
  if (!session.user.value || !session.token.value || !forecast.value.job) return
  loading.value = true
  try {
    forecast.value = (await retryForecast(apiBase(), session.token.value, session.user.value.tenant_id, forecast.value.job.id)).data
    message.value = '失败任务已重试。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '预测重试失败'
  } finally {
    loading.value = false
  }
}

async function backfill() {
  if (!session.user.value || !session.token.value || !forecast.value.job) return
  loading.value = true
  try {
    forecast.value = (await backfillForecastActuals(apiBase(), session.token.value, session.user.value.tenant_id, forecast.value.job.id)).data
    message.value = '实际金额已回填，评估指标已更新。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '实际金额回填失败'
  } finally {
    loading.value = false
  }
}

async function activate(version: string) {
  if (!session.user.value || !session.token.value) return
  try {
    const updated = (await activateForecastModel(apiBase(), session.token.value, session.user.value.tenant_id, version)).data
    models.value = models.value.map((model) => ({ ...model, status: model.version === updated.version ? 'active' : 'inactive' }))
    message.value = `模型版本 ${version} 已启用。`
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '模型版本启用失败'
  }
}

onMounted(load)
</script>

<template>
  <section class="page-shell">
    <header class="hero-band"><div><p class="eyebrow">现金预测</p><h2>{{ route.params.jobId ? `预测任务 #${route.params.jobId}` : '现金流预测工作台' }}</h2><p class="lead">结合历史净现金流和未来应收，查看预测余额、风险解释和评估偏差。</p></div><RouterLink v-if="route.params.jobId" class="ghost-button" to="/forecast">返回预测工作台</RouterLink></header>
    <p v-if="error" class="error-banner">{{ error }}</p><p v-if="message" class="feedback-text">{{ message }}</p>
    <section class="grid forecast-layout">
      <article class="panel workflow-panel"><h3>生成预测</h3><label>预测天数<input v-model.number="horizon" min="1" max="90" type="number" /></label><label>历史窗口<input v-model.number="windowSize" min="1" max="30" type="number" /></label><button class="primary-button" :disabled="loading" type="button" @click="run">{{ loading ? '处理中...' : '生成预测' }}</button><button v-if="forecast.job?.status === 'failed'" class="ghost-button" :disabled="loading" type="button" @click="retryJob">重试失败任务</button><button v-if="forecast.job?.status === 'success'" class="ghost-button" :disabled="loading" type="button" @click="backfill">回填实际金额</button><section class="detail-section"><h3>模型版本</h3><div v-for="model in models" :key="model.version" class="mini-row"><span><strong>{{ model.version }}</strong> · {{ model.model_name }}</span><button v-if="model.status !== 'active'" class="text-button" type="button" @click="activate(model.version)">启用</button><span v-else class="pill">当前启用</span></div></section></article>
      <article class="panel table-panel"><div class="section-heading"><div><h3>预测任务详情</h3><span class="meta">{{ forecast.job ? `任务 #${forecast.job.id} · ${forecast.job.status} · ${forecast.job.model_version || '-'}` : '暂无已完成预测' }}</span></div><RouterLink v-if="forecast.job && !route.params.jobId" class="text-button" :to="`/forecast/jobs/${forecast.job.id}`">打开任务详情</RouterLink></div><div v-if="forecast.job" class="summary-grid"><div><span>预测区间</span><strong>{{ forecast.job.input_start_date || '-' }} 至 {{ forecast.job.input_end_date || '-' }}</strong></div><div><span>模型</span><strong>{{ forecast.job.model_name || '-' }} / {{ forecast.job.model_version || '-' }}</strong></div><div><span>尝试次数</span><strong>{{ forecast.job.attempt_count }}</strong></div><div><span>完成时间</span><strong>{{ forecast.job.finished_at || '-' }}</strong></div></div><p v-if="forecast.job?.error_message" class="error-banner">{{ forecast.job.error_message }}</p><div v-if="forecast.results.length" class="table-scroll"><table><thead><tr><th>日期</th><th>预测净现金流</th><th>预计应收</th><th>预计余额</th><th>实际金额</th><th>偏差</th><th>风险解释</th></tr></thead><tbody><tr v-for="item in forecast.results" :key="item.id"><td>{{ item.forecast_date }}</td><td :class="Number(item.forecast_amount) < 0 ? 'expense-amount' : 'income-amount'">{{ formatCurrency(item.forecast_amount) }}</td><td>{{ formatCurrency(item.expected_receivable) }}</td><td>{{ formatCurrency(item.projected_balance) }}</td><td>{{ item.actual_amount !== null ? formatCurrency(item.actual_amount) : '-' }}</td><td>{{ item.deviation_amount !== null ? formatCurrency(item.deviation_amount) : '-' }}</td><td><span class="pill" :class="`risk-${item.risk_level}`">{{ item.risk_level }} · {{ item.risk_message }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无预测结果，请先生成预测。</p><div v-if="forecast.job" class="forecast-evaluation"><strong>评估点数 {{ forecast.evaluation.evaluated_points }}</strong><span>MAE {{ formatCurrency(forecast.evaluation.mae) }}</span><span>RMSE {{ formatCurrency(forecast.evaluation.rmse) }}</span><span>平均偏差 {{ formatCurrency(forecast.evaluation.mean_deviation) }}</span></div></article>
    </section>
  </section>
</template>
