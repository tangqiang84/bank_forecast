<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ExceptionDetail from './ExceptionDetail.vue'
import {
  loadCounterpartyAliases,
  loadMatchResultDetail,
  type ExceptionCase,
} from '../services/receivables'
import { apiBase, useSession } from '../session'
import { confidenceLevelLabel } from '../utils/labels'

// P4-12 统一详情抽屉：按财务核对动线组织（钱是谁打的 → 应该对哪笔 → 操作 → 处理留痕）
const props = defineProps<{ item: ExceptionCase; hasPrev: boolean; hasNext: boolean }>()
const emit = defineEmits<{
  prev: []
  next: []
  action: [
    name: 'confirm' | 'reject' | 'assign' | 'comment' | 'resolve' | 'close' | 'false_positive',
    item: ExceptionCase,
  ]
}>()

const session = useSession()
const matchDetail = ref<Record<string, unknown> | null>(null)
const matchError = ref('')
const aliases = ref<Array<{ alias: string; canonical_name: string }> | null>(null)

const summary = computed(() => props.item.match_summary ?? null)
const isReview = computed(() => summary.value?.match_status === 'suggested')
const isOpen = computed(() => !['closed', 'false_positive'].includes(props.item.status))

const allocations = computed(() => {
  const list = matchDetail.value?.allocations
  return Array.isArray(list) ? (list as Array<Record<string, unknown>>) : []
})

const aliasHit = computed(() => {
  const counterparty = summary.value?.counterparty_name || props.item.source_counterparty || ''
  if (!counterparty || !aliases.value) return false
  return aliases.value.some(
    (entry) => entry.alias === counterparty || entry.canonical_name === counterparty,
  )
})

function formatAmount(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  const num = Number(value)
  if (Number.isNaN(num)) return String(value)
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}

async function loadMatchDetail() {
  matchDetail.value = null
  matchError.value = ''
  const resultId = summary.value?.result_id
  if (!resultId || !session.user.value || !session.token.value) return
  try {
    matchDetail.value = (
      await loadMatchResultDetail(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        resultId,
      )
    ).data
  } catch (cause) {
    matchError.value = cause instanceof Error ? cause.message : '匹配详情加载失败'
  }
}

async function loadAliases() {
  if (aliases.value !== null || !session.user.value || !session.token.value) return
  try {
    aliases.value = (
      await loadCounterpartyAliases(apiBase(), session.token.value, session.user.value.tenant_id)
    ).data
  } catch {
    aliases.value = []
  }
}

onMounted(() => {
  loadMatchDetail()
  loadAliases()
})
watch(() => props.item.id, loadMatchDetail)
</script>

<template>
  <div class="workbench-detail">
    <div class="drawer-nav">
      <button class="ghost-button" :disabled="!hasPrev" type="button" @click="emit('prev')">
        上一笔</button
      ><button class="ghost-button" :disabled="!hasNext" type="button" @click="emit('next')">
        下一笔
      </button>
    </div>
    <section class="detail-section">
      <h3>① 钱是谁打的</h3>
      <p v-if="summary">
        流水号 {{ summary.txn_no }} · 付款方 {{ summary.counterparty_name || '-' }}
        <span v-if="aliasHit" class="pill">常用对手方</span>
        · 金额 {{ formatAmount(summary.amount) }}
      </p>
      <p v-else class="meta">{{ item.source_counterparty || item.title }}</p>
    </section>
    <section v-if="summary" class="detail-section">
      <h3>② 应该对哪笔</h3>
      <p>
        建议合同 {{ summary.contract_no || '-' }} / 节点 {{ summary.node_name || '-' }} · 建议金额
        {{ formatAmount(summary.amount) }} · 建议依据 {{ confidenceLevelLabel(summary.confidence) }}
      </p>
      <p v-if="matchError" class="error-banner">{{ matchError }}</p>
      <div v-if="allocations.length" class="mini-list">
        <div v-for="allocation in allocations" :key="String(allocation.id)" class="mini-row">
          <span>{{ allocation.contract_no }} / {{ allocation.node_name }}</span>
          <span class="meta">{{ formatAmount(allocation.allocated_amount) }}</span>
        </div>
      </div>
    </section>
    <section class="detail-section">
      <h3>③ 操作</h3>
      <div class="action-group">
        <template v-if="isReview">
          <button
            v-permission="'matching:confirm'"
            class="small-primary-button"
            type="button"
            @click="emit('action', 'confirm', item)"
          >
            确认核销</button
          ><button
            v-permission="'matching:confirm'"
            class="text-button"
            type="button"
            @click="emit('action', 'reject', item)"
          >
            打回
          </button>
        </template>
        <template v-else>
          <button
            v-if="!item.owner_user_id && isOpen"
            v-permission="'exception:assign'"
            class="small-primary-button"
            type="button"
            @click="emit('action', 'assign', item)"
          >
            认领给我</button
          ><button
            v-if="isOpen"
            v-permission="'exception:handle'"
            class="text-button"
            type="button"
            @click="emit('action', 'comment', item)"
          >
            备注</button
          ><button
            v-if="['new', 'in_progress'].includes(item.status)"
            v-permission="'exception:handle'"
            class="small-primary-button"
            type="button"
            @click="emit('action', 'resolve', item)"
          >
            处理完成</button
          ><button
            v-if="item.status === 'resolved'"
            v-permission="'exception:handle'"
            class="small-danger-button"
            type="button"
            @click="emit('action', 'close', item)"
          >
            关闭</button
          ><button
            v-if="isOpen"
            v-permission="'exception:handle'"
            class="text-button"
            type="button"
            @click="emit('action', 'false_positive', item)"
          >
            误报
          </button>
        </template>
      </div>
    </section>
    <section class="detail-section">
      <h3>④ 处理留痕</h3>
      <ExceptionDetail :id="item.id" :key="item.id" />
    </section>
  </div>
</template>
