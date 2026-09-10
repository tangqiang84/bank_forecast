<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getToken, loadCurrentUser, login, logout, type User } from './services/auth'
import { loadDashboardOverview, type DashboardOverview } from './services/dashboard'
import { classifyTransaction, closeBankAccount, createBankAccount, exportTransactions, importStatements, loadAccounts, loadTransactionDetail, loadTransactions, scanIdleAccounts, type BankAccount, type BankAccountInput, type BankTransaction, unlinkTransaction, updateBankAccount } from './services/bank'
import { assignException, closeException, commentException, confirmMatchResult, importContracts, loadContractDetail, loadContracts, loadExceptions, loadMatchResultDetail, loadMatchResults, loadReceivables, rejectMatchResult, resolveException, runMatching, type Contract, type ExceptionCase, type MatchResult, type Receivable } from './services/receivables'
import { fetchJson } from './services/http'
import { authHeaders } from './services/auth'
import { activateForecastModel, backfillForecastActuals, loadForecastModels, loadLatestForecast, retryForecast, runForecast, type ForecastDetail, type ForecastModel } from './services/forecast'
import { formatCurrency } from './utils/number'
import { importFinanceRecords, loadReconciliationResults, runReconciliation, type ReconciliationResult } from './services/finance'

const backendBase = import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080'
const user = ref<User | null>(null)
const token = ref(getToken())
const loginName = ref('finance01')
const password = ref('')
const loginLoading = ref(false)
const loginError = ref('')
const activeView = ref<'dashboard' | 'accounts' | 'transactions' | 'receivables' | 'matching' | 'exceptions' | 'reconciliation' | 'forecast'>('dashboard')
const loading = ref(false)
const overview = ref<DashboardOverview | null>(null)
const accounts = ref<BankAccount[]>([])
const transactions = ref<BankTransaction[]>([])
const totalTransactions = ref(0)
const selectedAccountId = ref<number | null>(null)
const selectedFile = ref<File | null>(null)
const importLoading = ref(false)
const importMessage = ref('')
const pageError = ref('')
const contracts = ref<Contract[]>([])
const receivables = ref<Receivable[]>([])
const exceptions = ref<ExceptionCase[]>([])
const matchResults = ref<MatchResult[]>([])
const contractFile = ref<File | null>(null)
const contractImportMessage = ref('')
const matchingLoading = ref(false)
const matchingMessage = ref('')
const matchActionLoading = ref<number | null>(null)
const matchActionMessage = ref('')
const exceptionActionLoading = ref<number | null>(null)
const exceptionActionMessage = ref('')
const detailLoading = ref(false)
const detailTitle = ref('')
const detailData = ref<Record<string, unknown> | null>(null)
const forecast = ref<ForecastDetail>({ job: null, results: [], evaluation: { evaluated_points: 0, mae: '0', rmse: '0', mean_deviation: '0' } })
const forecastHorizon = ref(7)
const forecastWindow = ref(3)
const forecastLoading = ref(false)
const forecastMessage = ref('')
const forecastModels = ref<ForecastModel[]>([])
const accountForm = ref<BankAccountInput>({ bankCode: '', bankName: '', accountName: '', accountNo: '', currency: 'CNY', currentBalance: '0' })
const editingAccountId = ref<number | null>(null)
const accountMessage = ref('')
const accountLoading = ref(false)
const transactionActionLoading = ref<number | null>(null)
const transactionMessage = ref('')
const financeFile = ref<File | null>(null)
const financeImportMessage = ref('')
const reconciliationMessage = ref('')
const reconciliationLoading = ref(false)
const reconciliationDateFrom = ref('')
const reconciliationDateTo = ref('')
const reconciliationResults = ref<ReconciliationResult[]>([])
const reconciliationSummary = ref<{ matched: number; bank_unrecorded: number; finance_unmatched: number } | null>(null)

const loggedIn = computed(() => Boolean(user.value && token.value))
const selectedAccount = computed(() => accounts.value.find((account) => account.id === selectedAccountId.value))
const overviewCards = computed(() => {
  if (!overview.value) return []
  return [
    { label: '全账户余额', value: formatCurrency(overview.value.total_balance), hint: '来自银行账户当前余额' },
    { label: '昨日净流入', value: formatCurrency(overview.value.yesterday_net_inflow), hint: '收入与支出流水净额' },
    { label: '应收总额', value: formatCurrency(overview.value.receivable_amount ?? '0'), hint: '合同应收计划合计' },
    { label: '已收金额', value: formatCurrency(overview.value.paid_receivable_amount ?? '0'), hint: '合同应收累计到账' },
    { label: '逾期未收', value: formatCurrency(overview.value.overdue_receivable_amount ?? '0'), hint: '已到期未收余额' },
    { label: '异常事项', value: String(overview.value.exception_count ?? 0), hint: '全部未删除异常记录' },
  ]
})
const directionLabel: Record<string, string> = { income: '收入', expense: '支出', transfer: '内部转账', refund: '退款', reversal: '冲正' }
const idleLabel: Record<string, string> = { normal: '正常', idle_30: '闲置 30 天', idle_90: '闲置 90 天', idle_180: '闲置 180 天以上' }

