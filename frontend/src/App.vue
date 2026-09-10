<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getToken, loadCurrentUser, login, logout, type User } from './services/auth'
import { loadDashboardOverview, type DashboardOverview } from './services/dashboard'
import { classifyTransaction, closeBankAccount, confirmImportPreview, createBankAccount, exportTransactions, loadAccounts, loadTransactionDetail, loadTransactions, previewStatements, retryImportErrors, scanIdleAccounts, type BankAccount, type BankAccountInput, type BankTransaction, type ImportPreview, type ImportPreviewRow, unlinkTransaction, updateBankAccount } from './services/bank'
import { assignException, batchExceptionAction, closeException, commentException, confirmMatchResult, deleteExceptionAttachment, importContracts, loadContractDetail, loadContracts, loadExceptions, loadMatchResultDetail, loadMatchResults, loadReceivables, markFalsePositive, previewExceptionAttachment, rejectMatchResult, resolveException, runMatching, type Contract, type ExceptionAttachment, type ExceptionCase, type MatchResult, type Receivable, uploadExceptionAttachment } from './services/receivables'
import { batchUpdateProjectStatus, loadProjectDetail, loadProjectRiskRules, loadProjects, updateProject, updateProjectRiskRule, type Project, type ProjectRiskRule } from './services/projects'
import { fetchJson } from './services/http'
import { authHeaders } from './services/auth'
import { activateForecastModel, backfillForecastActuals, loadForecastModels, loadLatestForecast, retryForecast, runForecast, type ForecastDetail, type ForecastModel } from './services/forecast'
import { formatCurrency } from './utils/number'
import { importFinanceRecords, loadReconciliationResults, runReconciliation, type ReconciliationResult } from './services/finance'
import { createReport, downloadReport, loadReportAudits, loadReportDetail, loadReports, type ReportAuditLog, type ReportTask } from './services/reports'

const backendBase = import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080'
const user = ref<User | null>(null)
const token = ref(getToken())
const loginName = ref('finance01')
const password = ref('')
const loginLoading = ref(false)
const loginError = ref('')
const activeView = ref<'dashboard' | 'accounts' | 'transactions' | 'receivables' | 'projects' | 'matching' | 'exceptions' | 'reconciliation' | 'reports' | 'forecast'>('dashboard')
const loading = ref(false)
const overview = ref<DashboardOverview | null>(null)
const accounts = ref<BankAccount[]>([])
const transactions = ref<BankTransaction[]>([])
const totalTransactions = ref(0)
const selectedAccountId = ref<number | null>(null)
const selectedFile = ref<File | null>(null)
const importLoading = ref(false)
const importMessage = ref('')
const importPreview = ref<ImportPreview | null>(null)
const importRetryJson = ref('')
const pageError = ref('')
const contracts = ref<Contract[]>([])
const receivables = ref<Receivable[]>([])
const exceptions = ref<ExceptionCase[]>([])
const projects = ref<Project[]>([])
const selectedExceptionIds = ref<number[]>([])
const selectedProjectIds = ref<number[]>([])
const exceptionAttachments = ref<ExceptionAttachment[]>([])
const projectEdit = ref<{ id: number; project_name: string; customer_name: string; project_manager: string; project_status: string } | null>(null)
const projectRiskRules = ref<ProjectRiskRule[]>([])
const projectMessage = ref('')
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
const reports = ref<ReportTask[]>([])
const reportType = ref('monthly')
const reportMonth = ref(new Date().toISOString().slice(0, 7))
const reportDate = ref(new Date().toISOString().slice(0, 10))
const reportLoading = ref(false)
const reportMessage = ref('')
const selectedReport = ref<ReportTask | null>(null)
const reportAudits = ref<ReportAuditLog[]>([])
const reportDetailLoading = ref(false)

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

async function loadReportTasks() {
  if (!user.value || !token.value) return
  reports.value = (await loadReports(backendBase, token.value, user.value.tenant_id)).data
  if (selectedReport.value) {
    const current = reports.value.find((report) => report.id === selectedReport.value?.id)
    if (current) await selectReport(current)
  }
}

async function selectReport(report: ReportTask) {
  if (!user.value || !token.value) return
  selectedReport.value = report
  reportDetailLoading.value = true
  try {
    const [detail, audits] = await Promise.all([
      loadReportDetail(backendBase, token.value, user.value.tenant_id, report.id),
      loadReportAudits(backendBase, token.value, user.value.tenant_id, report.id),
    ])
    selectedReport.value = detail.data
    reportAudits.value = audits.data.items
  } catch (error) {
    reportMessage.value = error instanceof Error ? error.message : '报表详情加载失败'
  } finally {
    reportDetailLoading.value = false
  }
}

async function generateReport() {
  if (!user.value || !token.value) return
  reportLoading.value = true
  reportMessage.value = ''
  try {
    const params: Record<string, string> = reportType.value === 'monthly'
      ? { month: reportMonth.value }
      : reportType.value === 'daily'
        ? { date_from: reportDate.value, date_to: reportDate.value }
        : {}
    await createReport(backendBase, token.value, user.value.tenant_id, reportType.value, params)
    await loadReportTasks()
    reportMessage.value = '报表已生成。'
  } catch (error) {
    reportMessage.value = error instanceof Error ? error.message : '报表生成失败'
  } finally {
    reportLoading.value = false
  }
}

