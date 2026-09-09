<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getToken, loadCurrentUser, login, logout, type User } from './services/auth'
import { loadDashboardOverview, type DashboardOverview } from './services/dashboard'
import { importStatements, loadAccounts, loadTransactions, type BankAccount, type BankTransaction } from './services/bank'
import { importContracts, loadContracts, loadExceptions, loadReceivables, runMatching, type Contract, type ExceptionCase, type Receivable } from './services/receivables'
import { formatCurrency } from './utils/number'

const backendBase = import.meta.env.VITE_BACKEND_BASE_URL ?? 'http://localhost:8080'
const user = ref<User | null>(null)
const token = ref(getToken())
const loginName = ref('finance01')
const password = ref('')
const loginLoading = ref(false)
const loginError = ref('')
const activeView = ref<'dashboard' | 'transactions' | 'receivables' | 'exceptions'>('dashboard')
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
const contractFile = ref<File | null>(null)
const contractImportMessage = ref('')
const matchingLoading = ref(false)
const matchingMessage = ref('')

const loggedIn = computed(() => Boolean(user.value && token.value))
const selectedAccount = computed(() => accounts.value.find((account) => account.id === selectedAccountId.value))
const overviewCards = computed(() => {
  if (!overview.value) return []
  return [
    { label: '全账户余额', value: formatCurrency(overview.value.total_balance), hint: '来自银行账户当前余额' },
    { label: '昨日净流入', value: formatCurrency(overview.value.yesterday_net_inflow), hint: '收入与支出流水净额' },
    { label: '待处理异常', value: String(overview.value.pending_exceptions), hint: '当前异常事项数量' },
    { label: '闲置账户', value: String(overview.value.idle_accounts), hint: '状态为闲置的账户' },
  ]
})
const directionLabel: Record<string, string> = { income: '收入', expense: '支出', transfer: '内部转账', refund: '退款', reversal: '冲正' }

