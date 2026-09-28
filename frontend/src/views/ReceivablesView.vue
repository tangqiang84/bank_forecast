<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  previewContracts,
  loadReceivables,
  runMatching,
  type ContractImportPayload,
  type Receivable,
} from '../services/receivables'
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
const rows = ref<Receivable[]>([])
const file = ref<File | null>(null)
const preview = ref<GenericImportPreview<ContractImportPayload> | null>(null)
const retryJson = ref('')
const importLoading = ref(false)
const page = ref(1)

function prevPage() {
  page.value--
  load()
}

function nextPage() {
  page.value++
  load()
}
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const message = ref('')
const error = ref('')
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const response = await loadReceivables(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
    )
    rows.value = response.data.items
    total.value = response.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '应收计划加载失败'
  } finally {
    loading.value = false
  }
}
function choose(event: Event) {
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
  if (!session.user.value || !session.token.value || !file.value) {
    message.value = '请选择合同或应收计划 CSV 文件'
    return
  }
  importLoading.value = true
  try {
    preview.value = (
      await previewContracts(base, session.token.value, session.user.value.tenant_id, file.value)
    ).data
    message.value =
      preview.value.status === 'preview_failed'
        ? '预览校验失败，请修正失败行后重新校验。'
        : '预览完成，请核对后确认导入。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '合同应收预览失败'
  } finally {
    importLoading.value = false
  }
}
async function confirmPreview() {
  if (!session.user.value || !session.token.value || !preview.value) return
  importLoading.value = true
  try {
    preview.value = (
      await confirmImportJob<ContractImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
      )
    ).data
    message.value = '导入已确认，应收计划已更新。'
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认导入失败'
  } finally {
    importLoading.value = false
  }
}
async function retryErrors() {
  if (!session.user.value || !session.token.value || !preview.value) return
  try {
    preview.value = (
      await retryImportJobErrors<ContractImportPayload>(
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
function retryPlaceholder(rows: Array<GenericImportRow<ContractImportPayload>>) {
  return JSON.stringify(
    rows
      .filter((row) => row.status === 'failed')
      .map((row) => ({
        row_no: row.row_no,
        contract_no: row.payload?.contract_no ?? '',
        contract_name: row.payload?.contract_name ?? '',
        customer_name: row.payload?.customer_name ?? '',
        node_name: row.payload?.node_name ?? '',
        due_date: row.payload?.due_date ?? '',
        plan_amount: row.payload?.plan_amount ?? '',
      })),
    null,
    2,
  )
}
async function matching() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await runMatching(base, session.token.value, session.user.value.tenant_id)
    message.value = `匹配完成：匹配 ${result.data.matched ?? 0}，待确认 ${result.data.suggested ?? 0}，未知收款 ${result.data.unknown ?? 0}。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '回款匹配失败'
  }
}
function changeSize() {
  page.value = 1
  load()
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
        <p class="eyebrow">合同应收</p>
        <h2>合同回款计划</h2>
        <p class="lead">导入合同主数据或独立应收计划，核对节点后运行回款匹配。</p>
      </div>
      <span class="meta">共 {{ total }} 个节点</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>
    <section class="grid receivable-layout">
      <article class="panel workflow-panel">
        <h3>导入合同 / 应收计划</h3>
        <label>CSV 文件<input accept=".csv,text/csv" type="file" @change="choose" /></label>
        <p v-if="file" class="meta">已选择：{{ file.name }}</p>
        <button
          v-permission="'contract:import'"
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
            v-permission="'contract:import'"
            class="ghost-button"
            type="button"
            @click="retryErrors"
          >
            重新校验失败行
          </button>
        </template>
        <button
          v-if="preview && previewActionable"
          v-permission="'contract:import'"
          class="primary-button"
          :disabled="importLoading || preview.success_rows === 0"
          type="button"
          @click="confirmPreview"
        >
          确认导入
        </button>
        <p class="meta import-hint">
          支持合同主数据、合同应收计划和独立应收计划模板；先预览校验，修正失败行后再确认导入。
        </p>
        <button v-permission="'matching:run'" class="ghost-button" type="button" @click="matching">
          运行回款匹配
        </button>
      </article>
      <article class="panel">
        <h3>业务口径</h3>
        <div class="detail-section">
          <p>未到期、按期足额、逾期未收、部分收款和超额收款由系统根据应收日期及已收金额计算。</p>
          <p class="meta">低置信度结果进入匹配结果页人工确认，未知收款进入异常事项页处理。</p>
        </div>
      </article>
    </section>
    <article v-if="preview" class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>导入预览行</h3>
          <span class="meta">任务 #{{ preview.job_id }} · {{ preview.status }}</span>
        </div>
      </div>
      <div v-if="preview.preview_rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>行号</th>
              <th>合同编号</th>
              <th>合同名称</th>
              <th>客户</th>
              <th>节点</th>
              <th>应收日期</th>
              <th>计划金额</th>
              <th>状态</th>
              <th>错误原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in preview.preview_rows" :key="row.id">
              <td>{{ row.row_no }}</td>
              <td>{{ row.payload?.contract_no || '-' }}</td>
              <td>{{ row.payload?.contract_name || '-' }}</td>
              <td>{{ row.payload?.customer_name || '-' }}</td>
              <td>{{ row.payload?.node_name || '-' }}</td>
              <td>{{ row.payload?.due_date || '-' }}</td>
              <td>{{ row.payload?.plan_amount || '-' }}</td>
              <td>
                <span class="pill">{{ row.status }}</span>
              </td>
              <td>{{ row.error_message || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">本次预览没有可展示的行。</p>
    </article>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>应收计划</h3>
          <span class="meta">服务端分页</span>
        </div>
        <span v-if="loading" class="meta">加载中...</span>
      </div>
      <div v-if="rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>合同</th>
              <th>客户</th>
              <th>节点</th>
              <th>应收日期</th>
              <th>计划金额</th>
              <th>已收金额</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td>
                {{ item.contract_no }}<br /><span class="meta">{{ item.contract_name }}</span>
              </td>
              <td>{{ item.customer_name }}</td>
              <td>{{ item.node_name }}</td>
              <td>{{ item.due_date }}</td>
              <td>{{ formatCurrency(item.plan_amount) }}</td>
              <td>{{ formatCurrency(item.paid_amount) }}</td>
              <td>
                <span class="pill">{{ item.status }}</span>
              </td>
              <td>
                <button
                  class="text-button"
                  type="button"
                  @click="router.push(`/contracts/${item.contract_id}`)"
                >
                  查看合同
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无应收计划，请先导入合同 CSV。</p>
      <div class="pagination-controls">
        <span
          >第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页，共
          {{ total }} 条</span
        ><label
          >每页<select v-model.number="pageSize" @change="changeSize">
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
</template>
