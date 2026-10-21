<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import WorkbenchDetail from '../components/WorkbenchDetail.vue'
import MatchArchive from '../components/MatchArchive.vue'
import ModalPanel from '../components/ModalPanel.vue'
import {
  assignException,
  batchExceptionAction,
  closeException,
  commentException,
  confirmMatchResult,
  loadExceptions,
  loadExceptionStats,
  markFalsePositive,
  rejectMatchResult,
  resolveException,
  runMatching,
  uploadExceptionAttachment,
  type ExceptionCase,
  type ExceptionStats,
} from '../services/receivables'
import { apiBase, useSession } from '../session'
import { confidenceLevelLabel, exceptionStatusLabel, exceptionTypeLabel } from '../utils/labels'

const session = useSession()
const base = apiBase()
const rows = ref<ExceptionCase[]>([])
const stats = ref<ExceptionStats | null>(null)
type WorkbenchView = 'mine' | 'unassigned' | 'pending_close' | 'all'
const view = ref<WorkbenchView>('mine')
const viewTabs: Array<{ value: WorkbenchView; label: string }> = [
  { value: 'mine', label: '我的待办' },
  { value: 'unassigned', label: '待分派' },
  { value: 'pending_close', label: '待复核' },
  { value: 'all', label: '全部记录' },
]
const ownerFilter = ref('')
const typeFilter = ref('')
const amountMin = ref('')
const amountMax = ref('')
const createdFrom = ref('')
const createdTo = ref('')
const runSummary = ref('')
const selected = ref<number[]>([])
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
const error = ref('')
const message = ref('')
const exceptionDetailId = ref<number | null>(null)
const assignTarget = ref<ExceptionCase | null>(null)
const actionTarget = ref<{
  item: ExceptionCase
  name: 'comment' | 'resolve' | 'false_positive' | 'reject'
} | null>(null)
const actionText = ref('')
const actionDone = ref(false)
const flashId = ref<number | null>(null)

const actionTitles = {
  comment: '备注',
  resolve: '处理完成',
  false_positive: '标记误报',
  reject: '打回',
} as const

const anyModalOpen = computed(
  () =>
    exceptionDetailId.value !== null || assignTarget.value !== null || actionTarget.value !== null,
)

function openException(id: number) {
  exceptionDetailId.value = id
}

function closeExceptionDetail() {
  exceptionDetailId.value = null
}

// 抽屉导航与处理完自动跳下一笔
const detailIndex = computed(() =>
  rows.value.findIndex((row) => row.id === exceptionDetailId.value),
)
const detailItem = computed(() => (detailIndex.value >= 0 ? rows.value[detailIndex.value] : null))
let advanceFrom: { id: number; index: number } | null = null

function prevDetail() {
  if (detailIndex.value > 0) exceptionDetailId.value = rows.value[detailIndex.value - 1].id
}

function nextDetail() {
  if (detailIndex.value >= 0 && detailIndex.value < rows.value.length - 1) {
    exceptionDetailId.value = rows.value[detailIndex.value + 1].id
  }
}

type DrawerAction =
  'confirm' | 'reject' | 'assign' | 'comment' | 'resolve' | 'close' | 'false_positive'

function onDrawerAction(name: DrawerAction, item: ExceptionCase) {
  advanceFrom = { id: item.id, index: detailIndex.value }
  if (name === 'confirm') confirmCarrier(item)
  if (name === 'reject') askTextAction(item, 'reject')
  if (name === 'assign') askAssign(item)
  if (name === 'comment') askTextAction(item, 'comment')
  if (name === 'resolve') askTextAction(item, 'resolve')
  if (name === 'close') close(item)
  if (name === 'false_positive') askTextAction(item, 'false_positive')
}

// 抽屉发起的动作完成后，若当前行已从队列消失则自动跳到同位置的下一笔
function maybeAdvanceDetail() {
  if (!advanceFrom) return
  const { id, index } = advanceFrom
  advanceFrom = null
  if (exceptionDetailId.value !== id) return
  if (rows.value.some((row) => row.id === id)) return
  const next = rows.value[Math.min(Math.max(index, 0), rows.value.length - 1)]
  exceptionDetailId.value = next ? next.id : null
}

function askAssign(item: ExceptionCase) {
  assignTarget.value = item
}

