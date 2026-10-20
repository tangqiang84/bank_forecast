<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ContractDetail from '../components/ContractDetail.vue'
import ModalPanel from '../components/ModalPanel.vue'
import {
  previewContracts,
  createContract,
  createReceivablePlan,
  loadContracts,
  loadReceivables,
  runMatching,
  type Contract,
  type ContractImportPayload,
  type Receivable,
} from '../services/receivables'
import { loadProjects, type Project } from '../services/projects'
import {
  loadPayables,
  createPayablePlan,
  previewPayables,
  type PaginatedPayables,
  type PayableImportPayload,
  type PayablePlan,
} from '../services/payables'
import {
  confirmImportJob,
  retryImportJobErrors,
  type GenericImportPreview,
  type GenericImportRow,
} from '../services/imports'
import { apiBase, useSession } from '../session'
import {
  importJobStatusLabel,
  importRowStatusLabel,
  payableStatusLabel,
  receivableStatusLabel,
} from '../utils/labels'
import { formatCurrency } from '../utils/number'

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
const importModalOpen = ref(false)
const importDone = ref(false)
const contractModalOpen = ref(false)
const contractDone = ref(false)
const contractForm = ref({
  contract_no: '',
  contract_name: '',
  customer_name: '',
  contract_amount: '',
  project_id: '',
  sign_date: '',
  start_date: '',
  end_date: '',
  status: 'active',
})
const planModalOpen = ref(false)
const planDone = ref(false)
const planForm = ref({
  contract_id: '',
  node_name: '',
  node_type: 'milestone',
  due_date: '',
  plan_amount: '',
})
const contractOptions = ref<Contract[]>([])
const projectOptions = ref<Project[]>([])
const contractRows = ref<Contract[]>([])
const contractPage = ref(1)
const contractPageSize = ref(20)
const contractTotal = ref(0)
const contractLoading = ref(false)
const plansModalOpen = ref(false)
const planTab = ref<'receivable' | 'payable'>('receivable')

async function loadContractList() {
  if (!session.user.value || !session.token.value) return
  contractLoading.value = true
  try {
    const response = await loadContracts(
      base,
      session.token.value,
      session.user.value.tenant_id,
      {},
      contractPage.value,
      contractPageSize.value,
    )
    contractRows.value = response.data.items
    contractTotal.value = response.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '合同列表加载失败'
  } finally {
    contractLoading.value = false
  }
}

function contractPrevPage() {
  contractPage.value--
  loadContractList()
}

function contractNextPage() {
  contractPage.value++
  loadContractList()
}

function changeContractSize() {
  contractPage.value = 1
  loadContractList()
}

function openPlansModal() {
  planTab.value = 'receivable'
  plansModalOpen.value = true
  load()
  loadPayableList()
}

function closePlansModal() {
  plansModalOpen.value = false
}

async function openContractModal() {
  contractForm.value = {
    contract_no: '',
    contract_name: '',
    customer_name: '',
    contract_amount: '',
    project_id: '',
    sign_date: '',
    start_date: '',
    end_date: '',
    status: 'active',
  }
  message.value = ''
  contractDone.value = false
  contractModalOpen.value = true
  if (session.user.value && session.token.value && !projectOptions.value.length) {
    try {
      const projects = await loadProjects(
        base,
        session.token.value,
        session.user.value.tenant_id,
        1,
        100,
        {},
      )
      projectOptions.value = projects.data.items
    } catch {
      projectOptions.value = []
    }
  }
}

function closeContractModal() {
  contractModalOpen.value = false
}

async function saveContract() {
  if (!session.user.value || !session.token.value) return
  const form = contractForm.value
  if (!form.contract_no.trim() || !form.contract_name.trim() || !form.customer_name.trim()) {
    message.value = '请填写合同编号、合同名称和客户名称'
    return
  }
  if (!form.contract_amount || Number(form.contract_amount) <= 0) {
    message.value = '请填写大于 0 的合同金额'
    return
  }
  try {
    await createContract(base, session.token.value, session.user.value.tenant_id, {
      contract_no: form.contract_no,
      contract_name: form.contract_name,
      customer_name: form.customer_name,
      contract_amount: form.contract_amount,
      project_id: form.project_id || null,
      sign_date: form.sign_date || null,
      start_date: form.start_date || null,
      end_date: form.end_date || null,
      status: form.status,
    })
    message.value = `合同「${form.contract_name.trim()}」已创建。`
    contractDone.value = true
    contractOptions.value = []
    await loadContractList()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '合同创建失败'
  }
}