async function submitLogin() {
  loginLoading.value = true
  loginError.value = ''
  try {
    user.value = await login(backendBase, loginName.value.trim(), password.value)
    token.value = getToken()
    password.value = ''
    await loadWorkspace()
    await loadForecast()
  } catch (error) {
    loginError.value = error instanceof Error ? error.message : '登录失败，请稍后重试'
  } finally {
    loginLoading.value = false
  }
}

function signOut() {
  logout()
  user.value = null
  token.value = ''
  overview.value = null
  accounts.value = []
  transactions.value = []
}

function resetAccountForm() {
  editingAccountId.value = null
  accountForm.value = { bankCode: '', bankName: '', accountName: '', accountNo: '', currency: 'CNY', currentBalance: '0' }
}

function editAccount(account: BankAccount) {
  editingAccountId.value = account.id
  accountForm.value = { bankCode: account.bank_code, bankName: account.bank_name, accountName: account.account_name, accountNo: '', currency: account.currency, currentBalance: account.current_balance }
  accountMessage.value = '编辑时请输入完整账号，系统只展示后四位。'
}

async function saveAccount() {
  if (!user.value || !token.value) return
  accountLoading.value = true
  accountMessage.value = ''
  try {
    if (editingAccountId.value) await updateBankAccount(backendBase, token.value, user.value.tenant_id, editingAccountId.value, accountForm.value)
    else await createBankAccount(backendBase, token.value, user.value.tenant_id, accountForm.value)
    accountMessage.value = editingAccountId.value ? '账户已更新。' : '账户已新增。'
    resetAccountForm()
    await loadWorkspace()
  } catch (error) { accountMessage.value = error instanceof Error ? error.message : '账户保存失败' }
  finally { accountLoading.value = false }
}

async function closeAccount(account: BankAccount) {
  if (!user.value || !token.value || account.status === 'closed' || !window.confirm(`确认将 ${account.bank_name} · ${account.account_name} 标记为已销户？`)) return
  accountLoading.value = true
  try { await closeBankAccount(backendBase, token.value, user.value.tenant_id, account.id); accountMessage.value = '账户已标记为已销户。'; await loadWorkspace() }
  catch (error) { accountMessage.value = error instanceof Error ? error.message : '账户销户失败' }
  finally { accountLoading.value = false }
}

async function runIdleScan() {
  if (!user.value || !token.value) return
  accountLoading.value = true
  try { const result = await scanIdleAccounts(backendBase, token.value, user.value.tenant_id); accountMessage.value = `账户盘点完成，更新 ${result.data.updated_accounts} 个账户。`; accounts.value = result.data.accounts; await loadWorkspace() }
  catch (error) { accountMessage.value = error instanceof Error ? error.message : '账户盘点失败' }
  finally { accountLoading.value = false }
}

async function classifyTransactionRow(transaction: BankTransaction) {
  if (!user.value || !token.value) return
  const category = window.prompt('请输入流水分类', transaction.category || (transaction.direction === 'income' ? '收款' : '付款'))
  if (category === null || !category.trim()) return
  const purpose = window.prompt('请输入用途（可选）', transaction.purpose || '')
  if (purpose === null) return
  transactionActionLoading.value = transaction.id
  try { await classifyTransaction(backendBase, token.value, user.value.tenant_id, transaction.id, category.trim(), purpose.trim(), '流水中心人工分类'); transactionMessage.value = '流水分类已保存。'; await loadWorkspace() }
  catch (error) { transactionMessage.value = error instanceof Error ? error.message : '流水分类失败' }
  finally { transactionActionLoading.value = null }
}

async function unlinkTransactionRow(transaction: BankTransaction) {
  if (!user.value || !token.value || transaction.match_status === 'unmatched') return
  const reason = window.prompt('请输入解除关联原因', '人工复核后解除关联')
  if (reason === null || !reason.trim()) return
  transactionActionLoading.value = transaction.id
  try { const result = await unlinkTransaction(backendBase, token.value, user.value.tenant_id, transaction.id, reason.trim()); transactionMessage.value = `已解除关联，回滚 ${result.data.rolled_back_plans} 个应收节点。`; await loadWorkspace() }
  catch (error) { transactionMessage.value = error instanceof Error ? error.message : '解除关联失败' }
  finally { transactionActionLoading.value = null }
}

async function downloadTransactionExport() {
  if (!user.value || !token.value) return
  try { const blob = await exportTransactions(backendBase, token.value, user.value.tenant_id, selectedAccountId.value ? { bank_account_id: String(selectedAccountId.value) } : {}); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'bank-transactions.csv'; link.click(); URL.revokeObjectURL(url); transactionMessage.value = '流水 CSV 已导出。' }
  catch (error) { transactionMessage.value = error instanceof Error ? error.message : '流水导出失败' }
}

function onFinanceFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  financeFile.value = input.files?.[0] ?? null
  financeImportMessage.value = ''
}

async function submitFinanceImport() {
  if (!user.value || !token.value || !financeFile.value) {
    financeImportMessage.value = '请选择财务记录 CSV 文件'
    return
  }
  financeImportMessage.value = ''
  try {
    const result = await importFinanceRecords(backendBase, token.value, user.value.tenant_id, financeFile.value)
    const job = result.data
    financeImportMessage.value = `导入完成：成功 ${job.success_rows ?? 0} 行，失败 ${job.failed_rows ?? 0} 行，跳过 ${job.skipped_rows ?? 0} 行`
  } catch (error) { financeImportMessage.value = error instanceof Error ? error.message : '财务记录导入失败' }
}