async function submitLogin() {
  loginLoading.value = true
  loginError.value = ''
  try {
    user.value = await login(backendBase, loginName.value.trim(), password.value)
    token.value = getToken()
    password.value = ''
    await loadWorkspace()
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

async function loadWorkspace() {
  if (!user.value || !token.value) return
  loading.value = true
  pageError.value = ''
  try {
    const [overviewResult, accountResult, transactionResult, contractResult, receivableResult, exceptionResult] = await Promise.all([
      loadDashboardOverview(backendBase, token.value, user.value.tenant_id),
      loadAccounts(backendBase, token.value, user.value.tenant_id),
      loadTransactions(backendBase, token.value, user.value.tenant_id),
      loadContracts(backendBase, token.value, user.value.tenant_id),
      loadReceivables(backendBase, token.value, user.value.tenant_id),
      loadExceptions(backendBase, token.value, user.value.tenant_id),
    ])
    overview.value = overviewResult.data
    accounts.value = accountResult.data
    transactions.value = transactionResult.data.items
    totalTransactions.value = transactionResult.data.total
    contracts.value = contractResult.data.items
    receivables.value = receivableResult.data
    exceptions.value = exceptionResult.data
    if (!selectedAccountId.value && accounts.value.length > 0) selectedAccountId.value = accounts.value[0].id
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '工作台加载失败，请刷新重试'
  } finally {
    loading.value = false
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
    importMessage.value = '请选择银行账户和 CSV 文件'
    return
  }
  importLoading.value = true
  importMessage.value = ''
  try {
    const result = await importStatements(backendBase, token.value, user.value.tenant_id, selectedAccountId.value, selectedFile.value)
    const job = result.data
    importMessage.value = `导入${job.status === 'success' ? '完成' : '结束'}：成功 ${job.success_rows ?? 0} 行，失败 ${job.failed_rows ?? 0} 行`
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
      <button :class="{ active: activeView === 'transactions' }" type="button" @click="activeView = 'transactions'">银行流水</button>
      <button :class="{ active: activeView === 'receivables' }" type="button" @click="activeView = 'receivables'">合同应收</button>
      <button :class="{ active: activeView === 'exceptions' }" type="button" @click="activeView = 'exceptions'">异常事项</button>
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
      <section v-if="activeView === 'receivables'" class="grid receivable-layout">
        <article class="panel import-panel"><h2>导入合同应收</h2><label>合同 CSV 文件<input accept=".csv,text/csv" type="file" @change="onContractFileChange" /></label><p v-if="contractFile" class="meta">已选择：{{ contractFile.name }}</p><p v-if="contractImportMessage" class="feedback-text">{{ contractImportMessage }}</p><button class="primary-button" type="button" @click="submitContractImport">导入合同</button><p class="meta import-hint">必填列：contract_no、contract_name、customer_name、contract_amount、node_name、node_type、due_date、plan_amount。</p></article>
        <article class="panel table-panel"><div class="section-heading"><h2>应收计划</h2><span class="meta">{{ receivables.length }} 个节点</span></div><div v-if="receivables.length" class="table-scroll"><table><thead><tr><th>合同</th><th>客户</th><th>节点</th><th>应收日期</th><th>计划金额</th><th>已收金额</th><th>状态</th></tr></thead><tbody><tr v-for="item in receivables" :key="item.id"><td>{{ item.contract_no }}<br />{{ item.contract_name }}</td><td>{{ item.customer_name }}</td><td>{{ item.node_name }}</td><td>{{ item.due_date }}</td><td>{{ formatCurrency(item.plan_amount) }}</td><td>{{ formatCurrency(item.paid_amount) }}</td><td><span class="pill">{{ item.status }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无应收计划，请先导入合同 CSV。</p></article>
      </section>
      <section v-else-if="activeView === 'exceptions'" class="panel"><div class="section-heading"><h2>异常事项</h2><button class="primary-button" :disabled="matchingLoading" type="button" @click="submitMatching">{{ matchingLoading ? '匹配中...' : '运行回款匹配' }}</button></div><p v-if="matchingMessage" class="feedback-text">{{ matchingMessage }}</p><div v-if="exceptions.length" class="list-stack"><div v-for="item in exceptions" :key="item.id" class="exception-row"><div><strong>{{ item.title }}</strong><p class="meta">{{ item.description }}</p></div><span class="pill">{{ item.exception_type }} · {{ item.status }}</span></div></div><p v-else class="empty-state">暂无异常事项。点击“运行回款匹配”生成结果。</p></section>
      <section v-else class="grid transaction-layout">
        <article class="panel import-panel"><h2>导入银行流水</h2><label>银行账户<select v-model="selectedAccountId"><option :value="null" disabled>请选择账户</option><option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.bank_name }} · {{ account.account_name }} · {{ account.account_no_last4 }}</option></select></label><label>CSV 文件<input accept=".csv,text/csv" type="file" @change="onFileChange" /></label><p v-if="selectedFile" class="meta">已选择：{{ selectedFile.name }}</p><p v-if="selectedAccount" class="meta">当前账户余额：{{ formatCurrency(selectedAccount.current_balance) }}</p><p v-if="importMessage" class="feedback-text">{{ importMessage }}</p><button class="primary-button" :disabled="importLoading" type="button" @click="submitImport">{{ importLoading ? '导入中...' : '开始导入' }}</button><p class="meta import-hint">必填列：transaction_no、transaction_date、direction、amount。</p></article>
        <article class="panel table-panel"><div class="section-heading"><h2>流水明细</h2><span class="meta">共 {{ totalTransactions }} 条</span></div><div v-if="transactions.length" class="table-scroll"><table><thead><tr><th>交易日期</th><th>方向</th><th>金额</th><th>对方户名</th><th>摘要</th><th>匹配状态</th></tr></thead><tbody><tr v-for="transaction in transactions" :key="transaction.id"><td>{{ transaction.transaction_date }}</td><td>{{ directionLabel[transaction.direction] ?? transaction.direction }}</td><td :class="transaction.direction === 'expense' ? 'expense-amount' : 'income-amount'">{{ formatCurrency(transaction.amount) }}</td><td>{{ transaction.counterparty_name || '-' }}</td><td>{{ transaction.summary || '-' }}</td><td><span class="pill">{{ transaction.match_status }}</span></td></tr></tbody></table></div><p v-else class="empty-state">暂无流水，请先导入 CSV 文件。</p></article>
      </section>
    </template>
  </main>
</template>
