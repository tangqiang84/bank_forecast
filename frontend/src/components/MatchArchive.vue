<script setup lang="ts">
import { onMounted, ref } from 'vue'
import MatchDetail from './MatchDetail.vue'
import ModalPanel from './ModalPanel.vue'
import {
  createCounterpartyAlias,
  deleteCounterpartyAlias,
  loadCounterpartyAliases,
  loadMatchResults,
  updateCounterpartyAlias,
  type CounterpartyAlias,
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

// P4-13：匹配结果只读归档 + 对手方别名维护；确认/打回已收敛到工作台队列与详情抽屉
const session = useSession()
const base = apiBase()
const rows = ref<MatchResult[]>([])
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const matchDetailId = ref<number | null>(null)
const aliasModalOpen = ref(false)
const aliases = ref<CounterpartyAlias[]>([])
const aliasForm = ref({ alias: '', canonical_name: '' })
const aliasEditingId = ref<number | null>(null)
const aliasMessage = ref('')

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
    error.value = cause instanceof Error ? cause.message : '匹配结果归档加载失败'
  } finally {
    loading.value = false
  }
}

function prevPage() {
  page.value--
  load()
}

function nextPage() {
  page.value++
  load()
}

function changeSize() {
  page.value = 1
  load()
}

function openMatch(id: number) {
  matchDetailId.value = id
}

function closeMatch() {
  matchDetailId.value = null
}

async function openAliases() {
  if (!session.user.value || !session.token.value) return
  aliasMessage.value = ''
  aliasEditingId.value = null
  aliasForm.value = { alias: '', canonical_name: '' }
  aliasModalOpen.value = true
  try {
    aliases.value = (
      await loadCounterpartyAliases(base, session.token.value, session.user.value.tenant_id)
    ).data
  } catch (cause) {
    aliasMessage.value = cause instanceof Error ? cause.message : '对手方别名加载失败'
  }
}

function closeAliases() {
  aliasModalOpen.value = false
}

async function saveAlias() {
  if (!session.user.value || !session.token.value) return
  const payload = {
    alias: aliasForm.value.alias.trim(),
    canonical_name: aliasForm.value.canonical_name.trim(),
  }
  if (!payload.alias || !payload.canonical_name) {
    aliasMessage.value = '请填写别名和标准名称'
    return
  }
  try {
    if (aliasEditingId.value !== null) {
      await updateCounterpartyAlias(
        base,
        session.token.value,
        session.user.value.tenant_id,
        aliasEditingId.value,
        payload,
      )
      aliasMessage.value = '别名已更新。'
    } else {
      await createCounterpartyAlias(
        base,
        session.token.value,
        session.user.value.tenant_id,
        payload,
      )
      aliasMessage.value = '别名已新增。'
    }
    aliasEditingId.value = null
    aliasForm.value = { alias: '', canonical_name: '' }
    aliases.value = (
      await loadCounterpartyAliases(base, session.token.value, session.user.value.tenant_id)
    ).data
  } catch (cause) {
    aliasMessage.value = cause instanceof Error ? cause.message : '别名保存失败'
  }
}

function editAlias(item: CounterpartyAlias) {
  aliasEditingId.value = item.id
  aliasForm.value = { alias: item.alias, canonical_name: item.canonical_name }
}

function cancelAliasEdit() {
  aliasEditingId.value = null
  aliasForm.value = { alias: '', canonical_name: '' }
}

async function removeAlias(item: CounterpartyAlias) {
  if (!session.user.value || !session.token.value) return
  if (!window.confirm(`确认删除别名「${item.alias}」？`)) return
  try {
    await deleteCounterpartyAlias(base, session.token.value, session.user.value.tenant_id, item.id)
    aliasMessage.value = `别名「${item.alias}」已删除。`
    aliases.value = (
      await loadCounterpartyAliases(base, session.token.value, session.user.value.tenant_id)
    ).data
  } catch (cause) {
    aliasMessage.value = cause instanceof Error ? cause.message : '别名删除失败'
  }
}

onMounted(load)
</script>

<template>
  <article class="panel table-panel">
    <div class="section-heading">
      <div>
        <h3>匹配结果归档</h3>
        <span class="meta"
          >共 {{ total }} 条；只读归档（含已核销/已打回），确认与打回请在上方队列处理</span
        >
      </div>
      <div class="filter-bar">
        <button class="ghost-button" type="button" @click="openAliases">对手方别名</button>
        <span v-if="loading" class="meta">加载中...</span>
      </div>
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
              <button class="text-button" type="button" @click="openMatch(item.id)">详情</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="empty-state">暂无匹配结果归档。</p>
    <div class="pagination-controls">
      <span
        >第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页，共 {{ total }} 条</span
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

    <ModalPanel v-if="aliasModalOpen" title="对手方别名" @close="closeAliases">
      <p class="meta import-hint">
        别名按归一化匹配流水对手方名称到标准名称，匹配时优先使用标准名称对齐合同客户。
      </p>
      <div v-if="aliases.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>别名</th>
              <th>标准名称</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in aliases" :key="item.id">
              <td>{{ item.alias }}</td>
              <td>{{ item.canonical_name }}</td>
              <td>{{ item.updated_at }}</td>
              <td>
                <div class="action-group">
                  <button
                    v-permission="'contract:import'"
                    class="text-button"
                    type="button"
                    @click="editAlias(item)"
                  >
                    编辑</button
                  ><button
                    v-permission="'contract:import'"
                    class="text-button"
                    type="button"
                    @click="removeAlias(item)"
                  >
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无对手方别名。</p>
      <label>别名<input v-model.trim="aliasForm.alias" placeholder="流水中的对手方写法" /></label
      ><label
        >标准名称<input v-model.trim="aliasForm.canonical_name" placeholder="合同客户标准名称"
      /></label>
      <div class="action-group">
        <button
          v-permission="'contract:import'"
          class="small-primary-button"
          type="button"
          @click="saveAlias"
        >
          {{ aliasEditingId !== null ? '保存别名' : '新增别名' }}
        </button>
        <button
          v-if="aliasEditingId !== null"
          class="ghost-button"
          type="button"
          @click="cancelAliasEdit"
        >
          取消编辑
        </button>
      </div>
      <p v-if="aliasMessage" class="feedback-text">{{ aliasMessage }}</p>
      <template #footer>
        <button class="ghost-button" type="button" @click="closeAliases">关闭</button>
      </template>
    </ModalPanel>
    <ModalPanel v-if="matchDetailId !== null" title="匹配结果详情" @close="closeMatch">
      <MatchDetail :id="matchDetailId" />
      <template #footer>
        <button class="primary-button" type="button" @click="closeMatch">关闭</button>
      </template>
    </ModalPanel>
  </article>
</template>