async function submitReconciliation() {
  if (!user.value || !token.value) return
  reconciliationLoading.value = true
  reconciliationMessage.value = ''
  try {
    const filters: Record<string, string> = {}
    if (reconciliationDateFrom.value) filters.date_from = reconciliationDateFrom.value
    if (reconciliationDateTo.value) filters.date_to = reconciliationDateTo.value
    const result = await runReconciliation(backendBase, token.value, user.value.tenant_id, filters)
    reconciliationSummary.value = result.data
    reconciliationResults.value = (await loadReconciliationResults(backendBase, token.value, user.value.tenant_id, result.data.job_id)).data.items
    reconciliationMessage.value = `对账完成：匹配 ${result.data.matched} 条，银行未记账 ${result.data.bank_unrecorded} 条，财务未在银行发生 ${result.data.finance_unmatched} 条。`
  } catch (error) { reconciliationMessage.value = error instanceof Error ? error.message : '对账失败' }
  finally { reconciliationLoading.value = false }
}

async function loadWorkspace() {
  if (!user.value || !token.value) return
  loading.value = true
  pageError.value = ''
  try {
    const [overviewResult, accountResult, transactionResult, contractResult, receivableResult, matchResult, exceptionResult] = await Promise.all([
      loadDashboardOverview(backendBase, token.value, user.value.tenant_id),
      loadAccounts(backendBase, token.value, user.value.tenant_id),
      loadTransactions(backendBase, token.value, user.value.tenant_id),
      loadContracts(backendBase, token.value, user.value.tenant_id),
      loadReceivables(backendBase, token.value, user.value.tenant_id),
      loadMatchResults(backendBase, token.value, user.value.tenant_id),
      loadExceptions(backendBase, token.value, user.value.tenant_id),
    ])
    overview.value = overviewResult.data
    accounts.value = accountResult.data
    transactions.value = transactionResult.data.items
    totalTransactions.value = transactionResult.data.total
    contracts.value = contractResult.data.items
    receivables.value = receivableResult.data.items
    matchResults.value = matchResult.data.items
    exceptions.value = exceptionResult.data.items
    if (!selectedAccountId.value && accounts.value.length > 0) selectedAccountId.value = accounts.value[0].id
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '工作台加载失败，请刷新重试'
  } finally {
    loading.value = false
  }
}

async function loadForecast() {
  if (!user.value || !token.value) return
  forecastMessage.value = ''
  try {
    const [latest, models] = await Promise.all([
      loadLatestForecast(backendBase, token.value, user.value.tenant_id),
      loadForecastModels(backendBase, token.value, user.value.tenant_id),
    ])
    forecast.value = latest.data
    forecastModels.value = models.data
  } catch (error) {
    forecastMessage.value = error instanceof Error ? error.message : '预测结果加载失败'
  }
}

async function backfillForecast() {
  if (!user.value || !token.value || !forecast.value.job) return
  forecastLoading.value = true
  forecastMessage.value = ''
  try {
    forecast.value = (await backfillForecastActuals(backendBase, token.value, user.value.tenant_id, forecast.value.job.id)).data
    forecastMessage.value = '实际金额已回填，偏差指标已更新。'
  } catch (error) {
    forecastMessage.value = error instanceof Error ? error.message : '实际金额回填失败，请稍后重试'
  } finally {
    forecastLoading.value = false
  }
}

async function activateModel(version: string) {
  if (!user.value || !token.value) return
  try {
    await activateForecastModel(backendBase, token.value, user.value.tenant_id, version)
    await loadForecast()
    forecastMessage.value = `模型版本 ${version} 已启用。`
  } catch (error) {
    forecastMessage.value = error instanceof Error ? error.message : '模型版本启用失败，请稍后重试'
  }
}

async function submitForecast() {
  if (!user.value || !token.value) return
  forecastLoading.value = true
  forecastMessage.value = ''
  try {
    forecast.value = (await runForecast(backendBase, token.value, user.value.tenant_id, forecastHorizon.value, forecastWindow.value)).data
    forecastMessage.value = '预测任务已完成。'
  } catch (error) {
    forecastMessage.value = error instanceof Error ? error.message : '预测任务失败，请稍后重试'
    await loadForecast()
  } finally {
    forecastLoading.value = false
  }
}

async function retryForecastJob() {
  if (!user.value || !token.value || !forecast.value.job) return
  forecastLoading.value = true
  forecastMessage.value = ''
  try {
    forecast.value = (await retryForecast(backendBase, token.value, user.value.tenant_id, forecast.value.job.id)).data
    forecastMessage.value = '预测任务重试完成。'
  } catch (error) {
    forecastMessage.value = error instanceof Error ? error.message : '预测重试失败，请稍后重试'
  } finally {
    forecastLoading.value = false
  }
}

