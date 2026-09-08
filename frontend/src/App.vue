<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchJson } from './services/http'

type ServiceHealth = {
  code: number
  message: string
  data: {
    service: string
    status: string
    timestamp: string
  }
  traceId: string
}

type ForecastResult = {
  code: number
  message: string
  data: {
    method: string
    baseline: number
    trend_step: number
    forecast_values: number[]
  }
  traceId: string
}

const backendBase = import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080'
const analyticsBase = import.meta.env.VITE_ANALYTICS_BASE_URL ?? 'http://localhost:8001'

const backendHealth = ref<ServiceHealth | null>(null)
const analyticsHealth = ref<ServiceHealth | null>(null)
const forecastResult = ref<ForecastResult | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const historyInput = ref('120000, 128000, 133000, 140000')

const backendStatusText = computed(() => backendHealth.value?.data.status ?? '未连接')
const analyticsStatusText = computed(() => analyticsHealth.value?.data.status ?? '未连接')

async function refreshHealth() {
  errorMessage.value = ''

  try {
    backendHealth.value = await fetchJson<ServiceHealth>(`${backendBase}/api/v1/health`)
  } catch (error) {
    backendHealth.value = null
    errorMessage.value = `后端连接失败: ${error instanceof Error ? error.message : String(error)}`
  }

  try {
    analyticsHealth.value = await fetchJson<ServiceHealth>(`${analyticsBase}/health`)
  } catch (error) {
    analyticsHealth.value = null
    errorMessage.value = `分析服务连接失败: ${error instanceof Error ? error.message : String(error)}`
  }
}

async function runForecast() {
  errorMessage.value = ''
  forecastResult.value = null

  const history = historyInput.value
    .split(/[,\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .map(Number)
    .filter((value) => Number.isFinite(value))

  if (history.length === 0) {
    errorMessage.value = '请输入至少一条历史金额'
    return
  }

  loading.value = true
  try {
    forecastResult.value = await fetchJson<ForecastResult>(`${analyticsBase}/forecast/cashflow`, {
      method: 'POST',
      body: JSON.stringify({ history, horizon: 5, window_size: 3 }),
    })
  } catch (error) {
    errorMessage.value = `预测失败: ${error instanceof Error ? error.message : String(error)}`
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await refreshHealth()
})
</script>

<template>
  <main class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">bank_forecast</p>
        <h1>银行资金预测骨架</h1>
        <p class="lead">Java 后端、Python 分析、Vue 前端的最小可启动组合。</p>
      </div>
      <button class="ghost-button" @click="refreshHealth">刷新连接</button>
    </header>

    <section class="grid two-up">
      <article class="panel">
        <h2>服务状态</h2>
        <div class="status-row">
          <span>后端</span>
          <strong>{{ backendStatusText }}</strong>
        </div>
        <div class="meta">{{ backendHealth?.data.timestamp ?? '未获取' }}</div>
        <div class="status-row">
          <span>分析服务</span>
          <strong>{{ analyticsStatusText }}</strong>
        </div>
        <div class="meta">{{ analyticsHealth?.data.timestamp ?? '未获取' }}</div>
      </article>

      <article class="panel">
        <h2>接口入口</h2>
        <ul class="links">
          <li><code>{{ backendBase }}/api/v1/health</code></li>
          <li><code>{{ analyticsBase }}/health</code></li>
          <li><code>{{ analyticsBase }}/forecast/cashflow</code></li>
        </ul>
      </article>
    </section>

    <section class="panel">
      <h2>现金流预测</h2>
      <label class="field-label" for="history-input">历史金额</label>
      <textarea
        id="history-input"
        v-model="historyInput"
        rows="4"
        class="text-area"
        placeholder="120000, 128000, 133000, 140000"
      />
      <div class="actions">
        <button class="primary-button" :disabled="loading" @click="runForecast">
          {{ loading ? '计算中...' : '生成预测' }}
        </button>
      </div>

      <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>

      <div v-if="forecastResult" class="forecast-grid">
        <div>
          <span class="label">方法</span>
          <strong>{{ forecastResult.data.method }}</strong>
        </div>
        <div>
          <span class="label">基线</span>
          <strong>{{ forecastResult.data.baseline }}</strong>
        </div>
        <div>
          <span class="label">趋势步长</span>
          <strong>{{ forecastResult.data.trend_step }}</strong>
        </div>
        <div>
          <span class="label">预测值</span>
          <strong>{{ forecastResult.data.forecast_values.join(', ') }}</strong>
        </div>
      </div>
    </section>
  </main>
</template>