async function openPlanModal() {
  planForm.value = {
    contract_id: '',
    node_name: '',
    node_type: 'milestone',
    due_date: '',
    plan_amount: '',
  }
  message.value = ''
  planDone.value = false
  planModalOpen.value = true
  if (session.user.value && session.token.value && !contractOptions.value.length) {
    try {
      const contracts = await loadContracts(base, session.token.value, session.user.value.tenant_id)
      contractOptions.value = contracts.data.items
    } catch {
      contractOptions.value = []
    }
  }
}

function closePlanModal() {
  planModalOpen.value = false
}

async function savePlan() {
  if (!session.user.value || !session.token.value) return
  const form = planForm.value
  if (!form.contract_id) {
    message.value = '请选择关联合同'
    return
  }
  if (!form.node_name.trim() || !form.due_date) {
    message.value = '请填写节点名称和应收日期'
    return
  }
  if (!form.plan_amount || Number(form.plan_amount) <= 0) {
    message.value = '请填写大于 0 的计划金额'
    return
  }
  try {
    await createReceivablePlan(base, session.token.value, session.user.value.tenant_id, {
      contract_id: form.contract_id,
      node_name: form.node_name,
      node_type: form.node_type,
      due_date: form.due_date,
      plan_amount: form.plan_amount,
    })
    message.value = `应收计划「${form.node_name.trim()}」已创建。`
    planDone.value = true
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '应收计划创建失败'
  }
}
const contractDetailId = ref<number | null>(null)
const payables = ref<PayablePlan[]>([])
const payablePage = ref(1)
const payablePageSize = ref(20)
const payableTotal = ref(0)
const payableStatus = ref('')
const payableLoading = ref(false)
const payableMessage = ref('')
const payableModalOpen = ref(false)
const payableFile = ref<File | null>(null)
const payablePreview = ref<GenericImportPreview<PayableImportPayload> | null>(null)
const payableRetryJson = ref('')
const payableImportLoading = ref(false)
const payableImportDone = ref(false)
const payableCreateModalOpen = ref(false)
const payableCreateDone = ref(false)
const payableForm = ref({
  supplier_name: '',
  contract_id: '',
  node_name: '',
  due_date: '',
  plan_amount: '',
  remark: '',
})

async function openPayableCreateModal() {
  payableForm.value = {
    supplier_name: '',
    contract_id: '',
    node_name: '',
    due_date: '',
    plan_amount: '',
    remark: '',
  }
  payableMessage.value = ''
  payableCreateDone.value = false
  payableCreateModalOpen.value = true
  if (session.user.value && session.token.value && !contractOptions.value.length) {
    try {
      const contracts = await loadContracts(base, session.token.value, session.user.value.tenant_id)
      contractOptions.value = contracts.data.items
    } catch {
      contractOptions.value = []
    }
  }
}

function closePayableCreateModal() {
  payableCreateModalOpen.value = false
}

async function savePayablePlan() {
  if (!session.user.value || !session.token.value) return
  const form = payableForm.value
  if (!form.supplier_name.trim() || !form.node_name.trim() || !form.due_date) {
    payableMessage.value = '请填写供应商名称、节点名称和应付日期'
    return
  }
  if (!form.plan_amount || Number(form.plan_amount) <= 0) {
    payableMessage.value = '请填写大于 0 的计划金额'
    return
  }
  try {
    await createPayablePlan(base, session.token.value, session.user.value.tenant_id, {
      supplier_name: form.supplier_name,
      contract_id: form.contract_id || null,
      node_name: form.node_name,
      due_date: form.due_date,
      plan_amount: form.plan_amount,
      remark: form.remark,
    })
    payableCreateDone.value = true
    await loadPayableList()
    payableMessage.value = `应付计划「${form.node_name.trim()}」已创建。`
  } catch (cause) {
    payableMessage.value = cause instanceof Error ? cause.message : '应付计划创建失败'
  }
}

