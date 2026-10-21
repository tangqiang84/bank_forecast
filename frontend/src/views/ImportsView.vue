<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ImportPreviewTable from '../components/ImportPreviewTable.vue'
import { loadAccounts, previewStatements, type BankAccount } from '../services/bank'
import { previewFinanceRecords } from '../services/finance'
import {
  confirmImportJob,
  loadImportQualitySummary,
  retryImportJobErrors,
  type ImportJobType,
  type ImportQualitySummary,
} from '../services/imports'
import { previewPayables } from '../services/payables'
import { previewProjects } from '../services/projects'
import { previewContracts } from '../services/receivables'
import { previewReceipts } from '../services/receipts'
import { apiBase, useSession } from '../session'
import { rateText, type WizardPreview } from '../utils/importPreview'
import { hasPermission } from '../utils/permission'

type ImportCard = {
  type: ImportJobType
  title: string
  description: string
  permission: string
  accept: string
  fileLabel: string
}

const SPREADSHEET_ACCEPT =
  '.csv,.xls,.xlsx,text/csv,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const CSV_ACCEPT = '.csv,text/csv'

// 统一导入向导（docs/菜单流程化重设计方案.md §4-2，P6-3）：六类导入一个入口；
// 卡片权限点与各业务页导入弹层按钮一致，无权卡片不渲染。原页面导入弹层保留，双轨并存。
const IMPORT_CARDS: ImportCard[] = [
  {
    type: 'bank_statement',
    title: '银行流水',
    description: '上传流水 CSV / Excel，自动识别银行模板。',
    permission: 'transaction:import',
    accept: SPREADSHEET_ACCEPT,
    fileLabel: 'CSV / Excel 文件',
  },
  {
    type: 'receipt',
    title: '电子回单',
    description: '上传回单 CSV，可按流水号关联已入账流水。',
    permission: 'receipt:import',
    accept: CSV_ACCEPT,
    fileLabel: 'CSV 文件',
  },
  {
    type: 'contract',
    title: '合同与应收计划',
    description: '上传合同与应收计划 CSV。',
    permission: 'contract:import',
    accept: CSV_ACCEPT,
    fileLabel: 'CSV 文件',
  },
  {
    type: 'payable',
    title: '应付计划',
    description: '上传供应商应付计划 CSV。',
    permission: 'contract:import',
    accept: CSV_ACCEPT,
    fileLabel: 'CSV 文件',
  },
  {
    type: 'project',
    title: '项目',
    description: '上传项目清单 CSV。',
    permission: 'project:import',
    accept: CSV_ACCEPT,
    fileLabel: 'CSV 文件',
  },
  {
    type: 'finance_record',
    title: '财务记录',
    description: '上传财务记录 CSV，用于财务对账。',
    permission: 'reconciliation:run',
    accept: CSV_ACCEPT,
    fileLabel: 'CSV 文件',
  },
]

const session = useSession()
const base = apiBase()

const quality = ref<ImportQualitySummary | null>(null)
const qualityError = ref('')

type WizardStep = 'select' | 'upload' | 'preview' | 'done'
const step = ref<WizardStep>('select')
const activeCard = ref<ImportCard | null>(null)
const accounts = ref<BankAccount[]>([])
const accountsError = ref('')
const accountId = ref<number | null>(null)
const file = ref<File | null>(null)
const preview = ref<WizardPreview | null>(null)
const includeDuplicates = ref(false)
const retryJson = ref('')
const busy = ref(false)
const message = ref('')

const visibleCards = computed(() =>
  IMPORT_CARDS.filter((card) => hasPermission(session.user.value, card.permission)),
)
const needsAccount = computed(() => activeCard.value?.type === 'bank_statement')
const confirmLabel = computed(() =>
  activeCard.value?.type === 'bank_statement' ? '确认入账' : '确认导入',
)
const duplicateSuspectRows = computed(() =>
  preview.value && activeCard.value?.type === 'bank_statement'
    ? preview.value.preview_rows
        .filter((row) => row.status === 'duplicate_suspect')
        .map((row) => row.row_no)
    : [],
)
const actionable = computed(
  () =>
    preview.value !== null && ['preview_pending', 'preview_failed'].includes(preview.value.status),
)
const canPreview = computed(
  () => !busy.value && file.value !== null && (!needsAccount.value || accountId.value !== null),
)
const canViewImportJob = computed(() => hasPermission(session.user.value, 'import:view'))

async function loadQuality() {
  if (!session.user.value || !session.token.value) return
  qualityError.value = ''
  try {
    quality.value = (
      await loadImportQualitySummary(base, session.token.value, session.user.value.tenant_id, 30)
    ).data
  } catch (cause) {
    qualityError.value = cause instanceof Error ? cause.message : '导入质量汇总加载失败'
  }
}

