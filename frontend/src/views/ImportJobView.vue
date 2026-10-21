<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  confirmImportJob,
  downloadImportJobErrors,
  loadImportJobPreview,
  loadImportQualitySummary,
  retryImportJobErrors,
  type ImportJobPreview,
  type ImportQualitySummary,
} from '../services/imports'
import { apiBase, useSession } from '../session'
import ImportPreviewTable from '../components/ImportPreviewTable.vue'
import { importJobStatusLabel, templateRecognitionStatusLabel } from '../utils/labels'
import { rateText } from '../utils/importPreview'

const route = useRoute()
const session = useSession()
const job = ref<ImportJobPreview | null>(null)
const quality = ref<ImportQualitySummary | null>(null)
const qualityError = ref('')
const includeDuplicates = ref(false)
const retryJson = ref('')
const loading = ref(false)
const message = ref('')
const error = ref('')

const actionable = computed(
  () => job.value !== null && ['preview_pending', 'preview_failed'].includes(job.value.status),
)
const duplicateSuspectRows = computed(() =>
  job.value && job.value.job_type === 'bank_statement'
    ? job.value.preview_rows
        .filter((row) => row.status === 'duplicate_suspect')
        .map((row) => row.row_no)
    : [],
)

async function load() {
  if (!session.user.value || !session.token.value) return
  loading.value = true
  try {
    job.value = (
      await loadImportJobPreview(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        Number(route.params.jobId),
      )
    ).data
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '导入任务加载失败'
  } finally {
    loading.value = false
  }
}
async function loadQuality() {
  if (!session.user.value || !session.token.value) return
  try {
    quality.value = (
      await loadImportQualitySummary(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        30,
      )
    ).data
  } catch (cause) {
    qualityError.value = cause instanceof Error ? cause.message : '导入质量汇总加载失败'
  }
}
async function confirm() {
  if (!session.user.value || !session.token.value || !job.value) return
  loading.value = true
  try {
    const duplicates =
      includeDuplicates.value && duplicateSuspectRows.value.length
        ? duplicateSuspectRows.value
        : undefined
    job.value = (
      await confirmImportJob(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        job.value.job_id,
        duplicates,
      )
    ).data
    includeDuplicates.value = false
    message.value = '任务已确认入账。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '确认失败'
  } finally {
    loading.value = false
  }
}
async function retry() {
  if (!session.user.value || !session.token.value || !job.value) return
  try {
    job.value = (
      await retryImportJobErrors(
        apiBase(),
        session.token.value,
        session.user.value.tenant_id,
        job.value.job_id,
        JSON.parse(retryJson.value),
      )
    ).data
    retryJson.value = ''
    message.value = '失败行重试完成。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '失败行 JSON 不正确'
  }
}
async function downloadErrors() {
  if (!session.user.value || !session.token.value || !job.value) return
  try {
    const blob = await downloadImportJobErrors(
      apiBase(),
      session.token.value,
      session.user.value.tenant_id,
      job.value.job_id,
    )
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `import-job-${job.value.job_id}-errors.csv`
    link.click()
    URL.revokeObjectURL(link.href)
    message.value = '错误明细 CSV 已导出。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '错误明细导出失败'
  }
}
onMounted(() => {
  load()
  loadQuality()
})
</script>
<template>
  <section class="page-shell">
    <header class="hero-band">
      <div>
        <p class="eyebrow">导入任务</p>
        <h2>任务 #{{ route.params.jobId }}</h2>
        <p class="lead">查看模板识别、行级校验、确认入账和重试结果。</p>
      </div>
      <RouterLink class="ghost-button" to="/imports">返回导入向导</RouterLink>
    </header>
    <p v-if="error" class="error-banner">{{ error }}</p>
    <article class="panel">
      <div class="section-heading">
        <div>
          <h3>近 30 天导入质量</h3>
          <span class="meta">按创建时间统计银行流水导入任务</span>
        </div>
      </div>
      <div v-if="quality" class="import-summary">
        <span>任务数 {{ quality.job_count }}</span
        ><span>成功率 {{ rateText(quality.success_rate) }}</span
        ><span>疑似重复率 {{ rateText(quality.duplicate_suspect_rate) }}</span
        ><span>失败行数 {{ quality.failed_rows }}</span>
      </div>
      <p v-else-if="qualityError" class="error-banner">{{ qualityError }}</p>
      <p v-else class="empty-state">导入质量汇总加载中...</p>
    </article>
    <article v-if="job" class="panel">
      <div class="import-summary">
        <strong>{{ importJobStatusLabel(job.status) }}</strong
        ><span>总行数 {{ job.total_rows }}</span
        ><span>有效 {{ job.success_rows }}</span
        ><span>失败 {{ job.failed_rows }}</span
        ><span>跳过 {{ job.skipped_rows }}</span
        ><span v-if="job.duplicate_suspected_rows"
          >疑似重复 {{ job.duplicate_suspected_rows }}</span
        >
      </div>
      <p v-if="message" class="feedback-text">{{ message }}</p>
      <section v-if="job.recognized_templates?.length" class="detail-section">
        <h3>模板识别</h3>
        <div class="mini-list">
          <div
            v-for="template in job.recognized_templates"
            :key="template.sheet_name"
            class="mini-row"
          >
            <span>{{ template.sheet_name }} · {{ template.bank_name || '未识别' }}</span
            ><span class="pill">{{ templateRecognitionStatusLabel(template.status) }}</span>
          </div>
        </div>
      </section>
      <section class="detail-section">
        <h3>预览行</h3>
        <ImportPreviewTable :job-type="job.job_type" :rows="job.preview_rows" />
      </section>
      <section v-if="actionable && job.failed_rows" class="detail-section">
        <h3>失败行重试</h3>
        <textarea v-model="retryJson" rows="6" placeholder="请输入 JSON 数组" /><button
          class="ghost-button"
          type="button"
          @click="retry"
        >
          重新校验
        </button>
      </section>
      <div class="action-group">
        <label v-if="actionable && duplicateSuspectRows.length" class="checkbox-label"
          ><input v-model="includeDuplicates" type="checkbox" /> 同时导入
          {{ duplicateSuspectRows.length }} 条疑似重复流水</label
        ><button
          v-if="actionable"
          class="primary-button"
          :disabled="loading || (job.success_rows === 0 && !includeDuplicates)"
          type="button"
          @click="confirm"
        >
          确认入账</button
        ><button v-if="job.failed_rows" class="ghost-button" type="button" @click="downloadErrors">
          下载错误 CSV</button
        ><span v-if="loading" class="meta">处理中...</span>
      </div>
    </article>
    <p v-else-if="loading" class="empty-state">加载中...</p>
  </section>
</template>
