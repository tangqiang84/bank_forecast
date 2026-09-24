<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import BusinessDetail from '../components/BusinessDetail.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import {
  importFinanceRecords,
  loadReconciliationResults,
  runReconciliation,
  type ReconciliationResult,
  type ReconciliationSummary,
} from '../services/finance'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'

const route = useRoute()
const session = useSession()
const file = ref<File | null>(null)
const dateFrom = ref('')
const dateTo = ref('')
const differenceType = ref('')
const page = ref(1)

function prevPage() {
  page.value--
  loadResults()
}

function nextPage() {
  page.value++
  loadResults()
}

function resetAndReload() {
  page.value = 1
  loadResults()
}
const pageSize = ref(20)
const total = ref(0)
const results = ref<ReconciliationResult[]>([])
const summary = ref<ReconciliationSummary | null>(null)
const selected = ref<ReconciliationResult | null>(null)
const loading = ref(false)
const importLoading = ref(false)
const error = ref('')
const message = ref('')

const jobId = computed(() => {
  const raw = route.params.jobId
  return raw ? Number(raw) : summary.value?.job_id
})

async function loadResults() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await loadReconciliationResults(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      jobId.value,
      page.value,
      pageSize.value,
      differenceType.value,
    )
    results.value = response.data.items
    total.value = response.data.total
    if (response.data.job)
      summary.value = { ...summary.value, ...response.data.job } as ReconciliationSummary
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '对账结果加载失败'
  } finally {
    loading.value = false
  }
}

function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  message.value = ''
}

async function importRecords() {
  if (!file.value || !session.user.value || !session.token.value) {
    message.value = '请选择财务记录 CSV 文件'
    return
  }
  importLoading.value = true
  message.value = ''
  try {
    const response = await importFinanceRecords(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      file.value,
    )
    message.value = `导入完成：成功 ${response.data.success_rows ?? 0} 行，失败 ${response.data.failed_rows ?? 0} 行。`
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '财务记录导入失败'
  } finally {
    importLoading.value = false
  }
}

async function run() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    const filters: Record<string, string> = {}
    if (dateFrom.value) filters.date_from = dateFrom.value
    if (dateTo.value) filters.date_to = dateTo.value
    const response = await runReconciliation(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      filters,
    )
    summary.value = response.data
    page.value = 1
    await loadResults()
    message.value = `对账完成：匹配 ${response.data.matched} 条，银行未记账 ${response.data.bank_unrecorded} 条，财务未在银行发生 ${response.data.finance_unmatched} 条。`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '对账失败'
  } finally {
    loading.value = false
  }
}

function changePageSize() {
  page.value = 1
  loadResults()
}

function openDetail(item: ReconciliationResult) {
  selected.value = item
}

onMounted(loadResults)
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">财务对账</p>
        <h2>
          {{ route.params.jobId ? `对账任务 #${route.params.jobId}` : '银行账 / 财务账对账' }}
        </h2>
        <p class="lead">导入财务记录后，按金额、方向、对手方和 3 天日期窗口生成可追溯差异。</p>
      </div>
      <RouterLink v-if="route.params.jobId" class="ghost-button" to="/reconciliation"
        >返回对账工作台</RouterLink
      >
    </header>

    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>

    <section class="grid reconciliation-layout">
      <article class="panel workflow-panel">
        <h3>对账准备</h3>
        <label>财务记录 CSV<input type="file" accept=".csv,text/csv" @change="chooseFile" /></label>
        <button
          v-permission="'reconciliation:run'"
          class="primary-button"
          :disabled="importLoading"
          type="button"
          @click="importRecords"
        >
          {{ importLoading ? '导入中...' : '导入财务记录' }}
        </button>
        <div class="detail-section">
          <h3>运行范围</h3>
          <label>开始日期<input v-model="dateFrom" type="date" /></label>
          <label>结束日期<input v-model="dateTo" type="date" /></label>
          <button
            v-permission="'reconciliation:run'"
            class="primary-button"
            :disabled="loading"
            type="button"
            @click="run"
          >
            {{ loading ? '对账中...' : '运行对账' }}
          </button>
        </div>
        <div v-if="summary" class="summary-grid compact-summary">
          <div>
            <span>匹配</span><strong>{{ summary.matched }}</strong>
          </div>
          <div>
            <span>银行未记账</span><strong>{{ summary.bank_unrecorded }}</strong>
          </div>
          <div>
            <span>财务未发生</span><strong>{{ summary.finance_unmatched }}</strong>
          </div>
        </div>
      </article>

      <article class="panel table-panel">
        <div class="section-heading">
          <div>
            <h3>差异结果</h3>
            <span class="meta">{{ total }} 条，服务端分页</span>
          </div>
          <select v-model="differenceType" aria-label="差异类型" @change="resetAndReload">
            <option value="">全部差异</option>
            <option value="bank_unrecorded">银行未记账</option>
            <option value="finance_unmatched">财务未在银行发生</option>
          </select>
        </div>
        <div v-if="results.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>差异类型</th>
                <th>来源编号</th>
                <th>日期</th>
                <th>金额</th>
                <th>业务说明</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in results" :key="item.id">
                <td>
                  <span class="pill">{{
                    item.exception_type === 'bank_unrecorded' ? '银行未记账' : '财务未在银行发生'
                  }}</span>
                </td>
                <td>{{ item.transaction_no || item.record_no || '-' }}</td>
                <td>{{ item.transaction_date || item.record_date || '-' }}</td>
                <td>{{ formatCurrency(item.bank_amount || item.finance_amount || '0') }}</td>
                <td>
                  <strong>{{ item.title }}</strong
                  ><br /><span class="meta">{{ item.description }}</span>
                </td>
                <td>{{ item.status }}</td>
                <td>
                  <button class="text-button" type="button" @click="openDetail(item)">
                    查看详情
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">暂无对账差异，请先导入财务记录并运行对账。</p>
        <div class="pagination-controls">
          <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span
          ><label
            >每页<select v-model.number="pageSize" @change="changePageSize">
              <option :value="20">20</option>
              <option :value="50">50</option>
              <option :value="100">100</option></select
            >条</label
          ><button class="ghost-button" :disabled="page <= 1" type="button" @click="prevPage">
            上一页</button
          ><button
            class="ghost-button"
            :disabled="page >= Math.ceil(total / pageSize)"
            type="button"
            @click="nextPage"
          >
            下一页
          </button>
        </div>
      </article>
    </section>

    <DetailDrawer v-if="selected" title="对账差异详情" @close="selected = null"
      ><BusinessDetail :data="{ reconciliation: selected }"
    /></DetailDrawer>
  </section>
</template>
