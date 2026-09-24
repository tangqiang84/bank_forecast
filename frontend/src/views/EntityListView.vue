<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { loadAccounts, type BankAccountPage } from '../services/bank'
import {
  loadExceptions,
  loadMatchResults,
  loadReceivables,
  type Paged,
} from '../services/receivables'
import { loadProjects, type ProjectPage } from '../services/projects'
import { loadReports, type ReportPage } from '../services/reports'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'
type Kind =
  | 'accounts'
  | 'receivables'
  | 'projects'
  | 'matching'
  | 'exceptions'
  | 'reconciliation'
  | 'reports'
  | 'forecast'
const props = defineProps<{ kind: Kind }>()
const router = useRouter()
const session = useSession()
const rows = ref<Array<Record<string, unknown>>>([])
const loading = ref(false)
const error = ref('')
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
const titles: Record<Kind, string> = {
  accounts: '银行账户',
  receivables: '合同应收',
  projects: '项目资金',
  matching: '匹配结果',
  exceptions: '异常事项',
  reconciliation: '财务对账',
  reports: '报表中心',
  forecast: '现金预测',
}
type ListResult = {
  data: BankAccountPage | Paged<Record<string, unknown>> | ProjectPage | ReportPage
}
async function load() {
  if (
    !session.user.value ||
    !session.token.value ||
    props.kind === 'reconciliation' ||
    props.kind === 'forecast'
  )
    return
  loading.value = true
  try {
    const base = apiBase()
    const token = session.token.value
    const tenant = session.user.value.tenant_id
    let result: ListResult
    if (props.kind === 'accounts')
      result = await loadAccounts(base, token, tenant, page.value, pageSize.value)
    else if (props.kind === 'receivables')
      result = (await loadReceivables(
        base,
        token,
        tenant,
        page.value,
        pageSize.value,
      )) as ListResult
    else if (props.kind === 'projects')
      result = await loadProjects(base, token, tenant, page.value, pageSize.value)
    else if (props.kind === 'matching')
      result = (await loadMatchResults(
        base,
        token,
        tenant,
        page.value,
        pageSize.value,
      )) as ListResult
    else if (props.kind === 'exceptions')
      result = (await loadExceptions(base, token, tenant, page.value, pageSize.value)) as ListResult
    else result = await loadReports(base, token, tenant, page.value, pageSize.value)
    rows.value = result.data.items as Array<Record<string, unknown>>
    total.value = result.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '列表加载失败'
  } finally {
    loading.value = false
  }
}
function id(row: Record<string, unknown>) {
  return Number(row.id)
}
function detailPath(row: Record<string, unknown>) {
  const map: Record<Kind, string> = {
    accounts: 'accounts',
    receivables: 'contracts',
    projects: 'projects',
    matching: 'matching',
    exceptions: 'exceptions',
    reconciliation: 'reconciliation',
    reports: 'reports',
    forecast: 'forecast',
  }
  const targetId = props.kind === 'receivables' ? Number(row.contract_id) : id(row)
  return `/${map[props.kind]}/${targetId}`
}
function changePageSize() {
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
        <p class="eyebrow">业务模块</p>
        <h2>{{ titles[kind] }}</h2>
        <p class="lead">独立列表页支持服务端分页、业务字段展示和详情跳转。</p>
      </div>
      <span v-if="loading" class="meta">加载中...</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <article v-if="kind === 'reconciliation'" class="panel">
      <h3>财务对账</h3>
      <p class="lead">对账导入和运行入口仍沿用基础工作区，复杂差异处理列入开发待办。</p>
      <RouterLink class="primary-button inline-button" to="/transactions">前往流水中心</RouterLink>
    </article>
    <article v-else-if="kind === 'forecast'" class="panel">
      <h3>现金预测</h3>
      <p class="lead">预测任务、模型版本和评估结果将在独立任务页继续拆分。</p>
    </article>
    <article v-else class="panel table-panel">
      <div v-if="rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>编号</th>
              <th>核心信息</th>
              <th>金额/状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="id(row)">
              <td>#{{ id(row) }}</td>
              <td>
                <strong>{{
                  row.project_name ||
                  row.contract_no ||
                  row.transaction_no ||
                  row.exception_no ||
                  row.file_name ||
                  row.bank_name ||
                  '-'
                }}</strong
                ><br /><span class="meta">{{
                  row.customer_name ||
                  row.title ||
                  row.account_name ||
                  row.report_type ||
                  row.node_name ||
                  ''
                }}</span>
              </td>
              <td>
                {{
                  row.amount
                    ? formatCurrency(String(row.amount))
                    : row.status || row.match_status || '-'
                }}
              </td>
              <td>
                <button class="text-button" type="button" @click="router.push(detailPath(row))">
                  查看详情
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无{{ titles[kind] }}数据。</p>
      <div class="pagination-controls">
        <span
          >第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页，共
          {{ total }} 条</span
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
</template>
