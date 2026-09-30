<script setup lang="ts">
import { onMounted, ref } from 'vue'
import MatchDetail from '../components/MatchDetail.vue'
import ModalPanel from '../components/ModalPanel.vue'
import {
  confirmMatchResult,
  loadMatchResults,
  rejectMatchResult,
  runMatching,
  type MatchResult,
} from '../services/receivables'
import { apiBase, useSession } from '../session'
import {
  allocationModeLabel,
  confidenceLevelLabel,
  matchStatusLabel,
  matchTypeLabel,
} from '../utils/labels'
import { formatCurrency } from '../utils/number'

const session = useSession()
const base = apiBase()
const rows = ref<MatchResult[]>([])
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
const actionId = ref<number | null>(null)
const matchDetailId = ref<number | null>(null)
const pendingAction = ref<{ type: 'confirm' | 'reject'; id: number } | null>(null)
const rejectReason = ref('')

function openMatch(id: number) {
  matchDetailId.value = id
}

function closeMatch() {
  matchDetailId.value = null
}

function askConfirm(id: number) {
  pendingAction.value = { type: 'confirm', id }
}

function askReject(id: number) {
  rejectReason.value = '人工复核后拒绝'
  pendingAction.value = { type: 'reject', id }
}

function cancelAction() {
  pendingAction.value = null
}
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const result = await loadMatchResults(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
    )
    rows.value = result.data.items
    total.value = result.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '匹配结果加载失败'
  } finally {
    loading.value = false
  }
}
async function run() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await runMatching(base, session.token.value, session.user.value.tenant_id)
    message.value = `匹配完成：精确 ${result.data.matched ?? 0}，待确认 ${result.data.suggested ?? 0}，未知收款 ${result.data.unknown ?? 0}。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '匹配运行失败'
  }
}
async function confirm(id: number) {
  if (!session.user.value || !session.token.value) return
  actionId.value = id
  try {
    await confirmMatchResult(base, session.token.value, session.user.value.tenant_id, id)
    message.value = '匹配组已确认，应收和流水状态已更新。'
    pendingAction.value = null
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认匹配失败'
  } finally {
    actionId.value = null
  }
}
async function reject(id: number, reason: string) {
  if (!session.user.value || !session.token.value) return
  actionId.value = id
  try {
    await rejectMatchResult(base, session.token.value, session.user.value.tenant_id, id, reason)
    message.value = '匹配组已拒绝。'
    pendingAction.value = null
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '拒绝匹配失败'
  } finally {
    actionId.value = null
  }
}
async function executeAction() {
  const pending = pendingAction.value
  if (!pending) return
  if (pending.type === 'confirm') {
    await confirm(pending.id)
  } else {
    if (!rejectReason.value.trim()) {
      message.value = '请输入拒绝原因'
      return
    }
    await reject(pending.id, rejectReason.value.trim())
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
        <p class="eyebrow">匹配结果</p>
        <h2>回款匹配工作台</h2>
        <p class="lead">
          查看自动匹配依据、拆分/合并分配明细，并对中低置信度结果进行人工确认或拒绝。
        </p>
      </div>
      <button
        v-permission="'matching:run'"
        class="primary-button"
        :disabled="loading"
        type="button"
        @click="run"
      >
        运行回款匹配
      </button>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>匹配结果</h3>
          <span class="meta">共 {{ total }} 条；待确认结果按匹配组处理</span>
        </div>
        <span v-if="loading" class="meta">加载中...</span>
      </div>
      <div v-if="rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>流水</th>
              <th>流水金额</th>
              <th>分配金额</th>
              <th>合同/节点</th>
              <th>匹配模式</th>
              <th>置信度</th>
              <th>匹配理由</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td>{{ item.transaction_no }}</td>
              <td class="income-amount">{{ formatCurrency(item.amount) }}</td>
              <td>
                {{ formatCurrency(item.allocated_amount) }}<br /><span class="meta"
                  >{{ item.allocation_count }} 明细 /
                  {{ formatCurrency(item.allocation_total) }}</span
                >
              </td>
              <td>
                {{ item.contract_no || '-' }}<br /><span class="meta"
                  >{{ item.contract_name || '-' }} · {{ item.node_name || '-' }}</span
                >
              </td>
              <td>
                {{ allocationModeLabel(item.allocation_mode) }}<br /><span class="meta"
                  >{{ matchTypeLabel(item.match_type) }} · {{ item.match_group_id }}</span
                >
              </td>
              <td>{{ confidenceLevelLabel(item.confidence_level) }}</td>
              <td class="reason-cell">{{ item.match_reason }}</td>
              <td>
                <span class="pill">{{ matchStatusLabel(item.match_status) }}</span>
              </td>
              <td>
                <div class="action-group">
                  <button class="text-button" type="button" @click="openMatch(item.id)">详情</button
                  ><button
                    v-if="item.match_status === 'suggested'"
                    v-permission="'matching:confirm'"
                    class="small-primary-button"
                    :disabled="actionId !== null"
                    type="button"
                    @click="askConfirm(item.id)"
                  >
                    确认组</button
                  ><button
                    v-if="item.match_status === 'suggested'"
                    v-permission="'matching:confirm'"
                    class="small-danger-button"
                    :disabled="actionId !== null"
                    type="button"
                    @click="askReject(item.id)"
                  >
                    拒绝组
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无匹配结果，请先运行回款匹配。</p>
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
      v-if="pendingAction"
      :title="pendingAction.type === 'confirm' ? '确认匹配组' : '拒绝匹配组'"
      @close="cancelAction"
    >
      <p v-if="pendingAction.type === 'confirm'">
        确认后应收计划和流水状态将更新为已匹配，是否继续？
      </p>
      <template v-else>
        <p>拒绝后该匹配组将不再参与自动核销，可在异常事项中跟进。</p>
        <label>拒绝原因<input v-model.trim="rejectReason" placeholder="请输入拒绝原因" /></label>
      </template>
      <template #footer>
        <button class="ghost-button" type="button" @click="cancelAction">取消</button>
        <button
          v-if="pendingAction.type === 'confirm'"
          class="primary-button"
          :disabled="actionId !== null"
          type="button"
          @click="executeAction"
        >
          确认
        </button>
        <button
          v-else
          class="small-danger-button"
          :disabled="actionId !== null || !rejectReason.trim()"
          type="button"
          @click="executeAction"
        >
          确认
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="matchDetailId !== null" title="匹配结果详情" @close="closeMatch">
      <MatchDetail :id="matchDetailId" />
      <template #footer>
        <button class="primary-button" type="button" @click="closeMatch">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
