<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  batchClassifyTransactions,
  batchUnlinkTransactions,
  confirmImportPreview,
  exportTransactions,
  loadAccounts,
  loadTransactions,
  previewStatements,
  retryImportErrors,
  type BankAccount,
  type BankTransaction,
  type ImportPreview,
  type ImportPreviewRow,
} from '../services/bank'
import { previewReceipts, type ReceiptImportPayload } from '../services/receipts'
import {
  confirmImportJob,
  retryImportJobErrors,
  type GenericImportPreview,
  type GenericImportRow,
} from '../services/imports'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'

const router = useRouter()
const session = useSession()
const base = apiBase()
const accounts = ref<BankAccount[]>([])
const transactions = ref<BankTransaction[]>([])
const preview = ref<ImportPreview | null>(null)
const file = ref<File | null>(null)
const accountId = ref<number | null>(null)
const loading = ref(false)
const message = ref('')
const retryJson = ref('')
const page = ref(1)

function prevPage() {
  page.value--
  load()
}

function nextPage() {
  page.value++
  load()
}
const total = ref(0)
const pageSize = ref(20)
const selectedAccount = computed(() => accounts.value.find((item) => item.id === accountId.value))
const receiptFile = ref<File | null>(null)
const receiptPreview = ref<GenericImportPreview<ReceiptImportPayload> | null>(null)
const receiptRetryJson = ref('')
const receiptLoading = ref(false)
const receiptMessage = ref('')
const filterKeyword = ref('')
const filterAmountMin = ref('')
const filterAmountMax = ref('')
const filterDateFrom = ref('')
const filterDateTo = ref('')
const selected = ref<number[]>([])
const batchCategory = ref('')
const batchPurpose = ref('')
const batchReason = ref('')
const batchLoading = ref(false)