function payablePrevPage() {
  payablePage.value--
  loadPayableList()
}

function payableNextPage() {
  payablePage.value++
  loadPayableList()
}

async function loadPayableList() {
  if (!session.user.value || !session.token.value) return
  payableLoading.value = true
  payableMessage.value = ''
  try {
    const result: PaginatedPayables = (
      await loadPayables(
        base,
        session.token.value,
        session.user.value.tenant_id,
        payablePage.value,
        payablePageSize.value,
        payableStatus.value || undefined,
      )
    ).data
    payables.value = result.items
    payableTotal.value = result.total
  } catch (cause) {
    payableMessage.value = cause instanceof Error ? cause.message : '应付计划加载失败'
  } finally {
    payableLoading.value = false
  }
}

function filterPayables() {
  payablePage.value = 1
  loadPayableList()
}

function changePayableSize() {
  payablePage.value = 1
  loadPayableList()
}

function openPayableImport() {
  payableFile.value = null
  payablePreview.value = null
  payableRetryJson.value = ''
  payableMessage.value = ''
  payableImportDone.value = false
  payableModalOpen.value = true
}

function closePayableImport() {
  payableModalOpen.value = false
  payableMessage.value = ''
}

function choosePayableFile(event: Event) {
  payableFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  payableMessage.value = ''
  payablePreview.value = null
  payableRetryJson.value = ''
}

const payablePreviewActionable = computed(
  () =>
    payablePreview.value !== null &&
    ['preview_pending', 'preview_failed'].includes(payablePreview.value.status),
)

async function startPayablePreview() {
  if (!session.user.value || !session.token.value || !payableFile.value) {
    payableMessage.value = '请选择应付计划 CSV 文件'
    return
  }
  payableImportLoading.value = true
  try {
    payablePreview.value = (
      await previewPayables(
        base,
        session.token.value,
        session.user.value.tenant_id,
        payableFile.value,
      )
    ).data
    payableMessage.value =
      payablePreview.value.status === 'preview_failed'
        ? '预览校验失败，请修正失败行后重新校验。'
        : '预览完成，请核对后确认导入。'
  } catch (cause) {
    payableMessage.value = cause instanceof Error ? cause.message : '应付计划预览失败'
  } finally {
    payableImportLoading.value = false
  }
}

async function confirmPayablePreview() {
  if (!session.user.value || !session.token.value || !payablePreview.value) return
  payableImportLoading.value = true
  try {
    payablePreview.value = (
      await confirmImportJob<PayableImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        payablePreview.value.job_id,
      )
    ).data
    await loadPayableList()
    payableMessage.value = '导入已确认，应付计划已更新。'
    payableImportDone.value = true
  } catch (cause) {
    payableMessage.value = cause instanceof Error ? cause.message : '确认导入失败'
  } finally {
    payableImportLoading.value = false
  }
}

async function retryPayableErrors() {
  if (!session.user.value || !session.token.value || !payablePreview.value) return
  try {
    payablePreview.value = (
      await retryImportJobErrors<PayableImportPayload>(
        base,
        session.token.value,
        session.user.value.tenant_id,
        payablePreview.value.job_id,
        JSON.parse(payableRetryJson.value),
      )
    ).data
    payableRetryJson.value = ''
    payableMessage.value = '失败行已重新校验。'
  } catch (cause) {
    payableMessage.value = cause instanceof Error ? cause.message : '失败行格式不正确'
  }
}

function payableRetryPlaceholder(rows: Array<GenericImportRow<PayableImportPayload>>) {
  return JSON.stringify(
    rows
      .filter((row) => row.status === 'failed')
      .map((row) => ({
        row_no: row.row_no,
        contract_no: row.payload?.contract_no ?? '',
        supplier_name: row.payload?.supplier_name ?? '',
        node_name: row.payload?.node_name ?? '',
        due_date: row.payload?.due_date ?? '',
        plan_amount: row.payload?.plan_amount ?? '',
        paid_amount: row.payload?.paid_amount ?? '',
      })),
    null,
    2,
  )
}

