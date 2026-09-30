<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import BusinessDetail from '../components/BusinessDetail.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import ModalPanel from '../components/ModalPanel.vue'
import {
  previewFinanceRecords,
  loadFinanceRecords,
  loadReconciliationResults,
  runReconciliation,
  type FinanceRecord,
  type FinanceRecordImportPayload,
  type ReconciliationResult,
  type ReconciliationSummary,
} from '../services/finance'
import {
  confirmImportJob,
  retryImportJobErrors,
  type GenericImportPreview,
  type GenericImportRow,
} from '../services/imports'
import { apiBase, useSession } from '../session'
import {
  exceptionStatusLabel,
  exceptionTypeLabel,
  importJobStatusLabel,
  importRowStatusLabel,
  recordTypeLabel,
} from '../utils/labels'
import { formatCurrency } from '../utils/number'

const route = useRoute()
const session = useSession()
const file = ref<File | null>(null)
const preview = ref<GenericImportPreview<FinanceRecordImportPayload> | null>(null)
const retryJson = ref('')
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
const reconModalOpen = ref(false)

function openRecon() {
  reconModalOpen.value = true
}

function closeRecon() {
  reconModalOpen.value = false
}

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
  preview.value = null
  retryJson.value = ''
}

const previewActionable = computed(
  () =>
    preview.value !== null && ['preview_pending', 'preview_failed'].includes(preview.value.status),
)

async function startPreview() {
  if (!file.value || !session.user.value || !session.token.value) {
    message.value = '请选择财务记录 CSV 文件'
    return
  }
  importLoading.value = true
  message.value = ''
  try {
    preview.value = (
      await previewFinanceRecords(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        file.value,
      )
    ).data
    message.value =
      preview.value.status === 'preview_failed'
        ? '预览校验失败，请修正失败行后重新校验。'
        : '预览完成，请核对后确认导入。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '财务记录预览失败'
  } finally {
    importLoading.value = false
  }
}

async function confirmPreview() {
  if (!preview.value || !session.user.value || !session.token.value) return
  importLoading.value = true
  try {
    preview.value = (
      await confirmImportJob<FinanceRecordImportPayload>(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
      )
    ).data
    message.value = '导入已确认，财务记录已入库。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认导入失败'
  } finally {
    importLoading.value = false
  }
}

async function retryErrors() {
  if (!preview.value || !session.user.value || !session.token.value) return
  try {
    preview.value = (
      await retryImportJobErrors<FinanceRecordImportPayload>(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
        JSON.parse(retryJson.value),
      )
    ).data
    retryJson.value = ''
    message.value = '失败行已重新校验。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '失败行格式不正确'
  }
}

