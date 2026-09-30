<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ExceptionDetail from '../components/ExceptionDetail.vue'
import ModalPanel from '../components/ModalPanel.vue'
import {
  assignException,
  batchExceptionAction,
  closeException,
  commentException,
  loadExceptions,
  loadExceptionStats,
  markFalsePositive,
  resolveException,
  runMatching,
  uploadExceptionAttachment,
  type ExceptionCase,
  type ExceptionStats,
} from '../services/receivables'
import { apiBase, useSession } from '../session'
import { exceptionStatusLabel, exceptionTypeLabel, severityLabel } from '../utils/labels'

const session = useSession()
const base = apiBase()
const rows = ref<ExceptionCase[]>([])
const stats = ref<ExceptionStats | null>(null)
const queue = ref('')
const ownerFilter = ref('')
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
  name: 'comment' | 'resolve' | 'false_positive'
} | null>(null)
const actionText = ref('')
const actionDone = ref(false)

const actionTitles = { comment: '备注', resolve: '处理完成', false_positive: '标记误报' } as const

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

function askAssign(item: ExceptionCase) {
  assignTarget.value = item
}

function cancelAssign() {
  assignTarget.value = null
}

function askTextAction(item: ExceptionCase, name: 'comment' | 'resolve' | 'false_positive') {
  actionTarget.value = { item, name }
  actionText.value = '人工处理记录'
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
    const result = await loadExceptions(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
      { queue: queue.value, owner_user_id: ownerFilter.value },
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
function applyQueue() {
  page.value = 1
  load()
}
async function matching() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await runMatching(base, session.token.value, session.user.value.tenant_id)
    message.value = `匹配完成：新增匹配 ${result.data.matched ?? 0}，待确认 ${result.data.suggested ?? 0}。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '匹配运行失败'
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
    message.value = '异常事项操作已完成。'
    actionDone.value = true
    await load()
    await loadStats()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '异常事项操作失败'
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
      <div class="summary-grid compact-summary">
        <div>
          <span>我的待办</span><strong>{{ stats.my_todo_count }}</strong>
        </div>
        <div>
          <span>待分派</span><strong>{{ stats.unassigned_count }}</strong>
        </div>
        <div>
          <span>待关闭</span><strong>{{ stats.pending_close_count }}</strong>
        </div>
        <div>
          <span>超期未处理</span><strong>{{ stats.overdue_count }}</strong>
        </div>
        <div>
          <span>闭环率</span><strong>{{ (Number(stats.closure_rate) * 100).toFixed(1) }}%</strong>
        </div>
        <div>
          <span>平均处理时长</span><strong>{{ stats.avg_resolution_hours }}h</strong>
        </div>
      </div>
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
        v-permission="'matching:run'"
        class="ghost-button"
        :disabled="loading"
        type="button"
        @click="matching"
      >
        运行回款匹配</button
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
        <div class="filter-bar">
          <label
            >队列<select v-model="queue" @change="applyQueue">
              <option value="">进行中</option>
              <option value="mine">我的待办</option>
              <option value="unassigned">待分派</option>
              <option value="pending_close">待关闭</option>
              <option value="closed">已关闭/误报</option>
            </select></label
          >
          <label
            >责任人<select v-model="ownerFilter" @change="applyQueue">
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
      </div>
      <div v-if="rows.length" class="list-stack">
        <div v-for="item in rows" :key="item.id" class="exception-row">
          <div>
            <input v-model="selected" type="checkbox" :value="item.id" /><strong>{{
              item.title
            }}</strong>
            <p class="meta">
              {{ item.exception_no }} · {{ exceptionTypeLabel(item.exception_type) }} ·
              {{ severityLabel(item.severity) }}
            </p>
            <p class="meta">{{ item.description }}</p>
            <p class="meta">
              责任人：{{
                item.owner_name || (item.owner_user_id ? `用户 ${item.owner_user_id}` : '未分派')
              }}
              · 截止：{{ item.due_date || '-' }}
            </p>
          </div>
          <div class="exception-actions">
            <span class="pill">{{ item.stage || exceptionStatusLabel(item.status) }}</span>
            <div class="action-group">
              <button class="text-button" type="button" @click="openException(item.id)">详情</button
              ><button
                v-if="
                  !item.owner_user_id &&
                  item.status !== 'closed' &&
                  item.status !== 'false_positive'
                "
                v-permission="'exception:assign'"
                class="small-primary-button"
                type="button"
                @click="askAssign(item)"
              >
                分派给我</button
              ><button
                v-if="item.status !== 'closed' && item.status !== 'false_positive'"
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
                v-if="item.status !== 'closed' && item.status !== 'false_positive'"
                v-permission="'exception:handle'"
                class="small-danger-button"
                type="button"
                @click="askTextAction(item, 'false_positive')"
              >
                标记误报</button
              ><label
                v-if="item.status !== 'closed' && item.status !== 'false_positive'"
                v-permission="'attachment:manage'"
                class="attachment-button"
                >上传附件<input type="file" @change="upload(item, $event)"
              /></label>
            </div>
          </div>
        </div>
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
      v-if="exceptionDetailId !== null"
      title="异常事项详情"
      @close="closeExceptionDetail"
    >
      <ExceptionDetail :id="exceptionDetailId" />
      <template #footer>
        <button class="primary-button" type="button" @click="closeExceptionDetail">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
