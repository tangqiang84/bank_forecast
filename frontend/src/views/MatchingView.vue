<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  confirmMatchResult,
  loadMatchResults,
  rejectMatchResult,
  runMatching,
  type MatchResult,
} from '../services/receivables'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'

const router = useRouter()
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
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认匹配失败'
  } finally {
    actionId.value = null
  }
}
async function reject(id: number) {
  if (!session.user.value || !session.token.value) return
  const reason = window.prompt('请输入拒绝原因', '人工复核后拒绝')
  if (!reason?.trim()) return
  actionId.value = id
  try {
    await rejectMatchResult(
      base,
      session.token.value,
      session.user.value.tenant_id,
      id,
      reason.trim(),
    )
    message.value = '匹配组已拒绝。'
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '拒绝匹配失败'
  } finally {
    actionId.value = null
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
      <button class="primary-button" :disabled="loading" type="button" @click="run">
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
                {{ item.allocation_mode }}<br /><span class="meta"
                  >{{ item.match_type }} · {{ item.match_group_id }}</span
                >
              </td>
              <td>{{ item.confidence_level }}</td>
              <td class="reason-cell">{{ item.match_reason }}</td>
              <td>
                <span class="pill">{{ item.match_status }}</span>
              </td>
              <td>
                <div class="action-group">
                  <button
                    class="text-button"
                    type="button"
                    @click="router.push(`/matching/${item.id}`)"
                  >
                    详情</button
                  ><button
                    v-if="item.match_status === 'suggested'"
                    class="small-primary-button"
                    :disabled="actionId !== null"
                    type="button"
                    @click="confirm(item.id)"
                  >
                    确认组</button
                  ><button
                    v-if="item.match_status === 'suggested'"
                    class="small-danger-button"
                    :disabled="actionId !== null"
                    type="button"
                    @click="reject(item.id)"
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
  </section>
</template>