function cancelAssign() {
  assignTarget.value = null
}

function askTextAction(
  item: ExceptionCase,
  name: 'comment' | 'resolve' | 'false_positive' | 'reject',
) {
  actionTarget.value = { item, name }
  actionText.value = name === 'reject' ? '' : '人工处理记录'
  actionDone.value = false
  message.value = ''
}

function cancelTextAction() {
  actionTarget.value = null
}
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const filters: Record<string, string> = {
      queue: view.value === 'all' ? '' : view.value,
      owner_user_id: ownerFilter.value,
      exception_type: typeFilter.value,
      amount_min: amountMin.value,
      amount_max: amountMax.value,
      created_from: createdFrom.value,
      created_to: createdTo.value,
    }
    if (view.value === 'all') filters.active_only = 'false'
    const result = await loadExceptions(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
      filters,
    )
    rows.value = result.data.items
    total.value = result.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '异常事项加载失败'
  } finally {
    loading.value = false
  }
}
async function loadStats() {
  if (!session.user.value || !session.token.value) return
  try {
    stats.value = (
      await loadExceptionStats(base, session.token.value, session.user.value.tenant_id)
    ).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '异常统计加载失败'
  }
}
const typeOptions = computed(() => (stats.value?.by_type ?? []).map((item) => item.exception_type))

function switchView(next: WorkbenchView) {
  view.value = next
  page.value = 1
  selected.value = []
  load()
}
function applyFilters() {
  page.value = 1
  load()
}
function selectType(type: string) {
  typeFilter.value = type
  page.value = 1
  load()
}
async function matching() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await runMatching(base, session.token.value, session.user.value.tenant_id)
    runSummary.value = `匹配完成：自动核销 ${result.data.auto_matched_count ?? result.data.matched ?? 0} 笔，待人工 ${result.data.pending_review_count ?? result.data.suggested ?? 0} 笔。`
    await load()
    await loadStats()
  } catch (cause) {
    runSummary.value = cause instanceof Error ? cause.message : '匹配运行失败'
  }
}
async function confirmAssign() {
  if (!session.user.value || !session.token.value || !assignTarget.value) return
  const target = assignTarget.value
  try {
    await assignException(base, session.token.value, session.user.value.tenant_id, target.id)
    message.value = '异常事项操作已完成。'
    assignTarget.value = null
    await load()
    await loadStats()
    maybeAdvanceDetail()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '异常事项操作失败'
  }
}
async function submitTextAction() {
  if (!session.user.value || !session.token.value || !actionTarget.value) return
  const text = actionText.value.trim()
  if (!text) {
    message.value = '请输入处理说明'
    return
  }
  const { item, name } = actionTarget.value
  try {
    if (name === 'comment')
      await commentException(base, session.token.value, session.user.value.tenant_id, item.id, text)
    if (name === 'resolve')
      await resolveException(base, session.token.value, session.user.value.tenant_id, item.id, text)
    if (name === 'false_positive')
      await markFalsePositive(
        base,
        session.token.value,
        session.user.value.tenant_id,
        item.id,
        text,
      )
    if (name === 'reject') {
      if (!item.match_summary?.result_id) {
        message.value = '该工单无关联匹配组，无法打回'
        return
      }
      await rejectMatchResult(
        base,
        session.token.value,
        session.user.value.tenant_id,
        item.match_summary.result_id,
        text,
      )
    }
    message.value = name === 'reject' ? '已打回，工单转入待跟进。' : '异常事项操作已完成。'
    actionDone.value = true
    await load()
    await loadStats()
    maybeAdvanceDetail()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '异常事项操作失败'
  }
}
function isOpen(item: ExceptionCase) {
  return item.status !== 'closed' && item.status !== 'false_positive'
}

function isReviewRow(item: ExceptionCase) {
  return !!item.match_summary && item.match_summary.match_status === 'suggested'
}

function formatAmount(value: string | null | undefined) {
  if (value === null || value === undefined || value === '') return '-'
  const num = Number(value)
  if (Number.isNaN(num)) return value
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}

function suggestionText(item: ExceptionCase) {
  const summary = item.match_summary
  if (!summary) return '-'
  const parts = [summary.contract_no, summary.node_name].filter(Boolean)
  return parts.length ? parts.join(' / ') : '-'
}