async function showDetail(title: string, loader: () => Promise<{ data: Record<string, unknown> }>) {
  detailLoading.value = true
  detailTitle.value = title
  detailData.value = null
  try {
    detailData.value = (await loader()).data
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailTitle.value = ''
  detailData.value = null
}

function detailJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function fetchExceptionDetail(id: number) {
  return fetchJson<{ data: Record<string, unknown> }>(`${backendBase}/api/v1/matching/exceptions/${id}`, {
    headers: authHeaders(token.value, user.value!.tenant_id),
  })
}

async function confirmResult(resultId: number) {
  if (!user.value || !token.value) return
  matchActionLoading.value = resultId
  matchActionMessage.value = ''
  try {
    await confirmMatchResult(backendBase, token.value, user.value.tenant_id, resultId)
    matchActionMessage.value = '匹配组已确认，应收和流水状态已按分配明细更新。'
    await loadWorkspace()
  } catch (error) {
    matchActionMessage.value = error instanceof Error ? error.message : '确认失败，请稍后重试'
  } finally {
    matchActionLoading.value = null
  }
}

async function rejectResult(resultId: number) {
  if (!user.value || !token.value) return
  const reason = window.prompt('请输入拒绝原因（可选）', '')
  if (reason === null) return
  matchActionLoading.value = resultId
  matchActionMessage.value = ''
  try {
    await rejectMatchResult(backendBase, token.value, user.value.tenant_id, resultId, reason)
    matchActionMessage.value = '匹配组已拒绝，应收金额保持不变。'
    await loadWorkspace()
  } catch (error) {
    matchActionMessage.value = error instanceof Error ? error.message : '拒绝失败，请稍后重试'
  } finally {
    matchActionLoading.value = null
  }
}

async function runExceptionAction(exceptionId: number, action: 'assign' | 'comment' | 'resolve' | 'close') {
  if (!user.value || !token.value) return
  const text = action === 'assign' ? '' : window.prompt(action === 'comment' ? '请输入备注' : '请输入处理说明', '')
  if (text === null) return
  exceptionActionLoading.value = exceptionId
  exceptionActionMessage.value = ''
  try {
    if (action === 'assign') await assignException(backendBase, token.value, user.value.tenant_id, exceptionId)
    if (action === 'comment') await commentException(backendBase, token.value, user.value.tenant_id, exceptionId, text)
    if (action === 'resolve') await resolveException(backendBase, token.value, user.value.tenant_id, exceptionId, text)
    if (action === 'close') await closeException(backendBase, token.value, user.value.tenant_id, exceptionId, text)
    exceptionActionMessage.value = '异常事项操作已完成。'
    await loadWorkspace()
  } catch (error) {
    exceptionActionMessage.value = error instanceof Error ? error.message : '异常事项操作失败'
  } finally {
    exceptionActionLoading.value = null
  }
}

function onContractFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  contractFile.value = input.files?.[0] ?? null
  contractImportMessage.value = ''
}

async function submitContractImport() {
  if (!user.value || !token.value || !contractFile.value) {
    contractImportMessage.value = '请选择合同 CSV 文件'
    return
  }
  contractImportMessage.value = ''
  try {
    const result = await importContracts(backendBase, token.value, user.value.tenant_id, contractFile.value)
    const job = result.data
    contractImportMessage.value = `导入完成：成功 ${job.success_rows ?? 0} 行，失败 ${job.failed_rows ?? 0} 行`
    await loadWorkspace()
  } catch (error) {
    contractImportMessage.value = error instanceof Error ? error.message : '合同导入失败'
  }
}