function activeFilters() {
  return {
    keyword: filterKeyword.value,
    amount_min: filterAmountMin.value,
    amount_max: filterAmountMax.value,
    date_from: filterDateFrom.value,
    date_to: filterDateTo.value,
  }
}
async function load() {
  if (!session.user.value || !session.token.value) return
  const [a, t] = await Promise.all([
    loadAccounts(base, session.token.value, session.user.value.tenant_id),
    loadTransactions(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
      activeFilters(),
    ),
  ])
  accounts.value = a.data.items
  transactions.value = t.data.items
  total.value = t.data.total
  selected.value = []
  if (!accountId.value) accountId.value = accounts.value[0]?.id ?? null
}
function applyFilters() {
  page.value = 1
  load()
}
function resetFilters() {
  filterKeyword.value = ''
  filterAmountMin.value = ''
  filterAmountMax.value = ''
  filterDateFrom.value = ''
  filterDateTo.value = ''
  page.value = 1
  load()
}
async function batchClassify() {
  if (!session.user.value || !session.token.value || !selected.value.length) return
  if (!batchCategory.value.trim()) {
    message.value = '请输入批量分类'
    return
  }
  batchLoading.value = true
  try {
    const result = await batchClassifyTransactions(
      base,
      session.token.value,
      session.user.value.tenant_id,
      selected.value,
      batchCategory.value.trim(),
      batchPurpose.value.trim(),
      '',
    )
    message.value = `已批量分类 ${result.data.updated} 条流水。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '批量分类失败'
  } finally {
    batchLoading.value = false
  }
}
async function batchUnlink() {
  if (!session.user.value || !session.token.value || !selected.value.length) return
  if (!batchReason.value.trim()) {
    message.value = '请输入解除关联原因'
    return
  }
  batchLoading.value = true
  try {
    const result = await batchUnlinkTransactions(
      base,
      session.token.value,
      session.user.value.tenant_id,
      selected.value,
      batchReason.value.trim(),
    )
    message.value = `已解除 ${result.data.processed} 条流水的关联，回滚应收 ${result.data.rolled_back_plans} 条。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '批量解除关联失败'
  } finally {
    batchLoading.value = false
  }
}
async function exportFile(format: 'csv' | 'xlsx') {
  if (!session.user.value || !session.token.value) return
  try {
    const blob = await exportTransactions(base, session.token.value, session.user.value.tenant_id, {
      ...activeFilters(),
      format,
    })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `bank-transactions.${format}`
    link.click()
    URL.revokeObjectURL(link.href)
    message.value = format === 'xlsx' ? 'Excel 导出成功。' : 'CSV 导出成功。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '导出失败'
  }
}
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  message.value = ''
  preview.value = null
}
async function startPreview() {
  if (!session.user.value || !session.token.value || !accountId.value || !file.value) {
    message.value = '请选择银行账户和 CSV/XLSX 文件'
    return
  }
  loading.value = true
  try {
    preview.value = (
      await previewStatements(
        base,
        session.token.value,
        session.user.value.tenant_id,
        accountId.value,
        file.value,
      )
    ).data
    message.value = '预览完成，请核对后确认入账。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '预览失败'
  } finally {
    loading.value = false
  }
}
async function confirm() {
  if (!session.user.value || !session.token.value || !preview.value) return
  loading.value = true
  try {
    preview.value = (
      await confirmImportPreview(
        base,
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
      )
    ).data
    message.value = '已确认入账。'
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认失败'
  } finally {
    loading.value = false
  }
}
async function retry() {
  if (!session.user.value || !session.token.value || !preview.value) return
  try {
    preview.value = (
      await retryImportErrors(
        base,
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
function payload(rows: ImportPreviewRow[]) {
  return JSON.stringify(
    rows
      .filter((row) => row.status === 'failed')
      .map((row) => ({
        row_no: row.row_no,
        transaction_no: row.transaction_no ?? '',
        transaction_date: row.transaction_date ?? '',
        direction: row.direction ?? 'income',
        amount: row.amount ?? '',
      })),
    null,
    2,
  )
}
function changePageSize() {
  page.value = 1
  load()
}
function chooseReceiptFile(event: Event) {
  receiptFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  receiptMessage.value = ''
  receiptPreview.value = null
  receiptRetryJson.value = ''
}
const receiptPreviewActionable = computed(
  () =>
    receiptPreview.value !== null &&
    ['preview_pending', 'preview_failed'].includes(receiptPreview.value.status),
)
async function startReceiptPreview() {
  if (!session.user.value || !session.token.value || !receiptFile.value) {
    receiptMessage.value = '请选择回单 CSV 文件'
    return
  }
  receiptLoading.value = true
  try {
    receiptPreview.value = (
      await previewReceipts(
        base,
        session.token.value,
        session.user.value.tenant_id,
        receiptFile.value,
      )
    ).data
    receiptMessage.value =
      receiptPreview.value.status === 'preview_failed'
        ? '预览校验失败，请修正失败行后重新校验。'
        : '预览完成，请核对后确认导入。'
  } catch (cause) {
    receiptMessage.value = cause instanceof Error ? cause.message : '回单预览失败'
  } finally {
    receiptLoading.value = false
  }
}
async function confirmReceiptPreview() {
  if (!session.user.value || !session.token.value || !receiptPreview.value) return
  receiptLoading.value = true
  try {
    receiptPreview.value = (
      await confirmImportJob<ReceiptImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        receiptPreview.value.job_id,
      )
    ).data
    receiptMessage.value = '回单导入已确认。'
  } catch (cause) {
    receiptMessage.value = cause instanceof Error ? cause.message : '确认导入失败'
  } finally {
    receiptLoading.value = false
  }
}
async function retryReceiptErrors() {
  if (!session.user.value || !session.token.value || !receiptPreview.value) return
  try {
    receiptPreview.value = (
      await retryImportJobErrors<ReceiptImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        receiptPreview.value.job_id,
        JSON.parse(receiptRetryJson.value),
      )
    ).data
    receiptRetryJson.value = ''
    receiptMessage.value = '失败行已重新校验。'
  } catch (cause) {
    receiptMessage.value = cause instanceof Error ? cause.message : '失败行格式不正确'
  }
}
function receiptRetryPlaceholder(rows: Array<GenericImportRow<ReceiptImportPayload>>) {
  return JSON.stringify(
    rows
      .filter((row) => row.status === 'failed')
      .map((row) => ({
        row_no: row.row_no,
        receipt_no: row.payload?.receipt_no ?? '',
        transaction_date: row.payload?.transaction_date ?? '',
        amount: row.payload?.amount ?? '',
        transaction_no: row.payload?.transaction_no ?? '',
      })),
    null,
    2,
  )
}
onMounted(() => {
  load()
  window.addEventListener('workspace-refresh', load)
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">银行流水</p>
        <h2>流水列表</h2>
        <p class="lead">先预览导入，再进入独立任务页确认入账。</p>
      </div>
      <span class="meta">共 {{ total }} 条</span>
    </header>
    <section class="grid transaction-layout">
      <article class="panel import-panel">
        <h3>导入流水</h3>
        <label
          >银行账户<select v-model="accountId">
            <option v-for="account in accounts" :key="account.id" :value="account.id">
              {{ account.bank_name }} · {{ account.account_name }} · {{ account.account_no_last4 }}
            </option>
          </select></label
        ><label
          >CSV / Excel 文件<input
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            type="file"
            @change="chooseFile"
        /></label>
        <p v-if="selectedAccount" class="meta">
          当前余额 {{ formatCurrency(selectedAccount.current_balance) }}
        </p>
        <button
          v-permission="'transaction:import'"
          class="primary-button"
          :disabled="loading"
          type="button"
          @click="startPreview"
        >
          {{ loading ? '处理中...' : '预览导入' }}
        </button>
        <p v-if="message" class="feedback-text">{{ message }}</p>
        <div v-if="preview" class="import-summary">
          <strong>任务 #{{ preview.job_id }}</strong
          ><span>有效 {{ preview.success_rows }}</span
          ><span>失败 {{ preview.failed_rows }}</span
          ><span>跳过 {{ preview.skipped_rows }}</span
          ><RouterLink class="text-button" :to="`/imports/${preview.job_id}`"
            >打开任务详情</RouterLink
          >
        </div>
        <template v-if="preview?.failed_rows"
          ><label
            >失败行修正 JSON<textarea
              v-model="retryJson"
              rows="5"
              :placeholder="payload(preview.preview_rows)"
            /></label
          ><button
            v-permission="'transaction:import'"
            class="ghost-button"
            type="button"
            @click="retry"
          >
            重新校验失败行
          </button></template
        ><button
          v-if="preview && ['preview_pending', 'preview_failed'].includes(preview.status)"
          v-permission="'transaction:import'"
          class="primary-button"
          :disabled="loading || preview.success_rows === 0"
          type="button"
          @click="confirm"
        >
          确认入账
        </button>
      </article>
      <article class="panel import-panel">
        <h3>导入回单</h3>
        <label
          >回单 CSV 文件<input accept=".csv,text/csv" type="file" @change="chooseReceiptFile"
        /></label>
        <p v-if="receiptFile" class="meta">已选择：{{ receiptFile.name }}</p>
        <button
          v-permission="'receipt:import'"
          class="primary-button"
          :disabled="receiptLoading"
          type="button"
          @click="startReceiptPreview"
        >
          {{ receiptLoading ? '处理中...' : '预览导入' }}
        </button>
        <p v-if="receiptMessage" class="feedback-text">{{ receiptMessage }}</p>
        <div v-if="receiptPreview" class="import-summary">
          <strong>任务 #{{ receiptPreview.job_id }}</strong
          ><span>有效 {{ receiptPreview.success_rows }}</span
          ><span>失败 {{ receiptPreview.failed_rows }}</span
          ><span>跳过 {{ receiptPreview.skipped_rows }}</span
          ><RouterLink class="text-button" :to="`/imports/${receiptPreview.job_id}`"
            >打开任务详情</RouterLink
          >
        </div>
        <template v-if="receiptPreview && receiptPreviewActionable && receiptPreview.failed_rows"
          ><label
            >失败行修正 JSON<textarea
              v-model="receiptRetryJson"
              rows="5"
              :placeholder="receiptRetryPlaceholder(receiptPreview.preview_rows)"
            /></label
          ><button
            v-permission="'receipt:import'"
            class="ghost-button"
            type="button"
            @click="retryReceiptErrors"
          >
            重新校验失败行
          </button></template
        ><button
          v-if="receiptPreview && receiptPreviewActionable"
          v-permission="'receipt:import'"
          class="primary-button"
          :disabled="receiptLoading || receiptPreview.success_rows === 0"
          type="button"
          @click="confirmReceiptPreview"
        >
          确认导入
        </button>
        <p class="meta import-hint">
          回单号、交易日期、金额必填；填写交易流水号时必须匹配已导入流水，重复回单号自动跳过；影像文件在流水详情页逐条上传。
        </p>
      </article>
      <article class="panel table-panel">
        <div class="section-heading">
          <div>
            <h3>流水明细</h3>
            <span class="meta">服务端分页，支持关键字、金额区间和日期筛选</span>
          </div>
          <div class="action-group">
            <button
              v-permission="'transaction:export'"
              class="ghost-button"
              type="button"
              @click="exportFile('csv')"
            >
              导出 CSV</button
            ><button
              v-permission="'transaction:export'"
              class="ghost-button"
              type="button"
              @click="exportFile('xlsx')"
            >
              导出 Excel
            </button>
          </div>
        </div>
        <div class="filter-bar">
          <label
            >关键字<input
              v-model.trim="filterKeyword"
              placeholder="流水号/对方户名/摘要/合同编号/项目名称"
          /></label>
          <label>金额下限<input v-model.trim="filterAmountMin" type="number" step="0.01" /></label>
          <label>金额上限<input v-model.trim="filterAmountMax" type="number" step="0.01" /></label>
          <label>开始日期<input v-model="filterDateFrom" type="date" /></label>
          <label>结束日期<input v-model="filterDateTo" type="date" /></label>
          <button class="small-primary-button" type="button" @click="applyFilters">查询</button>
          <button class="ghost-button" type="button" @click="resetFilters">重置</button>
        </div>
        <div v-if="selected.length" class="filter-bar">
          <span class="meta">已选 {{ selected.length }} 条</span>
          <label>分类<input v-model.trim="batchCategory" placeholder="如：客户回款" /></label>
          <label>用途<input v-model.trim="batchPurpose" placeholder="选填" /></label>
          <button
            v-permission="'transaction:import'"
            class="small-primary-button"
            :disabled="batchLoading"
            type="button"
            @click="batchClassify"
          >
            批量分类</button
          ><label>解除原因<input v-model.trim="batchReason" placeholder="必填" /></label>
          <button
            v-permission="'transaction:import'"
            class="ghost-button"
            :disabled="batchLoading"
            type="button"
            @click="batchUnlink"
          >
            批量解除关联
          </button>
        </div>
        <div v-if="transactions.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>选择</th>
                <th>交易日期</th>
                <th>银行/账户</th>
                <th>方向</th>
                <th>金额</th>
                <th>对方户名</th>
                <th>摘要</th>
                <th>分类</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in transactions" :key="item.id">
                <td><input v-model="selected" type="checkbox" :value="item.id" /></td>
                <td>{{ item.transaction_date }}</td>
                <td>{{ item.bank_name || '-' }}·{{ item.account_no_last4 || '-' }}</td>
                <td>{{ item.direction }}</td>
                <td :class="item.direction === 'expense' ? 'expense-amount' : 'income-amount'">
                  {{ formatCurrency(item.amount) }}
                </td>
                <td>{{ item.counterparty_name || '-' }}</td>
                <td>{{ item.summary || '-' }}</td>
                <td>{{ item.category || '-' }}</td>
                <td>
                  <span class="pill">{{ item.match_status }}</span>
                </td>
                <td>
                  <button
                    class="text-button"
                    type="button"
                    @click="router.push(`/transactions/${item.id}`)"
                  >
                    查看详情
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">暂无流水。</p>
        <div class="pagination-controls">
          <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span
          ><label class="page-size-control"
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
  </section>
</template>