function overdueText(item: ExceptionCase) {
  if (!item.due_date) return '-'
  const days = Math.floor(
    (Date.parse(`${item.due_date}T00:00:00`) -
      Date.parse(new Date().toISOString().slice(0, 10) + 'T00:00:00')) /
      86400000,
  )
  if (days < 0 && isOpen(item)) return `超期 ${-days} 天`
  if (days >= 0 && isOpen(item)) return `剩余 ${days} 天`
  return '-'
}

// 处理完自动高亮下一笔（行从队列消失后聚焦同位置行）
async function afterRowAction(item: ExceptionCase) {
  const index = rows.value.findIndex((row) => row.id === item.id)
  await load()
  await loadStats()
  const next = rows.value[Math.min(Math.max(index, 0), rows.value.length - 1)]
  flashId.value = next && next.id !== item.id ? next.id : null
  if (flashId.value !== null) {
    window.setTimeout(() => (flashId.value = null), 1500)
  }
}

async function confirmCarrier(item: ExceptionCase) {
  if (!session.user.value || !session.token.value || !item.match_summary?.result_id) return
  try {
    await confirmMatchResult(
      base,
      session.token.value,
      session.user.value.tenant_id,
      item.match_summary.result_id,
    )
    message.value = '已确认核销。'
    await afterRowAction(item)
    maybeAdvanceDetail()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认核销失败'
  }
}
async function close(item: ExceptionCase) {
  if (!session.user.value || !session.token.value) return
  const text = window.prompt('请输入处理说明', '已完成异常闭环')
  if (!text?.trim()) return
  try {
    await closeException(base, session.token.value, session.user.value.tenant_id, item.id, text)
    message.value = '异常事项操作已完成。'
    await load()
    await loadStats()
    maybeAdvanceDetail()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '异常事项操作失败'
  }
}
async function batch(actionName: string) {
  if (!session.user.value || !session.token.value || !selected.value.length) return
  const text =
    actionName === 'false_positive' ? window.prompt('请输入批量误报原因', '人工复核后标记误报') : ''
  if (actionName === 'false_positive' && !text?.trim()) return
  try {
    const result = await batchExceptionAction(
      base,
      session.token.value,
      session.user.value.tenant_id,
      selected.value,
      actionName,
      text ?? '批量处理',
    )
    message.value = `已批量处理 ${result.data.updated} 条异常。`
    selected.value = []
    await load()
    await loadStats()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '批量操作失败'
  }
}
async function upload(item: ExceptionCase, event: Event) {
  if (!session.user.value || !session.token.value) return
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    await uploadExceptionAttachment(
      base,
      session.token.value,
      session.user.value.tenant_id,
      item.id,
      file,
    )
    message.value = '附件已上传。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '附件上传失败'
  }
}
function changeSize() {
  page.value = 1
  load()
}
onMounted(() => {
  load()
  loadStats()
  window.addEventListener('workspace-refresh', load)
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">异常事项</p>
        <h2>异常处理工作台</h2>
        <p class="lead">
          按责任人和状态推进应收未收、未知收款、财务差异及账户闲置异常，保留备注、附件和闭环记录。
        </p>
      </div>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message && !anyModalOpen" class="feedback-text">{{ message }}</p>
    <article v-if="stats" class="panel">
      <div class="section-heading">
        <div>
          <h3>今日概览</h3>
          <span class="meta"
            >大额待办口径：进行中且工单金额 ≥ {{ stats.large_amount_threshold ?? 100000 }}</span
          >
        </div>
        <div class="filter-bar">
          <button
            v-permission="'matching:run'"
            class="primary-button"
            :disabled="loading"
            type="button"
            @click="matching"
          >
            运行回款匹配
          </button>
        </div>
      </div>
      <div class="summary-grid compact-summary">
        <div>
          <span>今日新增</span><strong>{{ stats.today_new_count ?? 0 }}</strong>
        </div>
        <div>
          <span>我的待办</span><strong>{{ stats.my_todo_count }}</strong>
        </div>
        <div>
          <span>待分派</span><strong>{{ stats.unassigned_count }}</strong>
        </div>
        <div>
          <span>待复核</span
          ><strong>{{ stats.review_pending_count ?? stats.pending_close_count }}</strong>
        </div>
        <div>
          <span>超期未处理</span><strong>{{ stats.overdue_count }}</strong>
        </div>
        <div>
          <span>大额待办</span><strong>{{ stats.large_amount_count ?? 0 }}</strong>
        </div>
        <div>
          <span>闭环率</span><strong>{{ (Number(stats.closure_rate) * 100).toFixed(1) }}%</strong>
        </div>
        <div>
          <span>平均处理时长</span><strong>{{ stats.avg_resolution_hours }}h</strong>
        </div>
      </div>
      <p v-if="runSummary" class="feedback-text">{{ runSummary }}</p>
      <div v-if="stats.by_owner.length" class="detail-section">
        <h3>责任人视图</h3>
        <div class="mini-list">
          <div v-for="owner in stats.by_owner" :key="owner.owner_user_id" class="mini-row">
            <span>{{ owner.owner_name || `用户 ${owner.owner_user_id}` }}</span>
            <span class="meta"
              >待处理 {{ owner.active }} · 已处理 {{ owner.resolved_closed }} · 超期
              {{ owner.overdue }}</span
            >
          </div>
        </div>
      </div>
    </article>
    <div class="page-toolbar">
      <button
        v-permission="'matching:confirm'"
        class="ghost-button"
        :disabled="!selected.length"
        type="button"
        @click="batch('confirm_match')"
      >
        批量确认核销</button
      ><button
        v-permission="'exception:handle'"
        class="ghost-button"
        :disabled="!selected.length"
        type="button"
        @click="batch('false_positive')"
      >
        批量标记误报</button
      ><button
        v-permission="'exception:assign'"
        class="ghost-button"
        :disabled="!selected.length"
        type="button"
        @click="batch('assign')"
      >
        批量分派
      </button>
    </div>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>异常清单</h3>
          <span class="meta">共 {{ total }} 条，支持分派、备注、处理、关闭和附件</span>
        </div>
        <div class="view-tabs" role="tablist" aria-label="待办视图">
          <button
            v-for="tab in viewTabs"
            :key="tab.value"
            class="view-tab"
            :class="{ active: view === tab.value }"
            role="tab"
            :aria-selected="view === tab.value"
            type="button"
            @click="switchView(tab.value)"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
      <div class="type-chips">
        <button
          class="chip"
          :class="{ active: typeFilter === '' }"
          type="button"
          @click="selectType('')"
        >
          全部类型</button
        ><button
          v-for="type in typeOptions"
          :key="type"
          class="chip"
          :class="{ active: typeFilter === type }"
          type="button"
          @click="selectType(type)"
        >
          {{ exceptionTypeLabel(type) }}
        </button>
      </div>
      <div class="filter-bar">
        <label
          >金额下限<input v-model="amountMin" type="number" min="0" @change="applyFilters" /></label
        ><label
          >金额上限<input v-model="amountMax" type="number" min="0" @change="applyFilters" /></label
        ><label>创建起始<input v-model="createdFrom" type="date" @change="applyFilters" /></label
        ><label>创建截止<input v-model="createdTo" type="date" @change="applyFilters" /></label
        ><label
          >责任人<select v-model="ownerFilter" @change="applyFilters">
            <option value="">全部</option>
            <option
              v-for="owner in stats?.by_owner ?? []"
              :key="owner.owner_user_id"
              :value="String(owner.owner_user_id)"
            >
              {{ owner.owner_name || `用户 ${owner.owner_user_id}` }}
            </option>
          </select></label
        >
      </div>
      <div v-if="rows.length" class="dense-table-wrap">
        <table class="dense-table">
          <thead>
            <tr>
              <th></th>
              <th class="amount-cell">金额</th>
              <th>付款方户名</th>
              <th>流水摘要</th>
              <th>建议合同/节点</th>
              <th>建议依据</th>
              <th>类型</th>
              <th>账龄/超期</th>
              <th>责任人</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id" :class="{ flash: flashId === item.id }">
              <td><input v-model="selected" type="checkbox" :value="item.id" /></td>
              <td class="amount-cell">{{ formatAmount(item.biz_amount) }}</td>
              <td>
                {{ item.match_summary?.counterparty_name || item.source_counterparty || '-' }}
              </td>
              <td class="summary-cell" :title="item.description">
                {{ item.match_summary?.summary || item.source_summary || item.title }}
              </td>
              <td>{{ suggestionText(item) }}</td>
              <td>
                {{ item.match_summary ? confidenceLevelLabel(item.match_summary.confidence) : '-' }}
              </td>
              <td>
                {{ exceptionTypeLabel(item.exception_type) }}
                <p class="meta">{{ item.exception_no }}</p>
              </td>
              <td>{{ overdueText(item) }}</td>
              <td>
                {{
                  item.owner_name || (item.owner_user_id ? `用户 ${item.owner_user_id}` : '未分派')
                }}
              </td>
              <td>
                <span class="pill">{{ item.stage || exceptionStatusLabel(item.status) }}</span>
              </td>
              <td class="action-cell">
                <template v-if="isReviewRow(item)">
                  <button
                    v-permission="'matching:confirm'"
                    class="small-primary-button"
                    type="button"
                    @click="confirmCarrier(item)"
                  >
                    确认核销</button
                  ><button
                    v-permission="'matching:confirm'"
                    class="text-button"
                    type="button"
                    @click="askTextAction(item, 'reject')"
                  >
                    打回
                  </button>
                </template>
                <template v-else>
                  <button
                    v-if="!item.owner_user_id && isOpen(item)"
                    v-permission="'exception:assign'"
                    class="small-primary-button"
                    type="button"
                    @click="askAssign(item)"
                  >
                    认领给我</button
                  ><button
                    v-if="isOpen(item)"
                    v-permission="'exception:handle'"
                    class="text-button"
                    type="button"
                    @click="askTextAction(item, 'comment')"
                  >
                    备注</button
                  ><button
                    v-if="['new', 'in_progress'].includes(item.status)"
                    v-permission="'exception:handle'"
                    class="small-primary-button"
                    type="button"
                    @click="askTextAction(item, 'resolve')"
                  >
                    处理完成</button
                  ><button
                    v-if="item.status === 'resolved'"
                    v-permission="'exception:handle'"
                    class="small-danger-button"
                    type="button"
                    @click="close(item)"
                  >
                    关闭</button
                  ><button
                    v-if="isOpen(item)"
                    v-permission="'exception:handle'"
                    class="text-button"
                    type="button"
                    @click="askTextAction(item, 'false_positive')"
                  >
                    误报
                  </button>
                </template>
                <button class="text-button" type="button" @click="openException(item.id)">
                  详情
                </button>
                <label
                  v-if="isOpen(item)"
                  v-permission="'attachment:manage'"
                  class="attachment-button"
                  >附件<input type="file" @change="upload(item, $event)"
                /></label>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无异常事项。运行回款匹配或财务对账后查看异常。</p>
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

    <MatchArchive v-if="view === 'all'" />

    <ModalPanel
      v-if="assignTarget"
      :title="`分派给我：${assignTarget.exception_no}`"
      @close="cancelAssign"
    >
      <p>确认将该异常事项分派给自己处理？</p>
      <template #footer>
        <button class="ghost-button" type="button" @click="cancelAssign">取消</button>
        <button class="primary-button" type="button" @click="confirmAssign">确认</button>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="actionTarget"
      :title="`${actionTitles[actionTarget.name]}：${actionTarget.item.exception_no}`"
      @close="cancelTextAction"
    >
      <label>处理说明<textarea v-model="actionText" rows="3" placeholder="请输入处理说明" /></label>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <template v-if="actionDone">
          <button class="primary-button" type="button" @click="cancelTextAction">关闭</button>
        </template>
        <template v-else>
          <button class="ghost-button" type="button" @click="cancelTextAction">取消</button>
          <button class="primary-button" type="button" @click="submitTextAction">确认</button>
        </template>
      </template>
    </ModalPanel>

    <ModalPanel
      v-if="exceptionDetailId !== null && detailItem"
      :title="`这笔款怎么回事（${detailItem.exception_no}）`"
      @close="closeExceptionDetail"
    >
      <WorkbenchDetail
        :item="detailItem"
        :has-prev="detailIndex > 0"
        :has-next="detailIndex >= 0 && detailIndex < rows.length - 1"
        @prev="prevDetail"
        @next="nextDetail"
        @action="onDrawerAction"
      />
      <template #footer>
        <button class="primary-button" type="button" @click="closeExceptionDetail">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