async function submitMatching() {
  if (!user.value || !token.value) return
  matchingLoading.value = true
  matchingMessage.value = ''
  try {
    const result = await runMatching(backendBase, token.value, user.value.tenant_id)
    matchingMessage.value = `匹配完成：精确 ${result.data.matched ?? 0}，待确认 ${result.data.suggested ?? 0}，未知收款 ${result.data.unknown ?? 0}，应收未收 ${result.data.overdue ?? 0}`
    await loadWorkspace()
  } catch (error) {
    matchingMessage.value = error instanceof Error ? error.message : '匹配失败'
  } finally {
    matchingLoading.value = false
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  importMessage.value = ''
}

async function submitImport() {
  if (!user.value || !token.value || !selectedAccountId.value || !selectedFile.value) {
    importMessage.value = '请选择银行账户和 CSV/XLSX 文件'
    return
  }
  importLoading.value = true
  importMessage.value = ''
  try {
    const result = await importStatements(backendBase, token.value, user.value.tenant_id, selectedAccountId.value, selectedFile.value)
    const job = result.data
    const templateText = Array.isArray(job.recognized_templates)
      ? `；模板 ${job.recognized_templates.filter((item: { status?: string }) => item.status === 'recognized').map((item: { bank_name?: string }) => item.bank_name).filter(Boolean).join('、') || '未识别'}`
      : ''
    importMessage.value = `导入${job.status === 'success' ? '完成' : '结束'}：成功 ${job.success_rows ?? 0} 行，失败 ${job.failed_rows ?? 0} 行${templateText}`
    await loadWorkspace()
  } catch (error) {
    importMessage.value = error instanceof Error ? error.message : '导入失败，请检查文件后重试'
  } finally {
    importLoading.value = false
  }
}

onMounted(async () => {
  if (!token.value) return
  try {
    user.value = await loadCurrentUser(backendBase, token.value)
    await loadWorkspace()
    await loadForecast()
  } catch {
    signOut()
  }
})
</script>

<template>
  <main v-if="!loggedIn" class="auth-shell">
    <section class="auth-panel">
      <p class="eyebrow">银行资金智能连接器</p>
      <h1>登录资金工作台</h1>
      <p class="lead">登录后查看企业账户、银行流水和导入任务。</p>
      <form class="login-form" @submit.prevent="submitLogin">
        <label>登录名<input v-model="loginName" autocomplete="username" required /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
        <p v-if="loginError" class="error-text">{{ loginError }}</p>
        <button class="primary-button" :disabled="loginLoading" type="submit">{{ loginLoading ? '登录中...' : '登录' }}</button>
      </form>
    </section>
  </main>

  <main v-else class="app-shell">
    <header class="topbar">
      <div><p class="eyebrow">银行资金智能连接器</p><h1>资金工作台</h1></div>
      <div class="user-actions"><span>{{ user?.display_name }}</span><button class="ghost-button" type="button" @click="signOut">退出登录</button></div>
    </header>
    <nav class="view-tabs" aria-label="工作区">
      <button :class="{ active: activeView === 'dashboard' }" type="button" @click="activeView = 'dashboard'">驾驶舱</button>
      <button :class="{ active: activeView === 'accounts' }" type="button" @click="activeView = 'accounts'">银行账户</button>
      <button :class="{ active: activeView === 'transactions' }" type="button" @click="activeView = 'transactions'">银行流水</button>
      <button :class="{ active: activeView === 'receivables' }" type="button" @click="activeView = 'receivables'">合同应收</button>
      <button :class="{ active: activeView === 'matching' }" type="button" @click="activeView = 'matching'">匹配结果</button>
      <button :class="{ active: activeView === 'exceptions' }" type="button" @click="activeView = 'exceptions'">异常事项</button>
      <button :class="{ active: activeView === 'reconciliation' }" type="button" @click="activeView = 'reconciliation'">财务对账</button>
      <button :class="{ active: activeView === 'forecast' }" type="button" @click="activeView = 'forecast'; loadForecast()">现金预测</button>
      <button class="refresh-button" :disabled="loading" type="button" @click="loadWorkspace">{{ loading ? '刷新中...' : '刷新数据' }}</button>
    </nav>
    <p v-if="pageError" class="error-banner">{{ pageError }}</p>

    <template v-if="activeView === 'dashboard'">
      <section class="grid stat-grid">
        <article v-for="card in overviewCards" :key="card.label" class="panel stat-card"><span class="label">{{ card.label }}</span><strong class="stat-value">{{ card.value }}</strong><p class="meta">{{ card.hint }}</p></article>
      </section>
      <section class="grid two-up">
        <article class="panel"><h2>关键风险</h2><div v-if="overview?.key_risks.length" class="list-stack"><div v-for="risk in overview.key_risks" :key="risk.title" class="list-row"><div><strong>{{ risk.title }}</strong><p class="meta">{{ risk.description }}</p></div><span class="pill">{{ risk.count }}</span></div></div><p v-else class="empty-state">当前没有已识别风险。</p></article>
        <article class="panel"><h2>最近导入任务</h2><div v-if="overview?.recent_import_jobs.length" class="list-stack"><div v-for="job in overview.recent_import_jobs" :key="`${job.name}-${job.status}`" class="list-row"><div><strong>{{ job.name }}</strong><p class="meta">{{ job.message || '无错误信息' }}</p></div><span class="pill">{{ job.status }}</span></div></div><p v-else class="empty-state">暂无导入任务。</p></article>
      </section>
      <section class="panel"><div class="section-heading"><h2>账户概览</h2><span class="meta">共 {{ accounts.length }} 个账户</span></div><div class="account-grid"><div v-for="account in accounts" :key="account.id" class="account-row"><div><strong>{{ account.bank_name }} · {{ account.account_name }}</strong><p class="meta">账号后四位 {{ account.account_no_last4 }} · {{ account.status }}</p></div><strong>{{ formatCurrency(account.current_balance) }}</strong></div></div></section>
    </template>

    <template v-else>
      <section v-if="activeView === 'accounts'" class="grid account-management-layout">
        <article class="panel import-panel"><div class="section-heading"><h2>{{ editingAccountId ? '编辑银行账户' : '新增银行账户' }}</h2><button v-if="editingAccountId" class="ghost-button" type="button" @click="resetAccountForm">取消编辑</button></div><label>银行代码<input v-model.trim="accountForm.bankCode" placeholder="例如 CMB" required /></label><label>银行名称<input v-model.trim="accountForm.bankName" placeholder="例如 招商银行" required /></label><label>账户名称<input v-model.trim="accountForm.accountName" required /></label><label>完整账号<input v-model.trim="accountForm.accountNo" inputmode="numeric" autocomplete="off" required /></label><label>币种<input v-model.trim="accountForm.currency" required /></label><label>当前余额<input v-model="accountForm.currentBalance" inputmode="decimal" type="number" min="0" step="0.01" required /></label><button class="primary-button" :disabled="accountLoading" type="button" @click="saveAccount">{{ accountLoading ? '处理中...' : (editingAccountId ? '保存账户' : '新增账户') }}</button><p v-if="accountMessage" class="feedback-text">{{ accountMessage }}</p><p class="meta import-hint">完整账号仅用于保存和校验，页面只展示后四位。</p></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>账户盘点</h2><span class="meta">共 {{ accounts.length }} 个账户</span></div><button class="primary-button" :disabled="accountLoading" type="button" @click="runIdleScan">盘点闲置账户</button></div><div v-if="accounts.length" class="table-scroll"><table><thead><tr><th>银行/账户</th><th>后四位</th><th>余额</th><th>状态</th><th>最近动账</th><th>闲置级别</th><th>操作</th></tr></thead><tbody><tr v-for="account in accounts" :key="account.id"><td>{{ account.bank_name }}<br />{{ account.account_name }}</td><td>{{ account.account_no_last4 }}</td><td>{{ formatCurrency(account.current_balance) }}</td><td><span class="pill">{{ account.status }}</span></td><td>{{ account.last_transaction_at || '暂无流水' }}<br /><span v-if="account.idle_days !== null" class="meta">{{ account.idle_days }} 天</span></td><td><span class="pill" :class="account.idle_level === 'normal' ? '' : 'risk-medium'">{{ idleLabel[account.idle_level || 'normal'] }}</span></td><td><div class="action-group"><button class="text-button" type="button" @click="editAccount(account)">编辑</button><button v-if="account.status !== 'closed'" class="small-danger-button" type="button" @click="closeAccount(account)">标记销户</button></div></td></tr></tbody></table></div><p v-else class="empty-state">暂无银行账户。</p></article>
      </section>
      <section v-if="activeView === 'receivables'" class="grid receivable-layout">
        <article class="panel import-panel"><h2>导入合同应收</h2><label>合同 CSV 文件<input accept=".csv,text/csv" type="file" @change="onContractFileChange" /></label><p v-if="contractFile" class="meta">已选择：{{ contractFile.name }}</p><p v-if="contractImportMessage" class="feedback-text">{{ contractImportMessage }}</p><button class="primary-button" type="button" @click="submitContractImport">导入合同</button><p class="meta import-hint">支持英文或中文表头，例如 contract_name / 合同名称；必填列为合同编号、合同名称、客户名称、合同金额、节点名称、节点类型、到期日期、应收金额。</p></article>
        <article class="panel table-panel"><div class="section-heading"><h2>应收计划</h2><span class="meta">{{ receivables.length }} 个节点</span></div><div v-if="receivables.length" class="table-scroll"><table><thead><tr><th>合同</th><th>客户</th><th>节点</th><th>应收日期</th><th>计划金额</th><th>已收金额</th><th>状态</th><th>追溯</th></tr></thead><tbody><tr v-for="item in receivables" :key="item.id"><td>{{ item.contract_no }}<br />{{ item.contract_name }}</td><td>{{ item.customer_name }}</td><td>{{ item.node_name }}</td><td>{{ item.due_date }}</td><td>{{ formatCurrency(item.plan_amount) }}</td><td>{{ formatCurrency(item.paid_amount) }}</td><td><span class="pill">{{ item.status }}</span></td><td><button class="text-button" type="button" @click="showDetail(`合同 ${item.contract_no}`, () => loadContractDetail(backendBase, token, user!.tenant_id, item.contract_id))">详情</button></td></tr></tbody></table></div><p v-else class="empty-state">暂无应收计划，请先导入合同 CSV。</p></article>
      </section>
      <section v-else-if="activeView === 'matching'" class="panel table-panel">
        <div class="section-heading"><div><h2>匹配结果</h2><span class="meta">共 {{ matchResults.length }} 条</span></div><span class="meta">待确认结果按匹配组整体处理</span></div>
        <p v-if="matchActionMessage" class="feedback-text">{{ matchActionMessage }}</p>
        <div v-if="matchResults.length" class="table-scroll"><table><thead><tr><th>流水</th><th>流水金额</th><th>分配金额</th><th>合同/节点</th><th>组/模式</th><th>置信度</th><th>匹配理由</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in matchResults" :key="item.id"><td>{{ item.transaction_no }}</td><td class="income-amount">{{ formatCurrency(item.amount) }}</td><td class="income-amount">{{ formatCurrency(item.allocated_amount) }}<br /><span class="meta">{{ item.allocation_count }} 明细 / 合计 {{ formatCurrency(item.allocation_total) }}</span></td><td>{{ item.contract_no || '-' }}<br />{{ item.contract_name || '-' }} · {{ item.node_name || '-' }}</td><td>{{ item.allocation_mode }}<br /><span class="meta">{{ item.match_group_id }}</span></td><td>{{ item.confidence_level }}</td><td class="reason-cell">{{ item.match_reason }}</td><td><span class="pill">{{ item.match_status }}</span></td><td><div class="action-group"><button class="text-button" type="button" @click="showDetail(`匹配结果 ${item.id}`, () => loadMatchResultDetail(backendBase, token, user!.tenant_id, item.id))">详情</button><button v-if="item.match_status === 'suggested'" class="small-primary-button" :disabled="matchActionLoading !== null" type="button" @click="confirmResult(item.id)">{{ matchActionLoading === item.id ? '处理中...' : '确认组' }}</button><button v-if="item.match_status === 'suggested'" class="small-danger-button" :disabled="matchActionLoading !== null" type="button" @click="rejectResult(item.id)">拒绝组</button><span v-if="item.match_status !== 'suggested'" class="meta">已处理</span></div></td></tr></tbody></table></div>
        <p v-else class="empty-state">暂无匹配结果，请先在异常事项页面运行回款匹配。</p>
      </section>
      <section v-else-if="activeView === 'exceptions'" class="panel"><div class="section-heading"><h2>异常事项</h2><button class="primary-button" :disabled="matchingLoading" type="button" @click="submitMatching">{{ matchingLoading ? '匹配中...' : '运行回款匹配' }}</button></div><p v-if="matchingMessage" class="feedback-text">{{ matchingMessage }}</p><p v-if="exceptionActionMessage" class="feedback-text">{{ exceptionActionMessage }}</p><div v-if="exceptions.length" class="list-stack"><div v-for="item in exceptions" :key="item.id" class="exception-row"><div><strong>{{ item.title }}</strong><p class="meta">{{ item.description }}</p><p class="meta">处理人：{{ item.owner_user_id ? `用户 ${item.owner_user_id}` : '未分派' }}</p></div><div class="exception-actions"><span class="pill">{{ item.exception_type }} · {{ item.status }}</span><div class="action-group"><button class="text-button" type="button" @click="showDetail(`异常 ${item.exception_no}`, () => fetchExceptionDetail(item.id))">详情</button><button v-if="item.status !== 'closed' && !item.owner_user_id" class="small-primary-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'assign')">分派给我</button><button v-if="item.status !== 'closed'" class="small-primary-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'comment')">备注</button><button v-if="item.status === 'new' || item.status === 'in_progress'" class="small-primary-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'resolve')">处理完成</button><button v-if="item.status === 'resolved'" class="small-danger-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'close')">关闭</button></div></div></div></div><p v-else class="empty-state">暂无异常事项。点击“运行回款匹配”生成结果。</p></section>
      <section v-else-if="activeView === 'reconciliation'" class="grid reconciliation-layout">
        <article class="panel import-panel"><h2>导入财务记录</h2><label>财务 CSV 文件<input accept=".csv,text/csv" type="file" @change="onFinanceFileChange" /></label><p v-if="financeFile" class="meta">已选择：{{ financeFile.name }}</p><p v-if="financeImportMessage" class="feedback-text">{{ financeImportMessage }}</p><button class="primary-button" type="button" @click="submitFinanceImport">导入财务记录</button><p class="meta import-hint">必填列：record_no、record_type、record_date、amount；记录类型支持 receipt、payment、voucher、journal。</p></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>银行账/财务账对账</h2><span class="meta">按金额、方向、对手方和 3 天日期窗口匹配</span></div><button class="primary-button" :disabled="reconciliationLoading" type="button" @click="submitReconciliation">{{ reconciliationLoading ? '对账中...' : '运行对账' }}</button></div><div class="filter-row"><label>开始日期<input v-model="reconciliationDateFrom" type="date" /></label><label>结束日期<input v-model="reconciliationDateTo" type="date" /></label></div><p v-if="reconciliationMessage" class="feedback-text">{{ reconciliationMessage }}</p><div v-if="reconciliationSummary" class="forecast-evaluation"><strong>匹配 {{ reconciliationSummary.matched }}</strong><span>银行未记账 {{ reconciliationSummary.bank_unrecorded }}</span><span>财务未在银行发生 {{ reconciliationSummary.finance_unmatched }}</span></div><div v-if="reconciliationResults.length" class="table-scroll"><table><thead><tr><th>差异类型</th><th>来源编号</th><th>日期</th><th>金额</th><th>标题</th><th>状态</th></tr></thead><tbody><tr v-for="item in reconciliationResults" :key="item.id"><td>{{ item.exception_type === 'bank_unrecorded' ? '银行未记账' : '财务未在银行发生' }}</td><td>{{ item.transaction_no || item.record_no || '-' }}</td><td>{{ item.transaction_date || item.record_date || '-' }}</td><td>{{ formatCurrency(item.bank_amount || item.finance_amount || '0') }}</td><td>{{ item.title }}<br /><span class="meta">{{ item.description }}</span></td><td><span class="pill">{{ item.status }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无对账差异，请先导入财务记录并运行对账。</p></article>
      </section>
      <section v-else-if="activeView === 'forecast'" class="grid forecast-layout">
        <article class="panel import-panel"><h2>现金流预测</h2><label>预测天数<input v-model.number="forecastHorizon" min="1" max="90" type="number" /></label><label>历史窗口<input v-model.number="forecastWindow" min="1" max="30" type="number" /></label><button class="primary-button" :disabled="forecastLoading" type="button" @click="submitForecast">{{ forecastLoading ? '预测中...' : '生成预测' }}</button><button v-if="forecast.job?.status === 'failed'" class="ghost-button" :disabled="forecastLoading" type="button" @click="retryForecastJob">重试失败任务</button><button v-if="forecast.job?.status === 'success'" class="ghost-button" :disabled="forecastLoading" type="button" @click="backfillForecast">回填实际金额</button><p v-if="forecastMessage" class="feedback-text">{{ forecastMessage }}</p><h3>模型版本</h3><div v-for="model in forecastModels" :key="model.version" class="list-row"><span>{{ model.version }} · {{ model.model_name }}</span><button v-if="model.status !== 'active'" class="text-button" type="button" @click="activateModel(model.version)">启用</button><span v-else class="pill">当前启用</span></div></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>预测结果</h2><span class="meta">{{ forecast.job ? `任务 #${forecast.job.id} · ${forecast.job.status} · ${forecast.job.model_version || '-'}` : '暂无已完成预测' }}</span></div><span v-if="forecast.job?.model_name" class="pill">{{ forecast.job.model_name }}</span></div><p v-if="forecast.job?.error_message" class="error-banner">{{ forecast.job.error_message }}</p><div v-if="forecast.results.length" class="table-scroll"><table><thead><tr><th>日期</th><th>预测净现金流</th><th>预计应收</th><th>预计余额</th><th>实际金额</th><th>偏差</th><th>风险</th></tr></thead><tbody><tr v-for="item in forecast.results" :key="item.id"><td>{{ item.forecast_date }}</td><td :class="Number(item.forecast_amount) < 0 ? 'expense-amount' : 'income-amount'">{{ formatCurrency(item.forecast_amount) }}</td><td>{{ formatCurrency(item.expected_receivable) }}</td><td>{{ formatCurrency(item.projected_balance) }}</td><td>{{ item.actual_amount !== null ? formatCurrency(item.actual_amount) : '-' }}</td><td>{{ item.deviation_amount !== null ? formatCurrency(item.deviation_amount) : '-' }}</td><td><span class="pill" :class="`risk-${item.risk_level}`">{{ item.risk_level }} · {{ item.risk_message }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无预测结果，请先生成预测。</p><div v-if="forecast.job" class="forecast-evaluation"><strong>评估点数 {{ forecast.evaluation?.evaluated_points ?? 0 }}</strong><span>MAE {{ formatCurrency(forecast.evaluation?.mae ?? '0') }}</span><span>RMSE {{ formatCurrency(forecast.evaluation?.rmse ?? '0') }}</span><span>平均偏差 {{ formatCurrency(forecast.evaluation?.mean_deviation ?? '0') }}</span></div></article>
      </section>
      <section v-else class="grid transaction-layout">
        <article class="panel import-panel"><h2>导入银行流水</h2><label>银行账户<select v-model="selectedAccountId"><option :value="null" disabled>请选择账户</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.bank_name }} · {{ account.account_name }} · {{ account.account_no_last4 }}</option></select></label><label>CSV / Excel 文件<input accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" type="file" @change="onFileChange" /></label><p v-if="selectedFile" class="meta">已选择：{{ selectedFile.name }}</p><p v-if="selectedAccount" class="meta">当前账户余额：{{ formatCurrency(selectedAccount.current_balance) }}</p><p v-if="importMessage" class="feedback-text">{{ importMessage }}</p><button class="primary-button" :disabled="importLoading" type="button" @click="submitImport">{{ importLoading ? '导入中...' : '开始导入' }}</button><p class="meta import-hint">CSV 必填列：transaction_no、transaction_date、direction、amount；XLSX 支持六家银行样本工作表及借方/贷方金额映射。</p></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>流水明细</h2><span class="meta">共 {{ totalTransactions }} 条</span></div><button class="primary-button" type="button" @click="downloadTransactionExport">导出 CSV</button></div><p v-if="transactionMessage" class="feedback-text">{{ transactionMessage }}</p><div v-if="transactions.length" class="table-scroll"><table><thead><tr><th>交易日期</th><th>方向</th><th>金额</th><th>对方户名</th><th>摘要</th><th>分类</th><th>匹配状态</th><th>操作</th></tr></thead><tbody><tr v-for="transaction in transactions" :key="transaction.id"><td>{{ transaction.transaction_date }}</td><td>{{ directionLabel[transaction.direction] ?? transaction.direction }}</td><td :class="transaction.direction === 'expense' ? 'expense-amount' : 'income-amount'">{{ formatCurrency(transaction.amount) }}</td><td>{{ transaction.counterparty_name || '-' }}</td><td>{{ transaction.summary || '-' }}</td><td>{{ transaction.category || '-' }}<br /><span class="meta">{{ transaction.purpose || '' }}</span></td><td><span class="pill">{{ transaction.match_status }}</span></td><td><div class="action-group"><button class="text-button" type="button" @click="showDetail(`流水 ${transaction.transaction_no}`, () => loadTransactionDetail(backendBase, token, user!.tenant_id, transaction.id))">详情</button><button class="text-button" :disabled="transactionActionLoading !== null" type="button" @click="classifyTransactionRow(transaction)">分类</button><button v-if="transaction.match_status !== 'unmatched'" class="small-danger-button" :disabled="transactionActionLoading !== null" type="button" @click="unlinkTransactionRow(transaction)">解除关联</button></div></td></tr></tbody></table></div><p v-else class="empty-state">暂无流水，请先导入 CSV 文件。</p></article>
      </section>
    </template>
    <section v-if="detailTitle" class="panel detail-panel"><div class="section-heading"><h2>{{ detailTitle }}</h2><button class="ghost-button" type="button" @click="closeDetail">关闭</button></div><p v-if="detailLoading" class="empty-state">加载详情中...</p><pre v-else>{{ detailJson(detailData) }}</pre></section>
  </main>
</template>
