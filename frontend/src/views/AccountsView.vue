<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  closeBankAccount,
  confirmImportPreview,
  createBankAccount,
  createBankConnection,
  deleteBankConnection,
  loadAccounts,
  loadBankConnections,
  loadBankTemplates,
  previewStatements,
  retryImportErrors,
  scanIdleAccounts,
  testBankConnection,
  type BankAccount,
  type BankAccountInput,
  type BankConnection,
  type BankTemplate,
  type ImportPreview,
  type ImportPreviewRow,
  updateBankAccount,
} from '../services/bank'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'

const router = useRouter()
const session = useSession()
const base = apiBase()
const accounts = ref<BankAccount[]>([])
const total = ref(0)
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
const loading = ref(false)
const error = ref('')
const accountId = ref<number | null>(null)
const file = ref<File | null>(null)
const preview = ref<ImportPreview | null>(null)
const retryJson = ref('')
const importMessage = ref('')
const importLoading = ref(false)
const editingId = ref<number | null>(null)
const accountForm = ref<BankAccountInput>({
  bankCode: '',
  bankName: '',
  accountName: '',
  accountNo: '',
  currency: 'CNY',
  currentBalance: '0',
})
const accountMessage = ref('')
const connections = ref<BankConnection[]>([])
const templates = ref<BankTemplate[]>([])
const connectionForm = ref({ bank_code: '', bank_name: '', bank_account_id: '' })
const connectionMessage = ref('')
const testResults = ref<Record<number, string>>({})
const testFiles = ref<Record<number, File | null>>({})
const selectedTemplate = ref<BankTemplate | null>(null)
const selectedAccount = computed(() => accounts.value.find((item) => item.id === accountId.value))
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await loadAccounts(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
    )
    accounts.value = result.data.items
    total.value = result.data.total
    if (!accountId.value) accountId.value = accounts.value[0]?.id ?? null
    const [connectionList, templateList] = await Promise.all([
      loadBankConnections(base, session.token.value, session.user.value.tenant_id),
      loadBankTemplates(base, session.token.value, session.user.value.tenant_id),
    ])
    connections.value = connectionList.data
    templates.value = templateList.data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账户加载失败'
  } finally {
    loading.value = false
  }
}
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  preview.value = null
  importMessage.value = ''
}
async function startPreview() {
  if (!session.user.value || !session.token.value || !accountId.value || !file.value) {
    importMessage.value = '请选择银行账户和 CSV/XLSX 文件'
    return
  }
  importLoading.value = true
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
    importMessage.value = '预览完成，请核对后确认入账。'
  } catch (cause) {
    importMessage.value = cause instanceof Error ? cause.message : '预览失败'
  } finally {
    importLoading.value = false
  }
}
async function confirm() {
  if (!session.user.value || !session.token.value || !preview.value) return
  importLoading.value = true
  try {
    preview.value = (
      await confirmImportPreview(
        base,
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
      )
    ).data
    importMessage.value = '流水已确认入账。'
    await load()
  } catch (cause) {
    importMessage.value = cause instanceof Error ? cause.message : '确认入账失败'
  } finally {
    importLoading.value = false
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
    importMessage.value = '失败行已重新校验。'
  } catch (cause) {
    importMessage.value = cause instanceof Error ? cause.message : '失败行 JSON 格式不正确'
  }
}
function retryPayload(rows: ImportPreviewRow[]) {
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
async function createConnection() {
  if (!session.user.value || !session.token.value) return
  if (!connectionForm.value.bank_code.trim()) {
    connectionMessage.value = '请填写银行编码'
    return
  }
  try {
    await createBankConnection(base, session.token.value, session.user.value.tenant_id, {
      bank_code: connectionForm.value.bank_code.trim(),
      bank_name: connectionForm.value.bank_name.trim() || undefined,
      bank_account_id: connectionForm.value.bank_account_id || undefined,
    })
    connectionMessage.value = '接入配置已保存。'
    connectionForm.value = { bank_code: '', bank_name: '', bank_account_id: '' }
    await load()
  } catch (cause) {
    connectionMessage.value = cause instanceof Error ? cause.message : '接入配置保存失败'
  }
}
async function removeConnection(connection: BankConnection) {
  if (!session.user.value || !session.token.value) return
  try {
    await deleteBankConnection(
      base,
      session.token.value,
      session.user.value.tenant_id,
      connection.id,
    )
    connectionMessage.value = `接入配置 ${connection.bank_name} 已删除。`
    await load()
  } catch (cause) {
    connectionMessage.value = cause instanceof Error ? cause.message : '接入配置删除失败'
  }
}
function chooseTestFile(id: number, event: Event) {
  testFiles.value[id] = (event.target as HTMLInputElement).files?.[0] ?? null
}
async function testConnection(connection: BankConnection) {
  if (!session.user.value || !session.token.value) return
  const testFile = testFiles.value[connection.id]
  if (!testFile) {
    testResults.value[connection.id] = '请先选择样本文件'
    return
  }
  try {
    const result = (
      await testBankConnection(
        base,
        session.token.value,
        session.user.value.tenant_id,
        connection.id,
        testFile,
      )
    ).data
    testResults.value[connection.id] =
      `${result.status === 'success' ? '连接测试成功' : '连接测试失败'}：${result.message}（${result.duration_ms}ms）`
    await load()
  } catch (cause) {
    testResults.value[connection.id] = cause instanceof Error ? cause.message : '连接测试请求失败'
  }
}
function edit(account: BankAccount) {
  editingId.value = account.id
  accountForm.value = {
    bankCode: account.bank_code,
    bankName: account.bank_name,
    accountName: account.account_name,
    accountNo: '',
    currency: account.currency,
    currentBalance: account.current_balance,
  }
  accountMessage.value = '编辑时请输入完整账号，页面只展示后四位。'
}
function reset() {
  editingId.value = null
  accountForm.value = {
    bankCode: '',
    bankName: '',
    accountName: '',
    accountNo: '',
    currency: 'CNY',
    currentBalance: '0',
  }
  accountMessage.value = ''
}
async function save() {
  if (!session.user.value || !session.token.value) return
  try {
    if (editingId.value)
      await updateBankAccount(
        base,
        session.token.value,
        session.user.value.tenant_id,
        editingId.value,
        accountForm.value,
      )
    else
      await createBankAccount(
        base,
        session.token.value,
        session.user.value.tenant_id,
        accountForm.value,
      )
    accountMessage.value = editingId.value ? '账户已更新。' : '账户已新增。'
    reset()
    await load()
  } catch (cause) {
    accountMessage.value = cause instanceof Error ? cause.message : '账户保存失败'
  }
}
async function close(account: BankAccount) {
  if (
    !session.user.value ||
    !session.token.value ||
    account.status === 'closed' ||
    !window.confirm(`确认将 ${account.bank_name} · ${account.account_name} 标记为已销户？`)
  )
    return
  try {
    await closeBankAccount(base, session.token.value, session.user.value.tenant_id, account.id)
    accountMessage.value = '账户已标记为已销户。'
    await load()
  } catch (cause) {
    accountMessage.value = cause instanceof Error ? cause.message : '账户销户失败'
  }
}
async function idleScan() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await scanIdleAccounts(base, session.token.value, session.user.value.tenant_id)
    accountMessage.value = `盘点完成，更新 ${result.data.updated_accounts} 个账户。`
    await load()
  } catch (cause) {
    accountMessage.value = cause instanceof Error ? cause.message : '账户盘点失败'
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
        <p class="eyebrow">银行账户</p>
        <h2>账户盘点与流水导入</h2>
        <p class="lead">维护账户、识别闲置账户，并从账户入口导入 CSV/XLSX 银行流水。</p>
      </div>
      <span class="meta">共 {{ total }} 个账户</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="accountMessage || importMessage" class="feedback-text">
      {{ accountMessage || importMessage }}
    </p>
    <section class="grid transaction-layout">
      <article class="panel workflow-panel">
        <h3>导入银行流水</h3>
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
          ><RouterLink class="text-button" :to="`/imports/${preview.job_id}`">任务详情</RouterLink>
        </div>
        <template v-if="preview?.failed_rows"
          ><label
            >失败行修正 JSON<textarea
              v-model="retryJson"
              rows="5"
              :placeholder="retryPayload(preview.preview_rows)"
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
          :disabled="importLoading || preview.success_rows === 0"
          type="button"
          @click="confirm"
        >
          确认入账
        </button>
        <p class="meta import-hint">预览阶段不写入正式流水，确认后才入账。</p>
      </article>
      <article class="panel import-panel">
        <div class="section-heading">
          <h3>{{ editingId ? '编辑银行账户' : '新增银行账户' }}</h3>
          <button v-if="editingId" class="ghost-button" type="button" @click="reset">
            取消编辑
          </button>
        </div>
        <label>银行代码<input v-model.trim="accountForm.bankCode" placeholder="例如 CMB" /></label
        ><label>银行名称<input v-model.trim="accountForm.bankName" /></label
        ><label>账户名称<input v-model.trim="accountForm.accountName" /></label
        ><label>完整账号<input v-model.trim="accountForm.accountNo" inputmode="numeric" /></label
        ><label>币种<input v-model.trim="accountForm.currency" /></label
        ><label
          >当前余额<input v-model="accountForm.currentBalance" type="number" step="0.01" /></label
        ><button v-permission="'account:manage'" class="primary-button" type="button" @click="save">
          {{ editingId ? '保存账户' : '新增账户' }}
        </button>
        <p class="meta import-hint">账号只在保存时提交，页面仅显示后四位。</p>
      </article>
    </section>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>账户盘点</h3>
          <span class="meta">服务端分页</span>
        </div>
        <button
          v-permission="'account:scan'"
          class="primary-button"
          type="button"
          @click="idleScan"
        >
          盘点闲置账户
        </button>
      </div>
      <div v-if="accounts.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>银行/账户</th>
              <th>后四位</th>
              <th>余额</th>
              <th>状态</th>
              <th>最近动账</th>
              <th>闲置级别</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.id">
              <td>{{ account.bank_name }}<br />{{ account.account_name }}</td>
              <td>{{ account.account_no_last4 }}</td>
              <td>{{ formatCurrency(account.current_balance) }}</td>
              <td>
                <span class="pill">{{ account.status }}</span>
              </td>
              <td>{{ account.last_transaction_at || '暂无流水' }}</td>
              <td>
                <span class="pill">{{ account.idle_level || 'normal' }}</span>
              </td>
              <td>
                <div class="action-group">
                  <button
                    class="text-button"
                    type="button"
                    @click="router.push(`/accounts/${account.id}`)"
                  >
                    详情</button
                  ><button
                    v-permission="'account:manage'"
                    class="text-button"
                    type="button"
                    @click="edit(account)"
                  >
                    编辑</button
                  ><button
                    v-if="account.status !== 'closed'"
                    v-permission="'account:manage'"
                    class="small-danger-button"
                    type="button"
                    @click="close(account)"
                  >
                    标记销户
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无银行账户。</p>
      <div class="pagination-controls">
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 条</span
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
          :disabled="page >= totalPages"
          type="button"
          @click="nextPage"
        >
          下一页
        </button>
      </div>
    </article>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>银行接入配置</h3>
          <span class="meta">文件导入型接入；连接测试用样本文件 dry-run 模板识别，不入库</span>
        </div>
      </div>
      <div class="filter-bar">
        <label
          >银行编码<input v-model.trim="connectionForm.bank_code" placeholder="如 BOC"
        /></label>
        <label
          >银行名称<input v-model.trim="connectionForm.bank_name" placeholder="如 中国银行"
        /></label>
        <label
          >关联账户<select v-model="connectionForm.bank_account_id">
            <option value="">不关联</option>
            <option v-for="account in accounts" :key="account.id" :value="String(account.id)">
              {{ account.bank_name }} · {{ account.account_no_last4 }}
            </option>
          </select></label
        >
        <button
          v-permission="'account:manage'"
          class="small-primary-button"
          type="button"
          @click="createConnection"
        >
          新建接入配置
        </button>
      </div>
      <p v-if="connectionMessage" class="feedback-text">{{ connectionMessage }}</p>
      <div v-if="connections.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>银行</th>
              <th>类型</th>
              <th>关联账户</th>
              <th>最近测试</th>
              <th>样本测试</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="connection in connections" :key="connection.id">
              <td>
                {{ connection.bank_code }}<br /><span class="meta">{{ connection.bank_name }}</span>
              </td>
              <td>{{ connection.connection_type }}</td>
              <td>{{ connection.account_name || '-' }}</td>
              <td>
                <span v-if="connection.last_test_status" class="pill">{{
                  connection.last_test_status
                }}</span
                ><br /><span class="meta">{{ connection.last_test_message || '尚未测试' }}</span>
              </td>
              <td>
                <label class="text-button"
                  >选择样本<input
                    accept=".csv,.xlsx"
                    type="file"
                    style="display: none"
                    @change="chooseTestFile(connection.id, $event)"
                /></label>
                <span v-if="testFiles[connection.id]" class="meta">{{
                  testFiles[connection.id]?.name
                }}</span>
                <button
                  v-permission="'account:manage'"
                  class="text-button"
                  type="button"
                  @click="testConnection(connection)"
                >
                  连接测试
                </button>
                <p v-if="testResults[connection.id]" class="meta">
                  {{ testResults[connection.id] }}
                </p>
              </td>
              <td>
                <button
                  v-permission="'account:manage'"
                  class="text-button"
                  type="button"
                  @click="removeConnection(connection)"
                >
                  删除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无接入配置，请先新建文件导入型接入。</p>
    </article>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>银行模板字典</h3>
          <span class="meta">六家支持银行的表头映射规则（只读，来自解析器实际规则）</span>
        </div>
      </div>
      <div class="mini-list">
        <div v-for="template in templates" :key="template.bank_code" class="mini-row">
          <strong>{{ template.bank_name }}（{{ template.bank_code }}）</strong>
          <span class="meta">{{ template.format }} · {{ template.recognition }}</span>
          <button class="text-button" type="button" @click="selectedTemplate = template">
            字段映射
          </button>
        </div>
      </div>
      <div v-if="selectedTemplate" class="detail-section">
        <h3>{{ selectedTemplate.bank_name }} 字段映射</h3>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>银行表头</th>
                <th>标准字段</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="mapping in selectedTemplate.field_mappings" :key="mapping.header">
                <td>{{ mapping.header }}</td>
                <td>{{ mapping.field }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <button class="ghost-button" type="button" @click="selectedTemplate = null">收起</button>
      </div>
    </article>
  </section>
</template>
