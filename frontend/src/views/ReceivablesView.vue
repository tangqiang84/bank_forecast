<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  importContracts,
  loadReceivables,
  runMatching,
  type Receivable,
} from '../services/receivables'
import { apiBase, useSession } from '../session'
import { formatCurrency } from '../utils/number'

const router = useRouter()
const session = useSession()
const base = apiBase()
const rows = ref<Receivable[]>([])
const file = ref<File | null>(null)
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
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const response = await loadReceivables(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
    )
    rows.value = response.data.items
    total.value = response.data.total
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '应收计划加载失败'
  } finally {
    loading.value = false
  }
}
function choose(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  message.value = ''
}
async function importFile() {
  if (!session.user.value || !session.token.value || !file.value) {
    message.value = '请选择合同或应收计划 CSV 文件'
    return
  }
  try {
    const result = await importContracts(
      base,
      session.token.value,
      session.user.value.tenant_id,
      file.value,
    )
    message.value = `导入完成：成功 ${result.data.success_rows ?? 0} 行，失败 ${result.data.failed_rows ?? 0} 行，跳过 ${result.data.skipped_rows ?? 0} 行。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '合同应收导入失败'
  }
}
async function matching() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await runMatching(base, session.token.value, session.user.value.tenant_id)
    message.value = `匹配完成：匹配 ${result.data.matched ?? 0}，待确认 ${result.data.suggested ?? 0}，未知收款 ${result.data.unknown ?? 0}。`
    await load()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '回款匹配失败'
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
        <p class="eyebrow">合同应收</p>
        <h2>合同回款计划</h2>
        <p class="lead">导入合同主数据或独立应收计划，核对节点后运行回款匹配。</p>
      </div>
      <span class="meta">共 {{ total }} 个节点</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message" class="feedback-text">{{ message }}</p>
    <section class="grid receivable-layout">
      <article class="panel workflow-panel">
        <h3>导入合同 / 应收计划</h3>
        <label>CSV 文件<input accept=".csv,text/csv" type="file" @change="choose" /></label>
        <p v-if="file" class="meta">已选择：{{ file.name }}</p>
        <button
          v-permission="'contract:import'"
          class="primary-button"
          :disabled="loading"
          type="button"
          @click="importFile"
        >
          导入合同应收
        </button>
        <p class="meta import-hint">
          支持合同主数据、合同应收计划和独立应收计划模板；导入结果会返回成功、失败、跳过数量。
        </p>
        <button v-permission="'matching:run'" class="ghost-button" type="button" @click="matching">
          运行回款匹配
        </button>
      </article>
      <article class="panel">
        <h3>业务口径</h3>
        <div class="detail-section">
          <p>未到期、按期足额、逾期未收、部分收款和超额收款由系统根据应收日期及已收金额计算。</p>
          <p class="meta">低置信度结果进入匹配结果页人工确认，未知收款进入异常事项页处理。</p>
        </div>
      </article>
    </section>
    <article class="panel table-panel">
      <div class="section-heading">
        <div>
          <h3>应收计划</h3>
          <span class="meta">服务端分页</span>
        </div>
        <span v-if="loading" class="meta">加载中...</span>
      </div>
      <div v-if="rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>合同</th>
              <th>客户</th>
              <th>节点</th>
              <th>应收日期</th>
              <th>计划金额</th>
              <th>已收金额</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in rows" :key="item.id">
              <td>
                {{ item.contract_no }}<br /><span class="meta">{{ item.contract_name }}</span>
              </td>
              <td>{{ item.customer_name }}</td>
              <td>{{ item.node_name }}</td>
              <td>{{ item.due_date }}</td>
              <td>{{ formatCurrency(item.plan_amount) }}</td>
              <td>{{ formatCurrency(item.paid_amount) }}</td>
              <td>
                <span class="pill">{{ item.status }}</span>
              </td>
              <td>
                <button
                  class="text-button"
                  type="button"
                  @click="router.push(`/contracts/${item.contract_id}`)"
                >
                  查看合同
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无应收计划，请先导入合同 CSV。</p>
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
