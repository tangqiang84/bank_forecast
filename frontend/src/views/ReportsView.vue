<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ModalPanel from '../components/ModalPanel.vue'
import ReportPreview from '../components/ReportPreview.vue'
import {
  createReport,
  downloadReport,
  loadReportAudits,
  loadReportDetail,
  loadReports,
  printReport,
  type ReportAuditLog,
  type ReportTask,
} from '../services/reports'
import { apiBase, useSession } from '../session'

const session = useSession()
const base = apiBase()
const rows = ref<ReportTask[]>([])
const selected = ref<ReportTask | null>(null)
const audits = ref<ReportAuditLog[]>([])
const latest = ref<Record<string, ReportTask | null>>({ weekly: null, daily: null, health: null })
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
const reportType = ref('monthly')
const reportMonth = ref(new Date().toISOString().slice(0, 7))
const reportDate = ref(new Date().toISOString().slice(0, 10))
const reportWeek = ref(new Date().toISOString().slice(0, 10))
const loading = ref(false)
const detailLoading = ref(false)
const message = ref('')
const error = ref('')
const generateModalOpen = ref(false)
const generateDone = ref(false)
const tasksModalOpen = ref(false)

const latestTypes = [
  { type: 'weekly', label: '最新周报' },
  { type: 'daily', label: '最新日报' },
  { type: 'health', label: '最新资金体检' },
]

const anyModalOpen = computed(
  () => generateModalOpen.value || tasksModalOpen.value || selected.value !== null,
)

function openGenerateModal() {
  message.value = ''
  generateDone.value = false
  generateModalOpen.value = true
}

function closeGenerateModal() {
  generateModalOpen.value = false
}

function openTasksModal() {
  tasksModalOpen.value = true
}

function closeTasksModal() {
  tasksModalOpen.value = false
}

function closeDetail() {
  selected.value = null
}
async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const result = await loadReports(
      base,
      session.token.value,
      session.user.value.tenant_id,
      page.value,
      pageSize.value,
    )
    rows.value = result.data.items
    total.value = result.data.total
    if (selected.value) {
      const current = rows.value.find((item) => item.id === selected.value?.id)
      if (current) await select(current)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '报表任务加载失败'
  } finally {
    loading.value = false
  }
}
async function loadLatest() {
  if (!session.user.value || !session.token.value) return
  try {
    const result = await loadReports(
      base,
      session.token.value,
      session.user.value.tenant_id,
      1,
      100,
    )
    for (const { type } of latestTypes) {
      const task = result.data.items.find(
        (item) => item.report_type === type && item.status === 'success',
      )
      if (!task) {
        latest.value[type] = null
        continue
      }
      latest.value[type] = task.result
        ? task
        : (await loadReportDetail(base, session.token.value, session.user.value.tenant_id, task.id))
            .data
    }
  } catch {
    latest.value = { weekly: null, daily: null, health: null }
  }
}
async function select(report: ReportTask) {
  if (!session.user.value || !session.token.value) return
  selected.value = report
  detailLoading.value = true
  try {
    const [detail, audit] = await Promise.all([
      loadReportDetail(base, session.token.value, session.user.value.tenant_id, report.id),
      loadReportAudits(base, session.token.value, session.user.value.tenant_id, report.id),
    ])
    selected.value = detail.data
    audits.value = audit.data.items
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '报表详情加载失败'
  } finally {
    detailLoading.value = false
  }
}
async function generate() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    const params =
      reportType.value === 'monthly'
        ? { month: reportMonth.value }
        : reportType.value === 'daily'
          ? { date_from: reportDate.value, date_to: reportDate.value }
          : reportType.value === 'weekly'
            ? { week: reportWeek.value }
            : {}
    await createReport(
      base,
      session.token.value,
      session.user.value.tenant_id,
      reportType.value,
      params,
    )
    message.value = '报表已生成。'
    generateDone.value = true
    await load()
    await loadLatest()
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '报表生成失败'
  } finally {
    loading.value = false
  }
}
async function download(report: ReportTask, format: 'csv' | 'xlsx' = 'csv') {
  if (!session.user.value || !session.token.value) return
  try {
    const blob = await downloadReport(
      base,
      session.token.value,
      session.user.value.tenant_id,
      report.id,
      format,
    )
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download =
      format === 'xlsx'
        ? (report.file_name || `report-${report.id}`).replace(/\.csv$/, '') + '.xlsx'
        : report.file_name || `report-${report.id}.csv`
    link.click()
    URL.revokeObjectURL(link.href)
    message.value = format === 'xlsx' ? '报表 Excel 已导出。' : '报表 CSV 已导出。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '报表导出失败'
  }
}
async function printPreview(report: ReportTask) {
  if (!session.user.value || !session.token.value) return
  try {
    const blob = await printReport(
      base,
      session.token.value,
      session.user.value.tenant_id,
      report.id,
    )
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
    message.value = '打印预览已在新窗口打开，可在浏览器中另存为 PDF。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '打印预览打开失败'
  }
}
function changeSize() {
  page.value = 1
  load()
}
onMounted(() => {
  load()
  loadLatest()
  window.addEventListener('workspace-refresh', load)
})
</script>