function retryPlaceholder(rows: Array<GenericImportRow<FinanceRecordImportPayload>>) {
  return JSON.stringify(
    rows
      .filter((row) => row.status === 'failed')
      .map((row) => ({
        row_no: row.row_no,
        record_no: row.payload?.record_no ?? '',
        record_type: row.payload?.record_type ?? '',
        record_date: row.payload?.record_date ?? '',
        counterparty_name: row.payload?.counterparty_name ?? '',
        amount: row.payload?.amount ?? '',
        subject: row.payload?.subject ?? '',
      })),
    null,
    2,
  )
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
    await loadRecords()
    message.value = `对账完成：一对一匹配 ${response.data.matched} 条，多对多匹配 ${response.data.multi_matched ?? 0} 组，跨月时间差 ${response.data.timing_difference ?? 0} 条，银行未记账 ${response.data.bank_unrecorded} 条，财务未在银行发生 ${response.data.finance_unmatched} 条。`
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

const recordPage = ref(1)
const recordPageSize = ref(20)
const recordTotal = ref(0)
const records = ref<FinanceRecord[]>([])
const recordSubject = ref('')
const recordType = ref('')

async function loadRecords() {
  if (!session.user.value || !session.token.value) return
  try {
    const response = await loadFinanceRecords(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      recordPage.value,
      recordPageSize.value,
      { subject: recordSubject.value, record_type: recordType.value },
    )
    records.value = response.data.items
    recordTotal.value = response.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '财务记录加载失败'
  }
}

function applyRecordFilters() {
  recordPage.value = 1
  loadRecords()
}

function recordPrevPage() {
  recordPage.value--
  loadRecords()
}

function recordNextPage() {
  recordPage.value++
  loadRecords()
}

function recordChangeSize() {
  recordPage.value = 1
  loadRecords()
}

onMounted(() => {
  if (route.params.jobId) reconModalOpen.value = true
  loadResults()
  loadRecords()
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">财务对账</p>
        <h2>
          {{ route.params.jobId ? `对账任务 #${route.params.jobId}` : '银行账 / 财务账对账' }}
        </h2>
        <p class="lead">
          导入财务记录后，按金额、方向、对手方进行一对一、多对多合计匹配，跨月时间差单独标记。
        </p>
      </div>
      <RouterLink v-if="route.params.jobId" class="ghost-button" to="/reconciliation"
        >返回对账工作台</RouterLink
      >
    </header>

    <p v-if="error && !reconModalOpen" class="error-banner">{{ error }}</p>
    <p v-if="message && !reconModalOpen" class="feedback-text">{{ message }}</p>
    <div class="page-toolbar">
      <button class="ghost-button" type="button" @click="openRecon">对账</button>
    </div>

    <ModalPanel v-if="reconModalOpen" title="银行账 / 财务账对账" wide @close="closeRecon">
      <section>
        <h3>对账准备</h3>
        <p v-if="error" class="error-banner">{{ error }}</p>
        <p v-if="message" class="feedback-text">{{ message }}</p>
        <label>财务记录 CSV<input type="file" accept=".csv,text/csv" @change="chooseFile" /></label>
        <button
          v-permission="'reconciliation:run'"
          class="primary-button"
          :disabled="importLoading"
          type="button"
          @click="startPreview"
        >
          {{ importLoading ? '处理中...' : '预览导入' }}
        </button>
        <div v-if="preview" class="import-summary">
          <strong>任务 #{{ preview.job_id }}</strong
          ><span>有效 {{ preview.success_rows }}</span
          ><span>失败 {{ preview.failed_rows }}</span
          ><span>跳过 {{ preview.skipped_rows }}</span
          ><RouterLink class="text-button" :to="`/imports/${preview.job_id}`"
            >打开任务详情</RouterLink
          >
        </div>
        <template v-if="preview && previewActionable && preview.failed_rows">
          <label
            >失败行修正 JSON<textarea
              v-model="retryJson"
              rows="5"
              :placeholder="retryPlaceholder(preview.preview_rows)"
            />
          </label>
          <button
            v-permission="'reconciliation:run'"
            class="ghost-button"
            type="button"
            @click="retryErrors"
          >
            重新校验失败行
          </button>
        </template>
        <button
          v-if="preview && previewActionable"
          v-permission="'reconciliation:run'"
          class="primary-button"
          :disabled="importLoading || preview.success_rows === 0"
          type="button"
          @click="confirmPreview"
        >
          确认导入
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
            <span>一对一匹配</span><strong>{{ summary.matched }}</strong>
          </div>
          <div>
            <span>多对多匹配</span><strong>{{ summary.multi_matched ?? 0 }}</strong>
          </div>
          <div>
            <span>跨月时间差</span><strong>{{ summary.timing_difference ?? 0 }}</strong>
          </div>
          <div>
            <span>银行未记账</span><strong>{{ summary.bank_unrecorded }}</strong>
          </div>
          <div>
            <span>财务未发生</span><strong>{{ summary.finance_unmatched }}</strong>
          </div>
        </div>
      </section>

      <section>
        <div class="section-heading">
          <div>
            <h3>差异结果</h3>
            <span class="meta">{{ total }} 条，服务端分页</span>
          </div>
          <select v-model="differenceType" aria-label="差异类型" @change="resetAndReload">
            <option value="">全部差异</option>
            <option value="bank_unrecorded">银行未记账</option>
            <option value="finance_unmatched">财务未在银行发生</option>
            <option value="timing_difference">跨月时间差</option>
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
                <th>科目</th>
                <th>业务说明</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in results" :key="item.id">
                <td>
                  <span class="pill">{{ exceptionTypeLabel(item.exception_type) }}</span>
                </td>
                <td>{{ item.transaction_no || item.record_no || '-' }}</td>
                <td>{{ item.transaction_date || item.record_date || '-' }}</td>
                <td>{{ formatCurrency(item.bank_amount || item.finance_amount || '0') }}</td>
                <td>{{ item.finance_subject || '-' }}</td>
                <td>
                  <strong>{{ item.title }}</strong
                  ><br /><span class="meta">{{ item.description }}</span>
                </td>
                <td>{{ exceptionStatusLabel(item.status) }}</td>
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
      </section>

      <section v-if="preview">
        <h3>导入预览行</h3>
        <p class="meta">任务 #{{ preview.job_id }} · {{ importJobStatusLabel(preview.status) }}</p>
        <div v-if="preview.preview_rows.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>行号</th>
                <th>记录编号</th>
                <th>类型</th>
                <th>记录日期</th>
                <th>对手方</th>
                <th>金额</th>
                <th>科目</th>
                <th>状态</th>
                <th>错误原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in preview.preview_rows" :key="row.id">
                <td>{{ row.row_no }}</td>
                <td>{{ row.payload?.record_no || '-' }}</td>
                <td>{{ recordTypeLabel(row.payload?.record_type) }}</td>
                <td>{{ row.payload?.record_date || '-' }}</td>
                <td>{{ row.payload?.counterparty_name || '-' }}</td>
                <td>{{ row.payload?.amount || '-' }}</td>
                <td>{{ row.payload?.subject || '-' }}</td>
                <td>
                  <span class="pill">{{ importRowStatusLabel(row.status) }}</span>
                </td>
                <td>{{ row.error_message || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">本次预览没有可展示的行。</p>
      </section>

      <template #footer>
        <button class="ghost-button" type="button" @click="closeRecon">关闭</button>
      </template>
    </ModalPanel>

    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>财务记录</h3>
          <span class="meta">{{ recordTotal }} 条，服务端分页</span>
        </div>
        <div class="filter-bar">
          <label>科目<input v-model.trim="recordSubject" placeholder="如：应收账款" /></label>
          <label
            >类型<select v-model="recordType">
              <option value="">全部类型</option>
              <option value="receipt">收款单</option>
              <option value="payment">付款单</option>
              <option value="voucher">凭证</option>
              <option value="journal">日记账</option>
            </select></label
          >
          <button class="small-primary-button" type="button" @click="applyRecordFilters">
            查询
          </button>
        </div>
      </div>
      <div v-if="records.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>记录编号</th>
              <th>类型</th>
              <th>记录日期</th>
              <th>对手方</th>
              <th>金额</th>
              <th>科目</th>
              <th>来源系统</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in records" :key="record.id">
              <td>{{ record.record_no }}</td>
              <td>{{ recordTypeLabel(record.record_type) }}</td>
              <td>{{ record.record_date }}</td>
              <td>{{ record.counterparty_name || '-' }}</td>
              <td>{{ formatCurrency(record.amount) }}</td>
              <td>{{ record.subject || '-' }}</td>
              <td>{{ record.source_system || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无财务记录，请先导入财务 CSV。</p>
      <div class="pagination-controls">
        <span
          >第 {{ recordPage }} / {{ Math.max(1, Math.ceil(recordTotal / recordPageSize)) }} 页，共
          {{ recordTotal }} 条</span
        ><label
          >每页<select v-model.number="recordPageSize" @change="recordChangeSize">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option></select
          >条</label
        ><button
          class="ghost-button"
          :disabled="recordPage <= 1"
          type="button"
          @click="recordPrevPage"
        >
          上一页</button
        ><button
          class="ghost-button"
          :disabled="recordPage >= Math.ceil(recordTotal / recordPageSize)"
          type="button"
          @click="recordNextPage"
        >
          下一页
        </button>
      </div>
    </article>

    <DetailDrawer v-if="selected" title="对账差异详情" @close="selected = null"
      ><BusinessDetail :data="{ reconciliation: selected }"
    /></DetailDrawer>
  </section>
</template>