function openImportModal() {
  file.value = null
  preview.value = null
  retryJson.value = ''
  message.value = ''
  importDone.value = false
  importModalOpen.value = true
}

function closeImportModal() {
  importModalOpen.value = false
}

function openContract(id: number) {
  contractDetailId.value = id
}

function closeContract() {
  contractDetailId.value = null
}
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
    importDone.value = true
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
  loadContractList()
  window.addEventListener('workspace-refresh', loadContractList)
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">合同应收</p>
        <h2>合同列表</h2>
        <p class="lead">
          合同主数据与回款进度总览；应收/应付计划通过「应收计划与应付计划」弹层分 Tab 查看与维护。
        </p>
      </div>
      <span class="meta">共 {{ contractTotal }} 个合同</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p
      v-if="message && !importModalOpen && !contractModalOpen && !planModalOpen && !plansModalOpen"
      class="feedback-text"
    >
      {{ message }}
    </p>
    <div class="page-toolbar">
      <button
        v-permission="'contract:import'"
        class="ghost-button"
        type="button"
        @click="openContractModal"
      >
        新增合同
      </button>
      <button class="ghost-button" type="button" @click="openPlansModal">应收计划与应付计划</button>
      <button class="ghost-button" type="button" @click="openImportModal">
        导入合同 / 应收计划
      </button>
      <button class="ghost-button" type="button" @click="openPayableImport">导入应付计划</button>
      <button v-permission="'matching:run'" class="ghost-button" type="button" @click="matching">
        运行回款匹配
      </button>
    </div>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>合同清单</h3>
          <span class="meta">服务端分页</span>
        </div>
        <span v-if="contractLoading" class="meta">加载中...</span>
      </div>
      <div v-if="contractRows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>合同编号</th>
              <th>合同名称</th>
              <th>客户</th>
              <th>关联项目</th>
              <th>合同金额</th>
              <th>应收金额</th>
              <th>已收金额</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="contract in contractRows" :key="contract.id">
              <td>{{ contract.contract_no }}</td>
              <td>{{ contract.contract_name }}</td>
              <td>{{ contract.customer_name }}</td>
              <td>{{ contract.project_name || '-' }}</td>
              <td>{{ formatCurrency(contract.contract_amount) }}</td>
              <td>{{ formatCurrency(contract.receivable_amount) }}</td>
              <td>{{ formatCurrency(contract.paid_amount) }}</td>
              <td>
                <button class="text-button" type="button" @click="openContract(contract.id)">
                  详情
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无合同，请通过「新增合同」或「导入合同 / 应收计划」录入。</p>
      <div class="pagination-controls">
        <span
          >第 {{ contractPage }} /
          {{ Math.max(1, Math.ceil(contractTotal / contractPageSize)) }} 页，共
          {{ contractTotal }} 条</span
        ><label
          >每页<select v-model.number="contractPageSize" @change="changeContractSize">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option></select
          >条</label
        ><button
          class="ghost-button"
          :disabled="contractPage <= 1"
          type="button"
          @click="contractPrevPage"
        >
          上一页</button
        ><button
          class="ghost-button"
          :disabled="contractPage >= Math.ceil(contractTotal / contractPageSize)"
          type="button"
          @click="contractNextPage"
        >
          下一页
        </button>
      </div>
    </article>
    <ModalPanel v-if="plansModalOpen" title="应收计划与应付计划" @close="closePlansModal">
      <div class="tab-bar" role="tablist">
        <button
          class="tab-item"
          :class="{ 'tab-item-active': planTab === 'receivable' }"
          role="tab"
          :aria-selected="planTab === 'receivable'"
          type="button"
          @click="planTab = 'receivable'"
        >
          应收计划
        </button>
        <button
          class="tab-item"
          :class="{ 'tab-item-active': planTab === 'payable' }"
          role="tab"
          :aria-selected="planTab === 'payable'"
          type="button"
          @click="planTab = 'payable'"
        >
          应付计划
        </button>
      </div>
      <template v-if="planTab === 'receivable'">
        <div class="detail-section">
          <p class="meta">
            未到期、按期足额、逾期未收、部分收款和超额收款由系统根据应收日期及已收金额计算；低置信度结果进入匹配结果页人工确认，未知收款进入异常事项页处理。
          </p>
        </div>
        <div class="page-toolbar">
          <button
            v-permission="'contract:import'"
            class="ghost-button"
            type="button"
            @click="openPlanModal"
          >
            新增应收计划
          </button>
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
                  <span class="pill">{{ receivableStatusLabel(item.status) }}</span>
                </td>
                <td>
                  <button class="text-button" type="button" @click="openContract(item.contract_id)">
                    查看合同
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">
          暂无应收计划，请通过「新增应收计划」或「导入合同 / 应收计划」录入。
        </p>
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
      </template>
      <template v-else>
        <div class="page-toolbar">
          <button
            v-permission="'contract:import'"
            class="ghost-button"
            type="button"
            @click="openPayableCreateModal"
          >
            新增应付计划
          </button>
          <label
            >状态<select v-model="payableStatus" @change="filterPayables">
              <option value="">全部</option>
              <option value="unpaid">未付</option>
              <option value="partial">部分支付</option>
              <option value="paid">已付</option>
            </select></label
          >
          <span v-if="payableLoading" class="meta">加载中...</span>
        </div>
        <p
          v-if="payableMessage && !payableModalOpen && !payableCreateModalOpen"
          class="error-banner"
        >
          {{ payableMessage }}
        </p>
        <div v-if="payables.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>供应商</th>
                <th>节点</th>
                <th>应付日期</th>
                <th>应付金额</th>
                <th>已付金额</th>
                <th>状态</th>
                <th>关联合同号</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in payables" :key="item.id">
                <td>{{ item.supplier_name }}</td>
                <td>{{ item.node_name }}</td>
                <td>{{ item.due_date }}</td>
                <td>{{ formatCurrency(item.plan_amount) }}</td>
                <td>{{ formatCurrency(item.paid_amount) }}</td>
                <td>
                  <span class="pill">{{ payableStatusLabel(item.status) }}</span>
                </td>
                <td>{{ item.contract_no || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">
          暂无应付计划，请通过「新增应付计划」或「导入应付计划」录入。
        </p>
        <div class="pagination-controls">
          <span
            >第 {{ payablePage }} /
            {{ Math.max(1, Math.ceil(payableTotal / payablePageSize)) }} 页，共
            {{ payableTotal }} 条</span
          ><label
            >每页<select v-model.number="payablePageSize" @change="changePayableSize">
              <option :value="20">20</option>
              <option :value="50">50</option>
              <option :value="100">100</option></select
            >条</label
          ><button
            class="ghost-button"
            :disabled="payablePage <= 1"
            type="button"
            @click="payablePrevPage"
          >
            上一页</button
          ><button
            class="ghost-button"
            :disabled="payablePage >= Math.ceil(payableTotal / payablePageSize)"
            type="button"
            @click="payableNextPage"
          >
            下一页
          </button>
        </div>
      </template>
      <template #footer>
        <button class="primary-button" type="button" @click="closePlansModal">关闭</button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="payableCreateModalOpen" title="新增应付计划" @close="closePayableCreateModal">
      <p class="meta">
        手工录入应付节点，可关联合同；供应商 + 节点 + 日期 + 金额完全相同的计划判重
      </p>
      <label>供应商名称<input v-model.trim="payableForm.supplier_name" /></label
      ><label
        >关联合同（可选）<select v-model="payableForm.contract_id">
          <option value="">不关联合同</option>
          <option
            v-for="contract in contractOptions"
            :key="contract.id"
            :value="String(contract.id)"
          >
            {{ contract.contract_no }} · {{ contract.contract_name }}
          </option>
        </select></label
      ><label>节点名称<input v-model.trim="payableForm.node_name" placeholder="如 到货款" /></label
      ><label>应付日期<input v-model="payableForm.due_date" type="date" /></label
      ><label
        >计划金额<input
          v-model="payableForm.plan_amount"
          type="number"
          min="0.01"
          step="0.01" /></label
      ><label>备注<input v-model.trim="payableForm.remark" /></label>
      <div class="action-group">
        <button
          v-permission="'contract:import'"
          class="primary-button"
          type="button"
          @click="savePayablePlan"
        >
          保存应付计划</button
        ><button class="ghost-button" type="button" @click="closePayableCreateModal">取消</button>
      </div>
      <p v-if="payableMessage" class="feedback-text">{{ payableMessage }}</p>
      <template #footer>
        <button
          v-if="payableCreateDone"
          class="primary-button"
          type="button"
          @click="closePayableCreateModal"
        >
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="payableModalOpen" title="导入应付计划" @close="closePayableImport">
      <label
        >CSV 文件<input accept=".csv,text/csv" type="file" @change="choosePayableFile"
      /></label>
      <p v-if="payableFile" class="meta">已选择：{{ payableFile.name }}</p>
      <button
        v-permission="'contract:import'"
        class="primary-button"
        :disabled="payableImportLoading"
        type="button"
        @click="startPayablePreview"
      >
        {{ payableImportLoading ? '处理中...' : '预览导入' }}
      </button>
      <p v-if="payableMessage" class="feedback-text">{{ payableMessage }}</p>
      <div v-if="payablePreview" class="import-summary">
        <strong>任务 #{{ payablePreview.job_id }}</strong
        ><span>有效 {{ payablePreview.success_rows }}</span
        ><span>失败 {{ payablePreview.failed_rows }}</span
        ><span>跳过 {{ payablePreview.skipped_rows }}</span
        ><RouterLink class="text-button" :to="`/imports/${payablePreview.job_id}`"
          >打开任务详情</RouterLink
        >
      </div>
      <template v-if="payablePreview && payablePreviewActionable && payablePreview.failed_rows">
        <label
          >失败行修正 JSON<textarea
            v-model="payableRetryJson"
            rows="5"
            :placeholder="payableRetryPlaceholder(payablePreview.preview_rows)"
          />
        </label>
        <button
          v-permission="'contract:import'"
          class="ghost-button"
          type="button"
          @click="retryPayableErrors"
        >
          重新校验失败行
        </button>
      </template>
      <button
        v-if="payablePreview && payablePreviewActionable"
        v-permission="'contract:import'"
        class="primary-button"
        :disabled="payableImportLoading || payablePreview.success_rows === 0"
        type="button"
        @click="confirmPayablePreview"
      >
        确认导入
      </button>
      <template v-if="payablePreview">
        <h3>导入预览行</h3>
        <p class="meta">
          任务 #{{ payablePreview.job_id }} · {{ importJobStatusLabel(payablePreview.status) }}
        </p>
        <div v-if="payablePreview.preview_rows.length" class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>行号</th>
                <th>供应商</th>
                <th>节点</th>
                <th>应付日期</th>
                <th>应付金额</th>
                <th>已付金额</th>
                <th>状态</th>
                <th>错误原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in payablePreview.preview_rows" :key="row.id">
                <td>{{ row.row_no }}</td>
                <td>{{ row.payload?.supplier_name || '-' }}</td>
                <td>{{ row.payload?.node_name || '-' }}</td>
                <td>{{ row.payload?.due_date || '-' }}</td>
                <td>{{ row.payload?.plan_amount || '-' }}</td>
                <td>{{ row.payload?.paid_amount || '-' }}</td>
                <td>
                  <span class="pill">{{ importRowStatusLabel(row.status) }}</span>
                </td>
                <td>{{ row.error_message || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">本次预览没有可展示的行。</p>
      </template>
      <p class="meta import-hint">
        CSV 表头：supplier_name、node_name、due_date、plan_amount
        必填，contract_no、paid_amount、status、remark 可选；重复的供应商+节点+日期+金额自动跳过。
      </p>
      <template #footer>
        <button
          v-if="payableImportDone"
          class="primary-button"
          type="button"
          @click="closePayableImport"
        >
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="contractModalOpen" title="新增合同" @close="closeContractModal">
      <p class="meta">手工录入合同主数据，合同编号在租户内唯一，保存后可继续录入应收计划</p>
      <label
        >合同编号<input
          v-model.trim="contractForm.contract_no"
          placeholder="如 HT-2026-001" /></label
      ><label>合同名称<input v-model.trim="contractForm.contract_name" /></label
      ><label>客户名称<input v-model.trim="contractForm.customer_name" /></label
      ><label
        >合同金额<input
          v-model="contractForm.contract_amount"
          type="number"
          min="0.01"
          step="0.01" /></label
      ><label
        >关联项目（可选）<select v-model="contractForm.project_id">
          <option value="">不关联项目</option>
          <option v-for="project in projectOptions" :key="project.id" :value="String(project.id)">
            {{ project.project_no }} · {{ project.project_name }}
          </option>
        </select></label
      ><label
        >状态<select v-model="contractForm.status">
          <option value="active">生效中</option>
          <option value="closed">已关闭</option>
          <option value="disabled">已停用</option>
        </select></label
      ><label>签订日期<input v-model="contractForm.sign_date" type="date" /></label
      ><label>开始日期<input v-model="contractForm.start_date" type="date" /></label
      ><label>结束日期<input v-model="contractForm.end_date" type="date" /></label>
      <div class="action-group">
        <button
          v-permission="'contract:import'"
          class="primary-button"
          type="button"
          @click="saveContract"
        >
          保存合同</button
        ><button class="ghost-button" type="button" @click="closeContractModal">取消</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button
          v-if="contractDone"
          class="primary-button"
          type="button"
          @click="closeContractModal"
        >
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="planModalOpen" title="新增应收计划" @close="closePlanModal">
      <p class="meta">
        手工录入应收节点并关联合同，同一合同下节点名称唯一，金额合计不能超过合同金额
      </p>
      <label
        >关联合同<select v-model="planForm.contract_id">
          <option value="" disabled>请选择合同</option>
          <option
            v-for="contract in contractOptions"
            :key="contract.id"
            :value="String(contract.id)"
          >
            {{ contract.contract_no }} · {{ contract.contract_name }}
          </option>
        </select></label
      ><label>节点名称<input v-model.trim="planForm.node_name" placeholder="如 首付款" /></label
      ><label
        >节点类型<select v-model="planForm.node_type">
          <option value="advance">预付款</option>
          <option value="milestone">里程碑</option>
          <option value="acceptance">验收款</option>
          <option value="warranty">质保金</option>
          <option value="maintenance">维保款</option>
        </select></label
      ><label>应收日期<input v-model="planForm.due_date" type="date" /></label
      ><label
        >计划金额<input v-model="planForm.plan_amount" type="number" min="0.01" step="0.01"
      /></label>
      <div class="action-group">
        <button
          v-permission="'contract:import'"
          class="primary-button"
          type="button"
          @click="savePlan"
        >
          保存应收计划</button
        ><button class="ghost-button" type="button" @click="closePlanModal">取消</button>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button v-if="planDone" class="primary-button" type="button" @click="closePlanModal">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="importModalOpen" title="导入合同 / 应收计划" @close="closeImportModal">
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
      <template v-if="preview">
        <h3>导入预览行</h3>
        <p class="meta">任务 #{{ preview.job_id }} · {{ importJobStatusLabel(preview.status) }}</p>
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
                  <span class="pill">{{ importRowStatusLabel(row.status) }}</span>
                </td>
                <td>{{ row.error_message || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-state">本次预览没有可展示的行。</p>
      </template>
      <p class="meta import-hint">
        支持合同主数据、合同应收计划和独立应收计划模板；先预览校验，修正失败行后再确认导入。
      </p>
      <template #footer>
        <button v-if="importDone" class="primary-button" type="button" @click="closeImportModal">
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="contractDetailId !== null" title="合同详情" @close="closeContract">
      <ContractDetail :id="contractDetailId" />
      <template #footer>
        <button class="primary-button" type="button" @click="closeContract">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
