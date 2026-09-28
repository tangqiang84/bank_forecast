<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  confirmImportJob,
  downloadImportJobErrors,
  loadImportJobPreview,
  retryImportJobErrors,
  type ImportJobPreview,
  type ImportJobPreviewRow,
} from '../services/imports'
import { apiBase, useSession } from '../session'

const route = useRoute()
const session = useSession()
const job = ref<ImportJobPreview | null>(null)
const retryJson = ref('')
const loading = ref(false)
const message = ref('')
const error = ref('')

type PreviewColumn = { key: string; label: string }
const BUSINESS_COLUMNS: Record<string, PreviewColumn[]> = {
  bank_statement: [
    { key: 'transaction_no', label: '流水号' },
    { key: 'transaction_date', label: '日期' },
    { key: 'direction', label: '方向' },
    { key: 'amount', label: '金额' },
  ],
  contract: [
    { key: 'contract_no', label: '合同编号' },
    { key: 'contract_name', label: '合同名称' },
    { key: 'customer_name', label: '客户' },
    { key: 'node_name', label: '节点' },
    { key: 'due_date', label: '应收日期' },
    { key: 'plan_amount', label: '计划金额' },
  ],
  finance_record: [
    { key: 'record_no', label: '记录编号' },
    { key: 'record_type', label: '类型' },
    { key: 'record_date', label: '记录日期' },
    { key: 'counterparty_name', label: '对手方' },
    { key: 'amount', label: '金额' },
  ],
  project: [
    { key: 'project_no', label: '项目编号' },
    { key: 'project_name', label: '项目名称' },
    { key: 'customer_name', label: '客户' },
    { key: 'project_manager', label: '负责人' },
    { key: 'project_status', label: '状态' },
    { key: 'start_date', label: '开始日期' },
    { key: 'delivery_date', label: '交付日期' },
    { key: 'acceptance_date', label: '验收日期' },
  ],
  receipt: [
    { key: 'receipt_no', label: '回单号' },
    { key: 'transaction_date', label: '交易日期' },
    { key: 'payer_name', label: '付款方' },
    { key: 'payee_name', label: '收款方' },
    { key: 'amount', label: '金额' },
    { key: 'transaction_no', label: '关联流水号' },
  ],
}
const businessColumns = computed<PreviewColumn[]>(
  () => BUSINESS_COLUMNS[job.value?.job_type ?? ''] ?? [],
)
const actionable = computed(
  () => job.value !== null && ['preview_pending', 'preview_failed'].includes(job.value.status),
)

function fieldValue(row: ImportJobPreviewRow, key: string): string {
  const flatFields: Record<string, string | null | undefined> = {
    transaction_no: row.transaction_no,
    transaction_date: row.transaction_date,
    direction: row.direction,
    amount: row.amount,
  }
  return row.payload?.[key] ?? flatFields[key] ?? '-'
}

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    job.value = (
      await loadImportJobPreview(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        Number(route.params.jobId),
      )
    ).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '导入任务加载失败'
  } finally {
    loading.value = false
  }
}
async function confirm() {
  if (!session.user.value || !session.token.value || !job.value) return
  loading.value = true
  try {
    job.value = (
      await confirmImportJob(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        job.value.job_id,
      )
    ).data
    message.value = '任务已确认入账。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认失败'
  } finally {
    loading.value = false
  }
}
async function retry() {
  if (!session.user.value || !session.token.value || !job.value) return
  try {
    job.value = (
      await retryImportJobErrors(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        job.value.job_id,
        JSON.parse(retryJson.value),
      )
    ).data
    retryJson.value = ''
    message.value = '失败行重试完成。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '失败行 JSON 不正确'
  }
}
async function downloadErrors() {
  if (!session.user.value || !session.token.value || !job.value) return
  try {
    const blob = await downloadImportJobErrors(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      job.value.job_id,
    )
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `import-job-${job.value.job_id}-errors.csv`
    link.click()
    URL.revokeObjectURL(link.href)
    message.value = '错误明细 CSV 已导出。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '错误明细导出失败'
  }
}
onMounted(load)
</script>
<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">导入任务</p>
        <h2>任务 #{{ route.params.jobId }}</h2>
        <p class="lead">查看模板识别、行级校验、确认入账和重试结果。</p>
      </div>
      <RouterLink class="ghost-button" to="/transactions">返回流水列表</RouterLink>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <article v-if="job" class="panel">
      <div class="import-summary">
        <strong>{{ job.status }}</strong
        ><span>总行数 {{ job.total_rows }}</span
        ><span>有效 {{ job.success_rows }}</span
        ><span>失败 {{ job.failed_rows }}</span
        ><span>跳过 {{ job.skipped_rows }}</span>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <section v-if="job.recognized_templates?.length" class="detail-section">
        <h3>模板识别</h3>
        <div class="mini-list">
          <div
            v-for="template in job.recognized_templates"
            :key="template.sheet_name"
            class="mini-row"
          >
            <span>{{ template.sheet_name }} · {{ template.bank_name || '未识别' }}</span
            ><span class="pill">{{ template.status }}</span>
          </div>
        </div>
      </section>
      <section class="detail-section">
        <h3>预览行</h3>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>行号</th>
                <th v-for="column in businessColumns" :key="column.key">{{ column.label }}</th>
                <th>状态</th>
                <th>错误</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in job.preview_rows" :key="row.id">
                <td>{{ row.row_no }}</td>
                <td v-for="column in businessColumns" :key="column.key">
                  {{ fieldValue(row, column.key) }}
                </td>
                <td>
                  <span class="pill">{{ row.status }}</span>
                </td>
                <td>{{ row.error_message || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <section v-if="actionable && job.failed_rows" class="detail-section">
        <h3>失败行重试</h3>
        <textarea v-model="retryJson" rows="6" placeholder="请输入 JSON 数组" /><button
          class="ghost-button"
          type="button"
          @click="retry"
        >
          重新校验
        </button>
      </section>
      <div class="action-group">
        <button
          v-if="actionable"
          class="primary-button"
          :disabled="loading || job.success_rows === 0"
          type="button"
          @click="confirm"
        >
          确认入账</button
        ><button v-if="job.failed_rows" class="ghost-button" type="button" @click="downloadErrors">
          下载错误 CSV</button
        ><span v-if="loading" class="meta">处理中...</span>
      </div>
    </article>
    <p v-else-if="loading" class="empty-state">加载中...</p>
  </section>
</template>
