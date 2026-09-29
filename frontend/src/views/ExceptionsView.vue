<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
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

const router = useRouter()
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
async function action(
  item: ExceptionCase,
  name: 'assign' | 'comment' | 'resolve' | 'close' | 'false_positive',
) {
  if (!session.user.value || !session.token.value) return
  const text =
    name === 'assign'
      ? ''
      : window.prompt('请输入处理说明', name === 'close' ? '已完成异常闭环' : '人工处理记录')
  if (name !== 'assign' && !text?.trim()) return
  try {
    if (name === 'assign')
      await assignException(base, session.token.value, session.user.value.tenant_id, item.id)
    if (name === 'comment')
      await commentException(
        base,
        session.token.value,
        session.user.value.tenant_id,
        item.id,
        text!.trim(),
      )
    if (name === 'resolve')
      await resolveException(
        base,
        session.token.value,
        session.user.value.tenant_id,
        item.id,
        text!.trim(),
      )
    if (name === 'close')
      await closeException(
        base,
        session.token.value,
        session.user.value.tenant_id,
        item.id,
        text!.trim(),
      )
    if (name === 'false_positive')
      await markFalsePositive(
        base,
        session.token.value,
        session.user.value.tenant_id,
        item.id,
        text!.trim(),
      )
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
      <div class="action-group">
        <button
          v-permission="'matching:run'"
          class="primary-button"
          :disabled="loading"
          type="button"
          @click="matching"
        >
          运行回款匹配</button
        ><button
          v-permission="'exception:handle'"
          class="small-danger-button"
          :disabled="!selected.length"
          type="button"
          @click="batch('false_positive')"
        >
          批量标记误报</button
        ><button
          v-permission="'exception:assign'"
          class="small-primary-button"
          :disabled="!selected.length"
          type="button"
          @click="batch('assign')"
        >
          批量分派
        </button>
      </div>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>
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
              {{ item.exception_no }} · {{ item.exception_type }} · {{ item.severity }}
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
            <span class="pill">{{ item.stage || item.status }}</span>
            <div class="action-group">
              <button
                class="text-button"
                type="button"
                @click="router.push(`/exceptions/${item.id}`)"
              >
                详情</button
              ><button
                v-if="
                  !item.owner_user_id &&
                  item.status !== 'closed' &&
                  item.status !== 'false_positive'
                "
                v-permission="'exception:assign'"
                class="small-primary-button"
                type="button"
                @click="action(item, 'assign')"
              >
                分派给我</button
              ><button
                v-if="item.status !== 'closed' && item.status !== 'false_positive'"
                v-permission="'exception:handle'"
                class="text-button"
                type="button"
                @click="action(item, 'comment')"
              >
                备注</button
              ><button
                v-if="['new', 'in_progress'].includes(item.status)"
                v-permission="'exception:handle'"
                class="small-primary-button"
                type="button"
                @click="action(item, 'resolve')"
              >
                处理完成</button
              ><button
                v-if="item.status === 'resolved'"
                v-permission="'exception:handle'"
                class="small-danger-button"
                type="button"
                @click="action(item, 'close')"
              >
                关闭</button
              ><button
                v-if="item.status !== 'closed' && item.status !== 'false_positive'"
                v-permission="'exception:handle'"
                class="small-danger-button"
                type="button"
                @click="action(item, 'false_positive')"
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
  </section>
</template>