async function loadAccountOptions() {
  if (!session.user.value || !session.token.value) return
  accountsError.value = ''
  try {
    accounts.value = (
      await loadAccounts(base, session.token.value, session.user.value.tenant_id)
    ).data.items
  } catch (cause) {
    accountsError.value = cause instanceof Error ? cause.message : '银行账户加载失败'
  }
}

function selectCard(card: ImportCard) {
  activeCard.value = card
  file.value = null
  accountId.value = null
  preview.value = null
  retryJson.value = ''
  includeDuplicates.value = false
  message.value = ''
  step.value = 'upload'
  if (card.type === 'bank_statement' && !accounts.value.length) void loadAccountOptions()
}

function backToSelect() {
  step.value = 'select'
  activeCard.value = null
  file.value = null
  accountId.value = null
  preview.value = null
  message.value = ''
}

function backToUpload() {
  step.value = 'upload'
  preview.value = null
  retryJson.value = ''
  includeDuplicates.value = false
  message.value = ''
}

function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  message.value = ''
  preview.value = null
  includeDuplicates.value = false
}

async function startPreview() {
  if (!session.user.value || !session.token.value || !activeCard.value) return
  const card = activeCard.value
  const currentFile = file.value
  if (card.type === 'bank_statement' && accountId.value === null) {
    message.value = '请选择银行账户'
    return
  }
  if (!currentFile) {
    message.value = '请选择要导入的文件'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const token = session.token.value
    const tenantId = session.user.value.tenant_id
    let result: WizardPreview
    if (card.type === 'bank_statement') {
      const selectedAccountId = accountId.value
      if (selectedAccountId === null) throw new Error('请选择银行账户')
      result = (await previewStatements(base, token, tenantId, selectedAccountId, currentFile)).data
    } else if (card.type === 'contract') {
      result = (await previewContracts(base, token, tenantId, currentFile)).data
    } else if (card.type === 'payable') {
      result = (await previewPayables(base, token, tenantId, currentFile)).data
    } else if (card.type === 'project') {
      result = (await previewProjects(base, token, tenantId, currentFile)).data
    } else if (card.type === 'receipt') {
      result = (await previewReceipts(base, token, tenantId, currentFile)).data
    } else if (card.type === 'finance_record') {
      result = (await previewFinanceRecords(base, token, tenantId, currentFile)).data
    } else {
      throw new Error('不支持的导入类型')
    }
    preview.value = result
    message.value =
      result.status === 'preview_failed'
        ? '预览校验失败，请修正失败行后重新校验。'
        : card.type === 'bank_statement'
          ? '预览完成，请核对后确认入账。'
          : '预览完成，请核对后确认导入。'
    step.value = 'preview'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '预览失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

async function retryFailedRows() {
  if (!session.user.value || !session.token.value || !preview.value) return
  busy.value = true
  try {
    preview.value = (
      await retryImportJobErrors(
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
    message.value = cause instanceof Error ? cause.message : '失败行 JSON 不正确'
  } finally {
    busy.value = false
  }
}

async function confirmPreview() {
  if (!session.user.value || !session.token.value || !preview.value) return
  busy.value = true
  try {
    const duplicates =
      includeDuplicates.value && duplicateSuspectRows.value.length
        ? duplicateSuspectRows.value
        : undefined
    preview.value = (
      await confirmImportJob(
        base,
        session.token.value,
        session.user.value.tenant_id,
        preview.value.job_id,
        duplicates,
      )
    ).data
    includeDuplicates.value = false
    step.value = 'done'
    void loadQuality()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认失败，请稍后重试'
  } finally {
    busy.value = false
  }
}

onMounted(loadQuality)
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">数据接入</p>
        <h2>导入向导</h2>
        <p class="lead">
          选择数据类型，上传文件，预览核对后确认入账；各业务页面内的导入入口仍然保留。
        </p>
      </div>
    </header>

    <article class="panel">
      <div class="section-heading">
        <div>
          <h3>近 30 天导入质量</h3>
          <span class="meta">按创建时间统计全部导入任务</span>
        </div>
      </div>
      <div v-if="quality" class="import-summary">
        <span>任务数 {{ quality.job_count }}</span
        ><span>成功率 {{ rateText(quality.success_rate) }}</span
        ><span>疑似重复率 {{ rateText(quality.duplicate_suspect_rate) }}</span
        ><span>失败行数 {{ quality.failed_rows }}</span>
      </div>
      <div v-else-if="qualityError">
        <p class="error-banner">{{ qualityError }}</p>
        <button class="ghost-button" type="button" @click="loadQuality">重试</button>
      </div>
      <p v-else class="empty-state">导入质量汇总加载中...</p>
    </article>

    <article v-if="step === 'select'" class="panel">
      <div class="section-heading">
        <div>
          <h3>选择导入类型</h3>
          <span class="meta">六类数据一个入口，按权限展示可用类型</span>
        </div>
      </div>
      <div v-if="visibleCards.length" class="import-type-grid">
        <button
          v-for="card in visibleCards"
          :key="card.type"
          class="module-card import-type-card"
          type="button"
          @click="selectCard(card)"
        >
          <strong>{{ card.title }}</strong>
          <span class="meta">{{ card.description }}</span>
        </button>
      </div>
      <p v-else class="empty-state">没有可用的导入权限，请联系管理员</p>
    </article>

    <article v-else-if="step === 'upload' && activeCard" class="panel">
      <div class="section-heading">
        <div>
          <h3>上传{{ activeCard.title }}文件</h3>
          <span class="meta">{{ activeCard.description }}</span>
        </div>
      </div>
      <template v-if="needsAccount && accountsError">
        <p class="error-banner">{{ accountsError }}</p>
        <button class="ghost-button" type="button" @click="loadAccountOptions">重试</button>
      </template>
      <div class="filter-bar">
        <label v-if="needsAccount"
          >银行账户<select v-model="accountId">
            <option :value="null" disabled>请选择银行账户</option>
            <option v-for="account in accounts" :key="account.id" :value="account.id">
              {{ account.bank_name }} · {{ account.account_name }} · {{ account.account_no_last4 }}
            </option>
          </select></label
        ><label
          >{{ activeCard.fileLabel
          }}<input :accept="activeCard.accept" type="file" @change="chooseFile"
        /></label>
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <div class="action-group">
        <button class="ghost-button" type="button" :disabled="busy" @click="backToSelect">
          上一步</button
        ><button
          v-permission="activeCard.permission"
          class="primary-button"
          type="button"
          :disabled="!canPreview"
          @click="startPreview"
        >
          {{ busy ? '处理中...' : '开始预览' }}
        </button>
      </div>
    </article>

    <article v-else-if="step === 'preview' && preview && activeCard" class="panel">
      <div class="section-heading">
        <div>
          <h3>预览{{ activeCard.title }}</h3>
          <span class="meta">核对预览行后可修正失败行重新校验，再确认入账</span>
        </div>
      </div>
      <div class="import-summary">
        <strong>任务 #{{ preview.job_id }}</strong
        ><span>有效 {{ preview.success_rows }}</span
        ><span>失败 {{ preview.failed_rows }}</span
        ><span>跳过 {{ preview.skipped_rows }}</span
        ><span v-if="preview.duplicate_suspected_rows"
          >疑似重复 {{ preview.duplicate_suspected_rows }}</span
        >
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <ImportPreviewTable :job-type="activeCard.type" :rows="preview.preview_rows" />
      <section v-if="actionable && preview.failed_rows" class="detail-section">
        <h3>失败行重试</h3>
        <label
          >失败行修正 JSON<textarea v-model="retryJson" rows="6" placeholder="请输入 JSON 数组" />
        </label>
        <button
          v-permission="activeCard.permission"
          class="ghost-button"
          type="button"
          :disabled="busy"
          @click="retryFailedRows"
        >
          重新校验
        </button>
      </section>
      <div class="action-group">
        <label v-if="actionable && duplicateSuspectRows.length" class="checkbox-label"
          ><input v-model="includeDuplicates" type="checkbox" /> 同时导入
          {{ duplicateSuspectRows.length }} 条疑似重复流水</label
        ><button class="ghost-button" type="button" :disabled="busy" @click="backToUpload">
          上一步</button
        ><button
          v-if="actionable"
          v-permission="activeCard.permission"
          class="primary-button"
          type="button"
          :disabled="busy || (preview.success_rows === 0 && !includeDuplicates)"
          @click="confirmPreview"
        >
          {{ confirmLabel }}</button
        ><span v-if="busy" class="meta">处理中...</span>
      </div>
    </article>

    <article v-else-if="step === 'done' && preview && activeCard" class="panel">
      <div class="section-heading">
        <div>
          <h3>导入完成</h3>
          <span class="meta">{{ activeCard.title }}已确认，可在导入任务详情中查看行级结果。</span>
        </div>
      </div>
      <div class="import-summary">
        <strong>任务 #{{ preview.job_id }}</strong
        ><span>入账 {{ preview.success_rows }}</span
        ><span>跳过 {{ preview.skipped_rows }}</span
        ><span>失败 {{ preview.failed_rows }}</span>
      </div>
      <div class="action-group">
        <RouterLink v-if="canViewImportJob" class="text-button" :to="`/imports/${preview.job_id}`"
          >查看导入任务详情 →</RouterLink
        ><button class="primary-button" type="button" @click="backToSelect">
          继续导入其他数据
        </button>
      </div>
    </article>
  </section>
</template>
