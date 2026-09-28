<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BusinessDetail from '../components/BusinessDetail.vue'
import DetailDrawer from '../components/DetailDrawer.vue'
import { loadAccountDetail, loadTransactionDetail } from '../services/bank'
import {
  loadContractDetail,
  loadExceptionDetail,
  loadMatchResultDetail,
} from '../services/receivables'
import { loadProjectDetail } from '../services/projects'
import {
  downloadReceiptImage,
  previewReceiptImage,
  uploadReceiptImage,
  type Receipt,
} from '../services/receipts'
import { loadReportDetail } from '../services/reports'
import { apiBase, useSession } from '../session'
type Kind = 'account' | 'transaction' | 'contract' | 'project' | 'match' | 'exception' | 'report'
const props = defineProps<{ kind: Kind }>()
const route = useRoute()
const router = useRouter()
const session = useSession()
const data = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const error = ref('')
const receiptMessage = ref('')
const titles: Record<Kind, string> = {
  account: '账户详情',
  transaction: '流水详情',
  contract: '合同详情',
  project: '项目详情',
  match: '匹配结果详情',
  exception: '异常事项详情',
  report: '报表任务详情',
}
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const base = apiBase()
    const token = session.token.value
    const tenant = session.user.value.tenant_id
    const id = Number(route.params.id)
    if (props.kind === 'account')
      data.value = (await loadAccountDetail(base, token, tenant, id)).data as Record<
        string,
        unknown
      >
    if (props.kind === 'transaction')
      data.value = (await loadTransactionDetail(base, token, tenant, id)).data
    if (props.kind === 'contract')
      data.value = (await loadContractDetail(base, token, tenant, id)).data
    if (props.kind === 'project')
      data.value = (await loadProjectDetail(base, token, tenant, id)).data
    if (props.kind === 'match')
      data.value = (await loadMatchResultDetail(base, token, tenant, id)).data
    if (props.kind === 'report')
      data.value = (await loadReportDetail(base, token, tenant, id)).data as Record<string, unknown>
    if (props.kind === 'exception')
      data.value = (await loadExceptionDetail(base, token, tenant, id)).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '详情加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

const receipts = computed<Receipt[]>(() => {
  const value = data.value?.receipts
  return Array.isArray(value) ? (value as Receipt[]) : []
})

function receiptSession() {
  if (!session.user.value || !session.token.value) return null
  return { base: apiBase(), token: session.token.value, tenant: session.user.value.tenant_id }
}

async function previewReceipt(receipt: Receipt) {
  const ctx = receiptSession()
  if (!ctx) return
  try {
    const blob = await previewReceiptImage(ctx.base, ctx.token, ctx.tenant, receipt.id)
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
    receiptMessage.value = '回单影像已在新窗口打开。'
  } catch (cause) {
    receiptMessage.value = cause instanceof Error ? cause.message : '回单影像预览失败'
  }
}

async function downloadReceipt(receipt: Receipt) {
  const ctx = receiptSession()
  if (!ctx) return
  try {
    const blob = await downloadReceiptImage(ctx.base, ctx.token, ctx.tenant, receipt.id)
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = receipt.image_file_name || `receipt-${receipt.receipt_no}`
    link.click()
    URL.revokeObjectURL(link.href)
    receiptMessage.value = '回单影像已下载。'
  } catch (cause) {
    receiptMessage.value = cause instanceof Error ? cause.message : '回单影像下载失败'
  }
}

async function uploadReceipt(receipt: Receipt, event: Event) {
  const ctx = receiptSession()
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!ctx || !file) return
  try {
    await uploadReceiptImage(ctx.base, ctx.token, ctx.tenant, receipt.id, file)
    receiptMessage.value = `回单 ${receipt.receipt_no} 影像已上传。`
    await load()
  } catch (cause) {
    receiptMessage.value = cause instanceof Error ? cause.message : '回单影像上传失败'
  }
}
</script>
<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">{{ titles[kind] }}</p>
        <h2>业务对象 #{{ route.params.id }}</h2>
        <p class="lead">字段、关联对象和处理记录按业务区块展示。</p>
      </div>
      <RouterLink class="ghost-button" to="/dashboard">返回驾驶舱</RouterLink>
    </header>
    <DetailDrawer :title="titles[kind]" :loading="loading" :error="error" @close="router.back()"
      ><BusinessDetail v-if="data" :data="data" />
      <section v-if="kind === 'transaction' && data" class="detail-section">
        <h3>关联回单</h3>
        <p v-if="receiptMessage" class="feedback-text">{{ receiptMessage }}</p>
        <div v-if="receipts.length" class="mini-list">
          <div v-for="receipt in receipts" :key="receipt.id" class="mini-row">
            <span
              >{{ receipt.receipt_no }} · {{ receipt.transaction_date }} · {{ receipt.amount }}
              {{ receipt.currency }}</span
            >
            <span class="meta"
              >{{ receipt.payer_name || '-' }} → {{ receipt.payee_name || '-' }}</span
            >
            <span class="action-group">
              <button
                v-if="receipt.has_image"
                class="text-button"
                type="button"
                @click="previewReceipt(receipt)"
              >
                预览影像</button
              ><button
                v-if="receipt.has_image"
                class="text-button"
                type="button"
                @click="downloadReceipt(receipt)"
              >
                下载</button
              ><label v-permission="'receipt:import'" class="text-button"
                >{{ receipt.has_image ? '重新上传' : '上传影像'
                }}<input
                  accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                  type="file"
                  style="display: none"
                  @change="uploadReceipt(receipt, $event)"
              /></label>
            </span>
          </div>
        </div>
        <p v-else class="empty-state">暂无关联回单，可在流水列表页导入回单 CSV。</p>
      </section>
    </DetailDrawer>
  </section>
</template>