async function downloadReportFile(report: ReportTask) {
  if (!user.value || !token.value) return
  try {
    const blob = await downloadReport(backendBase, token.value, user.value.tenant_id, report.id)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = report.file_name || `report-${report.id}.csv`
    link.click()
    URL.revokeObjectURL(url)
    reportMessage.value = '报表 CSV 已导出。'
  } catch (error) {
    reportMessage.value = error instanceof Error ? error.message : '报表导出失败'
  }
}

async function loadWorkspace() {
  if (!user.value || !token.value) return
  loading.value = true
  pageError.value = ''
  try {
    const [overviewResult, accountResult, transactionResult, contractResult, receivableResult, matchResult, exceptionResult, projectResult, riskRuleResult] = await Promise.all([
      loadDashboardOverview(backendBase, token.value, user.value.tenant_id),
      loadAccounts(backendBase, token.value, user.value.tenant_id),
      loadTransactions(backendBase, token.value, user.value.tenant_id),
      loadContracts(backendBase, token.value, user.value.tenant_id),
      loadReceivables(backendBase, token.value, user.value.tenant_id),
      loadMatchResults(backendBase, token.value, user.value.tenant_id),
      loadExceptions(backendBase, token.value, user.value.tenant_id),
      loadProjects(backendBase, token.value, user.value.tenant_id),
      loadProjectRiskRules(backendBase, token.value, user.value.tenant_id),
    ])
    overview.value = overviewResult.data
    accounts.value = accountResult.data
    transactions.value = transactionResult.data.items
    totalTransactions.value = transactionResult.data.total
    contracts.value = contractResult.data.items
    receivables.value = receivableResult.data.items
    matchResults.value = matchResult.data.items
    exceptions.value = exceptionResult.data.items
    projects.value = projectResult.data.items
    projectRiskRules.value = riskRuleResult.data
    await loadReportTasks()
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
  exceptionAttachments.value = []
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
  exceptionAttachments.value = []
}

function detailJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function fetchExceptionDetail(id: number) {
  return fetchJson<{ data: Record<string, unknown> }>(`${backendBase}/api/v1/matching/exceptions/${id}`, {
    headers: authHeaders(token.value, user.value!.tenant_id),
  }).then((result) => { exceptionAttachments.value = Array.isArray(result.data.attachments) ? result.data.attachments as ExceptionAttachment[] : []; return result })
}

function beginProjectEdit(project: Project) {
  projectEdit.value = { id: project.id, project_name: project.project_name, customer_name: project.customer_name || '', project_manager: project.project_manager || '', project_status: project.project_status }
}

async function saveProjectEdit() {
  if (!user.value || !token.value || !projectEdit.value) return
  try {
    await updateProject(backendBase, token.value, user.value.tenant_id, projectEdit.value.id, projectEdit.value)
    projectMessage.value = '项目已更新。'
    projectEdit.value = null
    await loadWorkspace()
  } catch (error) { projectMessage.value = error instanceof Error ? error.message : '项目更新失败' }
}

async function batchProjectStatus(status: string) {
  if (!user.value || !token.value || !selectedProjectIds.value.length) return
  try {
    const result = await batchUpdateProjectStatus(backendBase, token.value, user.value.tenant_id, selectedProjectIds.value, status)
    projectMessage.value = `已批量更新 ${result.data.updated} 个项目。`
    selectedProjectIds.value = []
    await loadWorkspace()
  } catch (error) { projectMessage.value = error instanceof Error ? error.message : '项目批量更新失败' }
}

async function saveRiskRule(rule: ProjectRiskRule) {
  if (!user.value || !token.value) return
  try {
    await updateProjectRiskRule(backendBase, token.value, user.value.tenant_id, rule.rule_code, rule)
    projectMessage.value = `风险规则 ${rule.rule_code} 已保存。`
    await loadWorkspace()
  } catch (error) { projectMessage.value = error instanceof Error ? error.message : '风险规则保存失败' }
}

async function batchException(action: string) {
  if (!user.value || !token.value || !selectedExceptionIds.value.length) return
  const text = action === 'assign' ? '' : window.prompt('请输入批量处理说明', '')
  if (text === null) return
  try {
    const result = await batchExceptionAction(backendBase, token.value, user.value.tenant_id, selectedExceptionIds.value, action, text)
    exceptionActionMessage.value = `已批量处理 ${result.data.updated} 条异常。`
    selectedExceptionIds.value = []
    await loadWorkspace()
  } catch (error) { exceptionActionMessage.value = error instanceof Error ? error.message : '异常批量操作失败' }
}

async function removeAttachment(attachmentId: number) {
  if (!user.value || !token.value || !window.confirm('确认删除该附件？')) return
  try {
    await deleteExceptionAttachment(backendBase, token.value, user.value.tenant_id, attachmentId)
    exceptionAttachments.value = exceptionAttachments.value.filter((item) => item.id !== attachmentId)
    exceptionActionMessage.value = '附件已删除。'
  } catch (error) { exceptionActionMessage.value = error instanceof Error ? error.message : '附件删除失败' }
}

async function previewAttachment(attachment: ExceptionAttachment, download = false) {
  if (!user.value || !token.value) return
  try {
    const blob = await previewExceptionAttachment(backendBase, token.value, user.value.tenant_id, attachment.id)
    const url = URL.createObjectURL(blob)
    if (download) { const link = document.createElement('a'); link.href = url; link.download = attachment.file_name; link.click() } else window.open(url, '_blank', 'noopener,noreferrer')
    window.setTimeout(() => URL.revokeObjectURL(url), 30000)
  } catch (error) { exceptionActionMessage.value = error instanceof Error ? error.message : '附件打开失败' }
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

async function markExceptionFalsePositive(item: ExceptionCase) {
  if (!user.value || !token.value || item.status === 'closed' || item.status === 'false_positive') return
  const reason = window.prompt('请输入误报原因', '')
  if (reason === null || !reason.trim()) return
  exceptionActionLoading.value = item.id
  exceptionActionMessage.value = ''
  try { await markFalsePositive(backendBase, token.value, user.value.tenant_id, item.id, reason.trim()); exceptionActionMessage.value = '异常已标记为误报，并记录原因。'; await loadWorkspace() }
  catch (error) { exceptionActionMessage.value = error instanceof Error ? error.message : '标记误报失败' }
  finally { exceptionActionLoading.value = null }
}

async function uploadAttachment(exceptionId: number, event: Event) {
  if (!user.value || !token.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  exceptionActionLoading.value = exceptionId
  exceptionActionMessage.value = ''
  try { await uploadExceptionAttachment(backendBase, token.value, user.value.tenant_id, exceptionId, file); exceptionActionMessage.value = `附件 ${file.name} 已上传。` }
  catch (error) { exceptionActionMessage.value = error instanceof Error ? error.message : '附件上传失败' }
  finally { exceptionActionLoading.value = null; input.value = '' }
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
  importPreview.value = null
  importRetryJson.value = ''
}

async function submitImport() {
  if (!user.value || !token.value || !selectedAccountId.value || !selectedFile.value) {
    importMessage.value = '请选择银行账户和 CSV/XLSX 文件'
    return
  }
  importLoading.value = true
  importMessage.value = ''
  try {
    const result = await previewStatements(backendBase, token.value, user.value.tenant_id, selectedAccountId.value, selectedFile.value)
    const job = result.data
    importPreview.value = job
    const templateText = Array.isArray(job.recognized_templates)
      ? `；模板 ${job.recognized_templates.filter((item: { status?: string }) => item.status === 'recognized').map((item: { bank_name?: string }) => item.bank_name).filter(Boolean).join('、') || '未识别'}`
      : ''
    importMessage.value = `预览完成：有效 ${job.success_rows ?? 0} 行，失败 ${job.failed_rows ?? 0} 行${templateText}，请确认后入账。`
  } catch (error) {
    importMessage.value = error instanceof Error ? error.message : '导入失败，请检查文件后重试'
  } finally {
    importLoading.value = false
  }
}

async function confirmPreview() {
  if (!user.value || !token.value || !importPreview.value) return
  importLoading.value = true
  try {
    const result = await confirmImportPreview(backendBase, token.value, user.value.tenant_id, importPreview.value.job_id)
    importPreview.value = result.data
    importMessage.value = `确认完成：成功 ${result.data.success_rows ?? 0} 行，跳过 ${result.data.skipped_rows ?? 0} 行，失败 ${result.data.failed_rows ?? 0} 行。`
    await loadWorkspace()
  } catch (error) { importMessage.value = error instanceof Error ? error.message : '确认导入失败' }
  finally { importLoading.value = false }
}

async function retryPreviewErrorsAction() {
  if (!user.value || !token.value || !importPreview.value) return
  let rows: Array<Record<string, unknown>>
  try {
    const parsed = JSON.parse(importRetryJson.value)
    if (!Array.isArray(parsed)) throw new Error('请输入 JSON 数组')
    rows = parsed as Array<Record<string, unknown>>
  } catch (error) { importMessage.value = error instanceof Error ? error.message : '失败行 JSON 格式不正确'; return }
  importLoading.value = true
  try {
    const result = await retryImportErrors(backendBase, token.value, user.value.tenant_id, importPreview.value.job_id, rows)
    importPreview.value = result.data
    importRetryJson.value = ''
    importMessage.value = `失败行重试完成：当前有效 ${result.data.success_rows ?? 0} 行，仍失败 ${result.data.failed_rows ?? 0} 行。`
  } catch (error) { importMessage.value = error instanceof Error ? error.message : '失败行重试失败' }
  finally { importLoading.value = false }
}

function retryPayload(rows: ImportPreviewRow[]) {
  return JSON.stringify(rows.filter((row) => row.status === 'failed').map((row) => ({ row_no: row.row_no, transaction_no: row.transaction_no || '', transaction_date: row.transaction_date || '', direction: row.direction || 'income', amount: row.amount || '', balance_after: row.balance_after || '', counterparty_name: row.counterparty_name || '', summary: row.summary || '' })), null, 2)
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
      <button :class="{ active: activeView === 'projects' }" type="button" @click="activeView = 'projects'">项目资金</button>
      <button :class="{ active: activeView === 'matching' }" type="button" @click="activeView = 'matching'">匹配结果</button>
      <button :class="{ active: activeView === 'exceptions' }" type="button" @click="activeView = 'exceptions'">异常事项</button>
      <button :class="{ active: activeView === 'reconciliation' }" type="button" @click="activeView = 'reconciliation'">财务对账</button>
      <button :class="{ active: activeView === 'reports' }" type="button" @click="activeView = 'reports'; loadReportTasks()">报表中心</button>
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
      <section v-else-if="activeView === 'projects'" class="panel table-panel">
        <div class="section-heading"><div><h2>项目资金与风险</h2><span class="meta">共 {{ projects.length }} 个项目</span></div><div class="action-group"><button class="small-primary-button" :disabled="!selectedProjectIds.length" type="button" @click="batchProjectStatus('active')">批量启用</button><button class="small-primary-button" :disabled="!selectedProjectIds.length" type="button" @click="batchProjectStatus('completed')">批量完成</button><button class="ghost-button" type="button" @click="loadWorkspace">刷新</button></div></div>
        <p v-if="projectMessage" class="feedback-text">{{ projectMessage }}</p>
        <div v-if="projectEdit" class="inline-edit-form"><label>项目名称<input v-model.trim="projectEdit.project_name" /></label><label>客户名称<input v-model.trim="projectEdit.customer_name" /></label><label>负责人<input v-model.trim="projectEdit.project_manager" /></label><label>状态<select v-model="projectEdit.project_status"><option value="active">active</option><option value="paused">paused</option><option value="completed">completed</option><option value="cancelled">cancelled</option></select></label><button class="primary-button" type="button" @click="saveProjectEdit">保存项目</button><button class="ghost-button" type="button" @click="projectEdit = null">取消</button></div>
        <div v-if="projects.length" class="table-scroll"><table><thead><tr><th>选择</th><th>项目</th><th>客户/负责人</th><th>合同金额</th><th>应收/已收</th><th>逾期金额</th><th>回款率</th><th>风险</th><th>操作</th></tr></thead><tbody><tr v-for="project in projects" :key="project.id"><td><input v-model="selectedProjectIds" type="checkbox" :value="project.id" /></td><td>{{ project.project_no }}<br />{{ project.project_name }}</td><td>{{ project.customer_name || '-' }}<br /><span class="meta">{{ project.project_manager || '未分派负责人' }}</span></td><td>{{ formatCurrency(project.contract_amount) }}</td><td>{{ formatCurrency(project.receivable_amount) }}<br />{{ formatCurrency(project.paid_amount) }}</td><td>{{ formatCurrency(project.overdue_amount) }}</td><td>{{ `${(Number(project.paid_rate) * 100).toFixed(1)}%` }}</td><td><span class="pill" :class="project.risk_level === 'danger' ? 'risk-high' : project.risk_level === 'warning' ? 'risk-medium' : 'risk-low'">{{ project.risk_level }} · {{ project.risk_score }}</span><br /><span class="meta">{{ project.risk_items.join('；') || '暂无风险项' }}</span></td><td><div class="action-group"><button class="text-button" type="button" @click="beginProjectEdit(project)">编辑</button><button class="text-button" type="button" @click="showDetail(`项目 ${project.project_no}`, () => loadProjectDetail(backendBase, token, user!.tenant_id, project.id))">详情</button></div></td></tr></tbody></table></div><p v-else class="empty-state">暂无项目数据，请先导入合同主数据。</p>
        <div v-if="projectRiskRules.length" class="risk-rule-editor"><h3>项目风险规则</h3><div v-for="rule in projectRiskRules" :key="rule.rule_code" class="rule-row"><span>{{ rule.rule_code }}</span><label>阈值<input v-model="rule.threshold" type="number" step="0.01" /></label><label>扣分<input v-model="rule.penalty" type="number" step="1" /></label><label><input v-model="rule.enabled" type="checkbox" />启用</label><button class="text-button" type="button" @click="saveRiskRule(rule)">保存</button></div></div>
      </section>
      <section v-else-if="activeView === 'matching'" class="panel table-panel">
        <div class="section-heading"><div><h2>匹配结果</h2><span class="meta">共 {{ matchResults.length }} 条</span></div><span class="meta">待确认结果按匹配组整体处理</span></div>
        <p v-if="matchActionMessage" class="feedback-text">{{ matchActionMessage }}</p>
        <div v-if="matchResults.length" class="table-scroll"><table><thead><tr><th>流水</th><th>流水金额</th><th>分配金额</th><th>合同/节点</th><th>组/模式</th><th>置信度</th><th>匹配理由</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in matchResults" :key="item.id"><td>{{ item.transaction_no }}</td><td class="income-amount">{{ formatCurrency(item.amount) }}</td><td class="income-amount">{{ formatCurrency(item.allocated_amount) }}<br /><span class="meta">{{ item.allocation_count }} 明细 / 合计 {{ formatCurrency(item.allocation_total) }}</span></td><td>{{ item.contract_no || '-' }}<br />{{ item.contract_name || '-' }} · {{ item.node_name || '-' }}</td><td>{{ item.allocation_mode }}<br /><span class="meta">{{ item.match_group_id }}</span></td><td>{{ item.confidence_level }}</td><td class="reason-cell">{{ item.match_reason }}</td><td><span class="pill">{{ item.match_status }}</span></td><td><div class="action-group"><button class="text-button" type="button" @click="showDetail(`匹配结果 ${item.id}`, () => loadMatchResultDetail(backendBase, token, user!.tenant_id, item.id))">详情</button><button v-if="item.match_status === 'suggested'" class="small-primary-button" :disabled="matchActionLoading !== null" type="button" @click="confirmResult(item.id)">{{ matchActionLoading === item.id ? '处理中...' : '确认组' }}</button><button v-if="item.match_status === 'suggested'" class="small-danger-button" :disabled="matchActionLoading !== null" type="button" @click="rejectResult(item.id)">拒绝组</button><span v-if="item.match_status !== 'suggested'" class="meta">已处理</span></div></td></tr></tbody></table></div>
        <p v-else class="empty-state">暂无匹配结果，请先在异常事项页面运行回款匹配。</p>
      </section>
      <section v-else-if="activeView === 'exceptions'" class="panel"><div class="section-heading"><h2>异常事项</h2><div class="action-group"><button class="small-danger-button" :disabled="!selectedExceptionIds.length" type="button" @click="batchException('false_positive')">批量标记误报</button><button class="small-primary-button" :disabled="!selectedExceptionIds.length" type="button" @click="batchException('assign')">批量分派</button><button class="primary-button" :disabled="matchingLoading" type="button" @click="submitMatching">{{ matchingLoading ? '匹配中...' : '运行回款匹配' }}</button></div></div><p v-if="matchingMessage" class="feedback-text">{{ matchingMessage }}</p><p v-if="exceptionActionMessage" class="feedback-text">{{ exceptionActionMessage }}</p><div v-if="exceptions.length" class="list-stack"><div v-for="item in exceptions" :key="item.id" class="exception-row"><div><input v-model="selectedExceptionIds" type="checkbox" :value="item.id" /><strong>{{ item.title }}</strong><p class="meta">{{ item.description }}</p><p class="meta">处理人：{{ item.owner_user_id ? `用户 ${item.owner_user_id}` : '未分派' }}</p></div><div class="exception-actions"><span class="pill">{{ item.exception_type }} · {{ item.status }}</span><div class="action-group"><button class="text-button" type="button" @click="showDetail(`异常 ${item.exception_no}`, () => fetchExceptionDetail(item.id))">详情</button><button v-if="item.status !== 'closed' && item.status !== 'false_positive' && !item.owner_user_id" class="small-primary-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'assign')">分派给我</button><button v-if="item.status !== 'closed' && item.status !== 'false_positive'" class="small-primary-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'comment')">备注</button><button v-if="item.status === 'new' || item.status === 'in_progress'" class="small-primary-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'resolve')">处理完成</button><button v-if="item.status === 'resolved'" class="small-danger-button" :disabled="exceptionActionLoading !== null" type="button" @click="runExceptionAction(item.id, 'close')">关闭</button><button v-if="item.status !== 'closed' && item.status !== 'false_positive'" class="small-danger-button" :disabled="exceptionActionLoading !== null" type="button" @click="markExceptionFalsePositive(item)">标记误报</button><label v-if="item.status !== 'closed' && item.status !== 'false_positive'" class="attachment-button">上传附件<input type="file" @change="uploadAttachment(item.id, $event)" /></label></div></div></div></div><p v-else class="empty-state">暂无异常事项。点击“运行回款匹配”生成结果。</p></section>
      <section v-else-if="activeView === 'reconciliation'" class="grid reconciliation-layout">
        <article class="panel import-panel"><h2>导入财务记录</h2><label>财务 CSV 文件<input accept=".csv,text/csv" type="file" @change="onFinanceFileChange" /></label><p v-if="financeFile" class="meta">已选择：{{ financeFile.name }}</p><p v-if="financeImportMessage" class="feedback-text">{{ financeImportMessage }}</p><button class="primary-button" type="button" @click="submitFinanceImport">导入财务记录</button><p class="meta import-hint">必填列：record_no、record_type、record_date、amount；记录类型支持 receipt、payment、voucher、journal。</p></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>银行账/财务账对账</h2><span class="meta">按金额、方向、对手方和 3 天日期窗口匹配</span></div><button class="primary-button" :disabled="reconciliationLoading" type="button" @click="submitReconciliation">{{ reconciliationLoading ? '对账中...' : '运行对账' }}</button></div><div class="filter-row"><label>开始日期<input v-model="reconciliationDateFrom" type="date" /></label><label>结束日期<input v-model="reconciliationDateTo" type="date" /></label></div><p v-if="reconciliationMessage" class="feedback-text">{{ reconciliationMessage }}</p><div v-if="reconciliationSummary" class="forecast-evaluation"><strong>匹配 {{ reconciliationSummary.matched }}</strong><span>银行未记账 {{ reconciliationSummary.bank_unrecorded }}</span><span>财务未在银行发生 {{ reconciliationSummary.finance_unmatched }}</span></div><div v-if="reconciliationResults.length" class="table-scroll"><table><thead><tr><th>差异类型</th><th>来源编号</th><th>日期</th><th>金额</th><th>标题</th><th>状态</th></tr></thead><tbody><tr v-for="item in reconciliationResults" :key="item.id"><td>{{ item.exception_type === 'bank_unrecorded' ? '银行未记账' : '财务未在银行发生' }}</td><td>{{ item.transaction_no || item.record_no || '-' }}</td><td>{{ item.transaction_date || item.record_date || '-' }}</td><td>{{ formatCurrency(item.bank_amount || item.finance_amount || '0') }}</td><td>{{ item.title }}<br /><span class="meta">{{ item.description }}</span></td><td><span class="pill">{{ item.status }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无对账差异，请先导入财务记录并运行对账。</p></article>
      </section>
      <section v-else-if="activeView === 'reports'" class="reports-page">
        <section class="report-type-strip" aria-label="报表类型">
          <button :class="{ active: reportType === 'daily' }" type="button" @click="reportType = 'daily'">日报</button>
          <button :class="{ active: reportType === 'monthly' }" type="button" @click="reportType = 'monthly'">月报</button>
          <button :class="{ active: reportType === 'health' }" type="button" @click="reportType = 'health'">资金体检报告</button>
        </section>
        <section class="grid reports-layout">
          <article class="panel import-panel"><h2>生成报表</h2><label>报表类型<select v-model="reportType"><option value="daily">日报</option><option value="monthly">月报</option><option value="health">资金体检报告</option></select></label><label v-if="reportType === 'monthly'">统计月份<input v-model="reportMonth" type="month" /></label><label v-if="reportType === 'daily'">统计日期<input v-model="reportDate" type="date" /></label><button class="primary-button" :disabled="reportLoading" type="button" @click="generateReport">{{ reportLoading ? '生成中...' : '生成报表' }}</button><p v-if="reportMessage" class="feedback-text">{{ reportMessage }}</p><p class="meta import-hint">报表使用实时业务数据生成，生成后可查看详情、健康评分、风险项和审计记录。</p></article>
          <article class="panel table-panel"><div class="section-heading"><div><h2>报表任务</h2><span class="meta">最近 {{ reports.length }} 条</span></div><button class="ghost-button" type="button" @click="loadReportTasks">刷新</button></div><div v-if="reports.length" class="table-scroll"><table><thead><tr><th>报表类型</th><th>统计范围</th><th>状态</th><th>文件</th><th>生成时间</th><th>操作</th></tr></thead><tbody><tr v-for="report in reports" :key="report.id" :class="{ 'selected-row': selectedReport?.id === report.id }"><td>{{ report.report_type === 'health' ? '资金体检' : report.report_type === 'monthly' ? '月报' : '日报' }}</td><td>{{ report.date_from || '-' }} 至 {{ report.date_to || '-' }}</td><td><span class="pill">{{ report.status }}</span></td><td>{{ report.file_name || '-' }}</td><td>{{ report.created_at }}</td><td><div class="action-group"><button class="text-button" type="button" @click="selectReport(report)">查看详情</button><button v-if="report.status === 'success'" class="text-button" type="button" @click="downloadReportFile(report)">下载 CSV</button></div></td></tr></tbody></table></div><p v-else class="empty-state">暂无报表任务，请先生成报表。</p></article>
        </section>
        <section class="grid report-detail-layout">
          <article class="panel report-preview"><div class="section-heading"><div><h2>报表详情与预览</h2><span class="meta">{{ selectedReport ? `任务 #${selectedReport.id}` : '请选择报表任务' }}</span></div><span v-if="reportDetailLoading" class="meta">加载中...</span></div><template v-if="selectedReport?.result"><div class="report-kpi-grid"><div><span class="label">报表类型</span><strong>{{ selectedReport.result.report_name || '-' }}</strong></div><div><span class="label">净现金流</span><strong>{{ formatCurrency(String(selectedReport.result.net_cashflow ?? '0')) }}</strong></div><div v-if="selectedReport.report_type === 'health'"><span class="label">健康评分</span><strong class="health-score">{{ selectedReport.result.health_score ?? '-' }}</strong></div><div v-if="selectedReport.report_type === 'health'"><span class="label">健康等级</span><strong>{{ selectedReport.result.health_level ?? '-' }}</strong></div></div><div v-if="selectedReport.report_type === 'health'" class="risk-list"><h3>风险项</h3><div v-if="Array.isArray(selectedReport.result.risk_items) && selectedReport.result.risk_items.length" v-for="risk in selectedReport.result.risk_items as Array<Record<string, unknown>>" :key="String(risk.title)" class="list-row"><span>{{ risk.title }}：{{ risk.description }}</span><span class="pill">{{ risk.count }}</span></div><p v-else class="empty-state">当前未发现风险项。</p></div><div v-else class="report-summary-grid"><span>收入 {{ formatCurrency(String(selectedReport.result.income_total ?? '0')) }}</span><span>支出 {{ formatCurrency(String(selectedReport.result.expense_total ?? '0')) }}</span><span>交易 {{ selectedReport.result.transaction_count ?? 0 }} 笔</span><span>匹配率 {{ `${(Number(selectedReport.result.match_rate ?? 0) * 100).toFixed(1)}%` }}</span></div></template><p v-else class="empty-state">请选择一条已生成的报表查看详情。</p></article>
          <article class="panel table-panel"><div class="section-heading"><div><h2>报表审计记录</h2><span class="meta">{{ reportAudits.length }} 条</span></div></div><div v-if="reportAudits.length" class="audit-list"><div v-for="audit in reportAudits" :key="audit.id" class="audit-row"><div><strong>{{ audit.action === 'CREATE_REPORT' ? '生成报表' : audit.action === 'DOWNLOAD_REPORT' ? '下载报表' : audit.action }}</strong><p class="meta">{{ audit.detail || '-' }}</p></div><span class="meta">{{ audit.created_at }}</span></div></div><p v-else class="empty-state">请选择报表任务查看审计记录。</p></article>
        </section>
      </section>
      <section v-else-if="activeView === 'forecast'" class="grid forecast-layout">
        <article class="panel import-panel"><h2>现金流预测</h2><label>预测天数<input v-model.number="forecastHorizon" min="1" max="90" type="number" /></label><label>历史窗口<input v-model.number="forecastWindow" min="1" max="30" type="number" /></label><button class="primary-button" :disabled="forecastLoading" type="button" @click="submitForecast">{{ forecastLoading ? '预测中...' : '生成预测' }}</button><button v-if="forecast.job?.status === 'failed'" class="ghost-button" :disabled="forecastLoading" type="button" @click="retryForecastJob">重试失败任务</button><button v-if="forecast.job?.status === 'success'" class="ghost-button" :disabled="forecastLoading" type="button" @click="backfillForecast">回填实际金额</button><p v-if="forecastMessage" class="feedback-text">{{ forecastMessage }}</p><h3>模型版本</h3><div v-for="model in forecastModels" :key="model.version" class="list-row"><span>{{ model.version }} · {{ model.model_name }}</span><button v-if="model.status !== 'active'" class="text-button" type="button" @click="activateModel(model.version)">启用</button><span v-else class="pill">当前启用</span></div></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>预测结果</h2><span class="meta">{{ forecast.job ? `任务 #${forecast.job.id} · ${forecast.job.status} · ${forecast.job.model_version || '-'}` : '暂无已完成预测' }}</span></div><span v-if="forecast.job?.model_name" class="pill">{{ forecast.job.model_name }}</span></div><p v-if="forecast.job?.error_message" class="error-banner">{{ forecast.job.error_message }}</p><div v-if="forecast.results.length" class="table-scroll"><table><thead><tr><th>日期</th><th>预测净现金流</th><th>预计应收</th><th>预计余额</th><th>实际金额</th><th>偏差</th><th>风险</th></tr></thead><tbody><tr v-for="item in forecast.results" :key="item.id"><td>{{ item.forecast_date }}</td><td :class="Number(item.forecast_amount) < 0 ? 'expense-amount' : 'income-amount'">{{ formatCurrency(item.forecast_amount) }}</td><td>{{ formatCurrency(item.expected_receivable) }}</td><td>{{ formatCurrency(item.projected_balance) }}</td><td>{{ item.actual_amount !== null ? formatCurrency(item.actual_amount) : '-' }}</td><td>{{ item.deviation_amount !== null ? formatCurrency(item.deviation_amount) : '-' }}</td><td><span class="pill" :class="`risk-${item.risk_level}`">{{ item.risk_level }} · {{ item.risk_message }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无预测结果，请先生成预测。</p><div v-if="forecast.job" class="forecast-evaluation"><strong>评估点数 {{ forecast.evaluation?.evaluated_points ?? 0 }}</strong><span>MAE {{ formatCurrency(forecast.evaluation?.mae ?? '0') }}</span><span>RMSE {{ formatCurrency(forecast.evaluation?.rmse ?? '0') }}</span><span>平均偏差 {{ formatCurrency(forecast.evaluation?.mean_deviation ?? '0') }}</span></div></article>
      </section>
      <section v-else class="grid transaction-layout">
        <article class="panel import-panel"><h2>导入银行流水</h2><label>银行账户<select v-model="selectedAccountId"><option :value="null" disabled>请选择账户</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.bank_name }} · {{ account.account_name }} · {{ account.account_no_last4 }}</option></select></label><label>CSV / Excel 文件<input accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" type="file" @change="onFileChange" /></label><p v-if="selectedFile" class="meta">已选择：{{ selectedFile.name }}</p><p v-if="selectedAccount" class="meta">当前账户余额：{{ formatCurrency(selectedAccount.current_balance) }}</p><p v-if="importMessage" class="feedback-text">{{ importMessage }}</p><div class="action-group"><button class="primary-button" :disabled="importLoading" type="button" @click="submitImport">{{ importLoading ? '处理中...' : '预览导入' }}</button><button v-if="importPreview && (importPreview.status === 'preview_pending' || importPreview.status === 'preview_failed')" class="primary-button" :disabled="importLoading || importPreview.success_rows === 0" type="button" @click="confirmPreview">确认入账</button></div><p class="meta import-hint">先预览并核对有效行，确认后才写入正式流水。XLSX 支持六家银行真实样本工作表及借方/贷方金额映射。</p><template v-if="importPreview"><h3>预览结果</h3><p class="meta">任务 #{{ importPreview.job_id }} · 共 {{ importPreview.total_rows }} 行 · 有效 {{ importPreview.success_rows }} · 失败 {{ importPreview.failed_rows }} · 跳过 {{ importPreview.skipped_rows }}</p><div v-if="importPreview.preview_rows.length" class="table-scroll"><table><thead><tr><th>行号</th><th>流水号</th><th>日期</th><th>方向</th><th>金额</th><th>状态</th><th>错误</th></tr></thead><tbody><tr v-for="row in importPreview.preview_rows" :key="row.id"><td>{{ row.row_no }}</td><td>{{ row.transaction_no || '-' }}</td><td>{{ row.transaction_date || '-' }}</td><td>{{ row.direction || '-' }}</td><td>{{ row.amount || '-' }}</td><td><span class="pill">{{ row.status }}</span></td><td>{{ row.error_message || '-' }}</td></tr></tbody></table></div><template v-if="importPreview.failed_rows > 0"><label>失败行修正 JSON<textarea v-model="importRetryJson" rows="6" :placeholder="retryPayload(importPreview.preview_rows)"></textarea></label><button class="ghost-button" :disabled="importLoading" type="button" @click="retryPreviewErrorsAction">提交失败行重试</button><p class="meta import-hint">JSON 为数组，每项至少包含 row_no、transaction_no、transaction_date（YYYY-MM-DD）、direction、amount；可点击占位内容复制后修正。</p></template></template></article>
        <article class="panel table-panel"><div class="section-heading"><div><h2>流水明细</h2><span class="meta">共 {{ totalTransactions }} 条</span></div><button class="primary-button" type="button" @click="downloadTransactionExport">导出 CSV</button></div><p v-if="transactionMessage" class="feedback-text">{{ transactionMessage }}</p><div v-if="transactions.length" class="table-scroll"><table><thead><tr><th>交易日期</th><th>方向</th><th>金额</th><th>对方户名</th><th>摘要</th><th>分类</th><th>匹配状态</th><th>操作</th></tr></thead><tbody><tr v-for="transaction in transactions" :key="transaction.id"><td>{{ transaction.transaction_date }}</td><td>{{ directionLabel[transaction.direction] ?? transaction.direction }}</td><td :class="transaction.direction === 'expense' ? 'expense-amount' : 'income-amount'">{{ formatCurrency(transaction.amount) }}</td><td>{{ transaction.counterparty_name || '-' }}</td><td>{{ transaction.summary || '-' }}</td><td>{{ transaction.category || '-' }}<br /><span class="meta">{{ transaction.purpose || '' }}</span></td><td><span class="pill">{{ transaction.match_status }}</span></td><td><div class="action-group"><button class="text-button" type="button" @click="showDetail(`流水 ${transaction.transaction_no}`, () => loadTransactionDetail(backendBase, token, user!.tenant_id, transaction.id))">详情</button><button class="text-button" :disabled="transactionActionLoading !== null" type="button" @click="classifyTransactionRow(transaction)">分类</button><button v-if="transaction.match_status !== 'unmatched'" class="small-danger-button" :disabled="transactionActionLoading !== null" type="button" @click="unlinkTransactionRow(transaction)">解除关联</button></div></td></tr></tbody></table></div><p v-else class="empty-state">暂无流水，请先导入 CSV 文件。</p></article>
      </section>
    </template>
    <section v-if="detailTitle" class="panel detail-panel"><div class="section-heading"><h2>{{ detailTitle }}</h2><button class="ghost-button" type="button" @click="closeDetail">关闭</button></div><p v-if="detailLoading" class="empty-state">加载详情中...</p><template v-else><div v-if="exceptionAttachments.length" class="attachment-list"><h3>异常附件</h3><div v-for="attachment in exceptionAttachments" :key="attachment.id" class="list-row"><span>{{ attachment.file_name }}（{{ attachment.file_size }} bytes）</span><span class="action-group"><button class="text-button" type="button" @click="previewAttachment(attachment)">预览</button><button class="text-button" type="button" @click="previewAttachment(attachment, true)">下载</button><button class="small-danger-button" type="button" @click="removeAttachment(attachment.id)">删除</button></span></div></div><pre>{{ detailJson(detailData) }}</pre></template></section>
  </main>
</template>