<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">报告中心</p>
        <h2>资金经营报告</h2>
        <p class="lead">
          展示最近生成的周报、日报和资金体检报告详情；生成与任务管理通过右上方入口操作。
        </p>
      </div>
      <span class="meta">共 {{ total }} 个报表任务</span>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <p v-if="message && !anyModalOpen" class="feedback-text">{{ message }}</p>
    <div class="page-toolbar">
      <button class="ghost-button" type="button" @click="openGenerateModal">生成报告</button>
      <button class="ghost-button" type="button" @click="openTasksModal">报表任务</button>
    </div>

    <section class="grid">
      <article v-for="item in latestTypes" :key="item.type" class="panel">
        <div class="section-heading">
          <div>
            <h3>{{ item.label }}</h3>
            <span class="meta">{{
              latest[item.type]
                ? `任务 #${latest[item.type]!.id} · ${latest[item.type]!.created_at}`
                : '暂无'
            }}</span>
          </div>
        </div>
        <ReportPreview v-if="latest[item.type]" :report="latest[item.type]!" />
        <p v-else class="empty-state">
          暂无已生成的{{ item.label.slice(2) }}，请先通过「生成报告」创建。
        </p>
      </article>
    </section>

    <ModalPanel v-if="generateModalOpen" title="生成报告" @close="closeGenerateModal">
      <label
        >报告类型<select v-model="reportType">
          <option value="daily">日报</option>
          <option value="weekly">周报</option>
          <option value="monthly">月报</option>
          <option value="health">资金体检报告</option>
        </select></label
      ><label v-if="reportType === 'monthly'"
        >统计月份<input v-model="reportMonth" type="month" /></label
      ><label v-if="reportType === 'daily'"
        >统计日期<input v-model="reportDate" type="date" /></label
      ><label v-if="reportType === 'weekly'"
        >周内任意日期<input v-model="reportWeek" type="date"
      /></label>
      <div class="action-group">
        <button
          v-permission="'report:generate'"
          class="primary-button"
          :disabled="loading"
          type="button"
          @click="generate"
        >
          {{ loading ? '生成中...' : '生成报表' }}
        </button>
      </div>
      <p class="meta import-hint">
        日报展示余额变化、收支和新增异常；周报展示回款、应付、账户活跃度和待办闭环；月报展示账户盘点、合同回款、项目健康和经营风险；资金体检展示健康评分和风险项。所有报表支持
        CSV/Excel 下载和打印预览（浏览器另存 PDF）。
      </p>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <template #footer>
        <button
          v-if="generateDone"
          class="primary-button"
          type="button"
          @click="closeGenerateModal"
        >
          关闭
        </button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="tasksModalOpen" title="报表任务" wide @close="closeTasksModal">
      <div class="section-heading">
        <span class="meta">服务端分页</span>
        <button class="ghost-button" type="button" @click="load">刷新</button>
      </div>
      <div v-if="rows.length" class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>报表类型</th>
              <th>统计范围</th>
              <th>状态</th>
              <th>生成时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="report in rows" :key="report.id">
              <td>
                {{
                  report.report_type === 'health'
                    ? '资金体检'
                    : report.report_type === 'monthly'
                      ? '月报'
                      : report.report_type === 'weekly'
                        ? '周报'
                        : '日报'
                }}
              </td>
              <td>{{ report.date_from || '-' }} 至 {{ report.date_to || '-' }}</td>
              <td>
                <span class="pill">{{ report.status }}</span>
              </td>
              <td>{{ report.created_at }}</td>
              <td>
                <div class="action-group">
                  <button class="text-button" type="button" @click="select(report)">查看详情</button
                  ><button
                    v-if="report.status === 'success'"
                    v-permission="'report:download'"
                    class="text-button"
                    type="button"
                    @click="download(report, 'csv')"
                  >
                    下载 CSV</button
                  ><button
                    v-if="report.status === 'success'"
                    v-permission="'report:download'"
                    class="text-button"
                    type="button"
                    @click="download(report, 'xlsx')"
                  >
                    下载 Excel</button
                  ><button
                    v-if="report.status === 'success'"
                    v-permission="'report:download'"
                    class="text-button"
                    type="button"
                    @click="printPreview(report)"
                  >
                    打印/PDF
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="empty-state">暂无报表任务，请先生成报表。</p>
      <p v-if="message" class="feedback-text">{{ message }}</p>
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
      <template #footer>
        <button class="primary-button" type="button" @click="closeTasksModal">关闭</button>
      </template>
    </ModalPanel>

    <ModalPanel v-if="selected" :title="`报表任务详情 #${selected.id}`" @close="closeDetail">
      <span v-if="detailLoading" class="meta">加载中...</span>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <ReportPreview :report="selected" />
      <section class="detail-section">
        <h3>报表审计记录</h3>
        <div v-if="audits.length" class="audit-list">
          <div v-for="audit in audits" :key="audit.id" class="audit-row">
            <div>
              <strong>{{ audit.action }}</strong>
              <p class="meta">{{ audit.detail || '-' }}</p>
            </div>
            <span class="meta">{{ audit.created_at }}</span>
          </div>
        </div>
        <p v-else class="empty-state">该报表暂无审计记录。</p>
      </section>
      <template #footer>
        <button class="primary-button" type="button" @click="closeDetail">关闭</button>
      </template>
    </ModalPanel>
  </section>
</template>
